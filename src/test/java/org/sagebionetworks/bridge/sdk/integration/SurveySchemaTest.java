package org.sagebionetworks.bridge.sdk.integration;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.api.SurveysApi;
import org.sagebionetworks.bridge.rest.api.UploadSchemasApi;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.model.BooleanConstraints;
import org.sagebionetworks.bridge.rest.model.DataType;
import org.sagebionetworks.bridge.rest.model.GuidCreatedOnVersionHolder;
import org.sagebionetworks.bridge.rest.model.MultiValueConstraints;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.Survey;
import org.sagebionetworks.bridge.rest.model.SurveyQuestion;
import org.sagebionetworks.bridge.rest.model.SurveyQuestionOption;
import org.sagebionetworks.bridge.rest.model.UIHint;
import org.sagebionetworks.bridge.rest.model.UploadFieldDefinition;
import org.sagebionetworks.bridge.rest.model.UploadFieldType;
import org.sagebionetworks.bridge.rest.model.UploadSchema;
import org.sagebionetworks.bridge.rest.model.UploadSchemaType;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@SuppressWarnings("ConstantConditions")
public class SurveySchemaTest {
    private static final Logger LOG = LoggerFactory.getLogger(SurveySchemaTest.class);

    private static final String SURVEY_NAME = "Compatibility Test Survey";

    private static TestUser admin;
    private static TestUser developer;
    private static UploadSchemasApi schemasApi;
    private static SurveysApi surveysApi;

    private String surveyId;

    // We use SimpleGuidCreatedOnVersionHolder, because we need to use an immutable version holder, to ensure we're
    // deleting the correct surveys.
    private Set<GuidCreatedOnVersionHolder> surveysToDelete;

    @BeforeClass
    public static void beforeClass() throws Exception {
        admin = TestUserHelper.getSignedInAdmin();
        developer = TestUserHelper.createAndSignInUser(UploadSchemaTest.class, false, Role.DEVELOPER);
        schemasApi = developer.getClient(UploadSchemasApi.class);
        surveysApi = developer.getClient(SurveysApi.class);
    }

    @Before
    public void before() {
        surveyId = "SurveySchemaTest-" + RandomStringUtils.randomAlphabetic(4);

        // Init surveys to delete set. It's not clear whether JUnit will re-init member vars between each method, so we
        // do it here just to be clear.
        surveysToDelete = new HashSet<>();
    }

    @After
    public void after() throws Exception {
        // cleanup surveys
        SurveysApi surveysApi = admin.getClient(SurveysApi.class);
        for (GuidCreatedOnVersionHolder oneSurvey : surveysToDelete) {
            try {
                surveysApi.deleteSurvey(oneSurvey.getGuid(), oneSurvey.getCreatedOn(), true).execute();
            } catch (RuntimeException ex) {
                LOG.error("Error deleting survey=" + oneSurvey + ": " + ex.getMessage(), ex);
            }
        }
    }

    @After
    public void deleteSchemas() throws Exception {
        ForAdminsApi adminApi = admin.getClient(ForAdminsApi.class);
        try {
            adminApi.deleteAllRevisionsOfUploadSchema(surveyId, true).execute();
        } catch (EntityNotFoundException ex) {
            // Suppress the exception, as the test may have already deleted the schema.
        }
    }

    @AfterClass
    public static void deleteDeveloper() throws Exception {
        if (developer != null) {
            developer.signOutAndDeleteUser();
        }
    }

    @Test
    public void createAndUpdateSurveySchema() throws Exception {
        // Test Case 1: Create the survey schema.
        // Create original survey.
        GuidCreatedOnVersionHolder keys = setupSurveySchemaTest();

        // Fetch and validate schema.
        UploadSchema schema = schemasApi.getMostRecentUploadSchema(surveyId).execute().body();
        validateCommonSchemaProperties(schema, keys);
        assertEquals(1, schema.getRevision().intValue());

        List<UploadFieldDefinition> fieldDefList = schema.getFieldDefinitions();
        assertEquals(1, fieldDefList.size());

        assertEquals("answers", fieldDefList.get(0).getName());
        assertTrue(fieldDefList.get(0).isRequired());
        assertEquals(UploadFieldType.LARGE_TEXT_ATTACHMENT, fieldDefList.get(0).getType());

        // Test Case 2: Old survey schema format updated to thew new format. The "answers" field will be appended to
        // the old schema.
        schema.setRevision(null);
        fieldDefList.clear();

        UploadFieldDefinition q1FieldDef = new UploadFieldDefinition().name("q1").required(false)
                .type(UploadFieldType.MULTI_CHOICE).allowOtherChoices(true).addMultiChoiceAnswerListItem("foo")
                .addMultiChoiceAnswerListItem("bar");
        fieldDefList.add(q1FieldDef);

        UploadFieldDefinition q2FieldDef = new UploadFieldDefinition().name("q2").required(false)
                .type(UploadFieldType.BOOLEAN);
        fieldDefList.add(q2FieldDef);

        schema = schemasApi.createUploadSchema(schema).execute().body();
        validateCommonSchemaProperties(schema, keys);
        assertEquals(2, schema.getRevision().intValue());

        // Now version and publish the survey again. The schema will now have the answers field appended to the
        // old fields.
        keys = versionSurvey(keys);
        surveysApi.publishSurvey(keys.getGuid(), keys.getCreatedOn(), false).execute();

        schema = schemasApi.getMostRecentUploadSchema(surveyId).execute().body();
        validateCommonSchemaProperties(schema, keys);
        assertEquals(2, schema.getRevision().intValue());

        fieldDefList = schema.getFieldDefinitions();
        assertEquals(3, fieldDefList.size());

        assertEquals("q1", fieldDefList.get(0).getName());
        assertFalse(fieldDefList.get(0).isRequired());
        assertEquals(UploadFieldType.MULTI_CHOICE, fieldDefList.get(0).getType());
        assertTrue(fieldDefList.get(0).isAllowOtherChoices());
        assertEquals(ImmutableList.of("foo", "bar"), fieldDefList.get(0).getMultiChoiceAnswerList());

        assertEquals("q2", fieldDefList.get(1).getName());
        assertFalse(fieldDefList.get(1).isRequired());
        assertEquals(UploadFieldType.BOOLEAN, fieldDefList.get(1).getType());

        assertEquals("answers", fieldDefList.get(2).getName());
        assertTrue(fieldDefList.get(2).isRequired());
        assertEquals(UploadFieldType.LARGE_TEXT_ATTACHMENT, fieldDefList.get(2).getType());

        // Test Case 3: Schema has a different but compatible "answers" field.
        // Update the schema to have the "answers" field as an unbounded string instead of a large text attachment.
        // Make the field non-required to show that this doesn't matter.
        schema.setRevision(null);
        fieldDefList.clear();

        UploadFieldDefinition answersUnboundedStringFieldDef = new UploadFieldDefinition().name("answers")
                .required(false).type(UploadFieldType.STRING).unboundedText(true);
        fieldDefList.add(answersUnboundedStringFieldDef);

        schema = schemasApi.createUploadSchema(schema).execute().body();
        validateCommonSchemaProperties(schema, keys);
        assertEquals(3, schema.getRevision().intValue());

        // Now version and publish the survey again. The schema will be unchanged.
        keys = versionSurvey(keys);
        surveysApi.publishSurvey(keys.getGuid(), keys.getCreatedOn(), false).execute();

        schema = schemasApi.getMostRecentUploadSchema(surveyId).execute().body();
        validateCommonSchemaProperties(schema, keys);
        assertEquals(3, schema.getRevision().intValue());

        fieldDefList = schema.getFieldDefinitions();
        assertEquals(1, fieldDefList.size());

        assertEquals("answers", fieldDefList.get(0).getName());
        assertFalse(fieldDefList.get(0).isRequired());
        assertEquals(UploadFieldType.STRING, fieldDefList.get(0).getType());
        assertTrue(fieldDefList.get(0).isUnboundedText());

        // Test Case 4: Schema has a different and incompatible "answers" field. This will force a new schema rev.
        // Update the schema to have the "answers" field as a 1000-char "short" string.
        schema.setRevision(null);
        fieldDefList.clear();

        UploadFieldDefinition answersShortStringFieldDef = new UploadFieldDefinition().name("answers")
                .type(UploadFieldType.STRING).maxLength(1000);
        fieldDefList.add(answersShortStringFieldDef);

        schema = schemasApi.createUploadSchema(schema).execute().body();
        validateCommonSchemaProperties(schema, keys);
        assertEquals(4, schema.getRevision().intValue());

        // Now version and publish the survey again. We end up with a new schema rev.
        keys = versionSurvey(keys);
        surveysApi.publishSurvey(keys.getGuid(), keys.getCreatedOn(), false).execute();

        schema = schemasApi.getMostRecentUploadSchema(surveyId).execute().body();
        validateCommonSchemaProperties(schema, keys);
        assertEquals(5, schema.getRevision().intValue());

        fieldDefList = schema.getFieldDefinitions();
        assertEquals(1, fieldDefList.size());

        assertEquals("answers", fieldDefList.get(0).getName());
        assertTrue(fieldDefList.get(0).isRequired());
        assertEquals(UploadFieldType.LARGE_TEXT_ATTACHMENT, fieldDefList.get(0).getType());

        // Test Case 5: newSchemaRev=true. Even though the new schema rev is identical to the old one, this test that
        // the server cuts a new schema if we pass in the flag.
        keys = versionSurvey(keys);
        surveysApi.publishSurvey(keys.getGuid(), keys.getCreatedOn(), true).execute();

        schema = schemasApi.getMostRecentUploadSchema(surveyId).execute().body();
        validateCommonSchemaProperties(schema, keys);
        assertEquals(6, schema.getRevision().intValue());

        fieldDefList = schema.getFieldDefinitions();
        assertEquals(1, fieldDefList.size());

        assertEquals("answers", fieldDefList.get(0).getName());
        assertTrue(fieldDefList.get(0).isRequired());
        assertEquals(UploadFieldType.LARGE_TEXT_ATTACHMENT, fieldDefList.get(0).getType());
    }

    private GuidCreatedOnVersionHolder setupSurveySchemaTest() throws Exception {
        SurveyQuestionOption option1 = new SurveyQuestionOption();
        option1.setLabel("foo");
        
        SurveyQuestionOption option2 = new SurveyQuestionOption();
        option2.setLabel("bar");
        
        // set up survey
        MultiValueConstraints con = new MultiValueConstraints();
        con.setAllowMultiple(true);
        con.setAllowOther(true);
        con.setEnumeration(ImmutableList.of(option1, option2));
        con.setDataType(DataType.STRING);

        SurveyQuestion q1 = new SurveyQuestion();
        q1.setIdentifier("q1");
        q1.setUiHint(UIHint.CHECKBOX);
        q1.setPrompt("Choose one or more or fewer");
        q1.setConstraints(con);
        Tests.setVariableValueInObject(q1, "type", "SurveyQuestion");
        
        BooleanConstraints bc = new BooleanConstraints();
        bc.setDataType(DataType.BOOLEAN);
        
        SurveyQuestion q2 = new SurveyQuestion();
        q2.setIdentifier("q2");
        q2.setUiHint(UIHint.CHECKBOX);
        q2.setPrompt("Yes or No?");
        bc.setDataType(DataType.BOOLEAN);
        q2.setConstraints(bc);
        Tests.setVariableValueInObject(q2, "type", "SurveyQuestion");

        Survey survey = new Survey();
        survey.setName(SURVEY_NAME);
        survey.setIdentifier(surveyId);
        survey.setElements(Lists.newArrayList(q1,q2));

        // create and publish survey
        GuidCreatedOnVersionHolder key = createSurvey(survey);
        surveysApi.publishSurvey(key.getGuid(), key.getCreatedOn(), false).execute();
        return key;
    }

    private void validateCommonSchemaProperties(UploadSchema schema, GuidCreatedOnVersionHolder surveyKeys) {
        assertEquals(SURVEY_NAME, schema.getName());
        assertEquals(surveyId, schema.getSchemaId());
        assertEquals(UploadSchemaType.IOS_SURVEY, schema.getSchemaType());
        assertEquals(surveyKeys.getGuid(), schema.getSurveyGuid());
        assertEquals(surveyKeys.getCreatedOn(), schema.getSurveyCreatedOn());
    }

    // Helper methods to ensure we always record these calls for cleanup

    private GuidCreatedOnVersionHolder createSurvey(Survey survey) throws Exception {
        GuidCreatedOnVersionHolder versionHolder = surveysApi.createSurvey(survey).execute().body();
        surveysToDelete.add(versionHolder);
        return versionHolder;
    }

    private GuidCreatedOnVersionHolder versionSurvey(GuidCreatedOnVersionHolder survey) throws Exception {
        GuidCreatedOnVersionHolder versionHolder = surveysApi
                .versionSurvey(survey.getGuid(), survey.getCreatedOn()).execute().body();
        surveysToDelete.add(versionHolder);
        return versionHolder;
    }
}
