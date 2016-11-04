package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import org.apache.commons.lang3.RandomStringUtils;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.sagebionetworks.bridge.sdk.integration.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.rest.api.SurveysApi;
import org.sagebionetworks.bridge.rest.api.UploadSchemasApi;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.model.BooleanConstraints;
import org.sagebionetworks.bridge.rest.model.DataType;
import org.sagebionetworks.bridge.rest.model.GuidCreatedOnVersionHolder;
import org.sagebionetworks.bridge.rest.model.MultiValueConstraints;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.StringConstraints;
import org.sagebionetworks.bridge.rest.model.Survey;
import org.sagebionetworks.bridge.rest.model.SurveyQuestion;
import org.sagebionetworks.bridge.rest.model.SurveyQuestionOption;
import org.sagebionetworks.bridge.rest.model.UIHint;
import org.sagebionetworks.bridge.rest.model.UploadFieldDefinition;
import org.sagebionetworks.bridge.rest.model.UploadFieldType;
import org.sagebionetworks.bridge.rest.model.UploadSchema;
import org.sagebionetworks.bridge.rest.model.UploadSchemaType;

public class SurveySchemaTest {
    private static final Logger LOG = LoggerFactory.getLogger(SurveySchemaTest.class);

    private static final String SURVEY_NAME = "Compatibility Test Survey";

    private static TestUserHelper.TestUser developer;
    private static UploadSchemasApi schemasApi;
    private static SurveysApi surveysApi;

    private String surveyId;

    // We use SimpleGuidCreatedOnVersionHolder, because we need to use an immutable version holder, to ensure we're
    // deleting the correct surveys.
    private Set<GuidCreatedOnVersionHolder> surveysToDelete;

    @BeforeClass
    public static void beforeClass() throws Exception {
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
        TestUser admin = TestUserHelper.getSignedInAdmin();
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
        try {
            schemasApi.deleteAllRevisionsOfUploadSchema(surveyId).execute();
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
        // Create original survey.
        GuidCreatedOnVersionHolder keys = setupSurveySchemaTest();

        // Fetch and validate schema.
        UploadSchema schema = schemasApi.getMostRecentUploadSchema(surveyId).execute().body();
        validateCommonSchemaProperties(schema, keys);
        assertEquals(1, schema.getRevision().intValue());

        List<UploadFieldDefinition> fieldDefList = schema.getFieldDefinitions();
        assertEquals(2, fieldDefList.size());

        assertEquals("q1", fieldDefList.get(0).getName());
        assertFalse(fieldDefList.get(0).getRequired());
        assertEquals(UploadFieldType.MULTI_CHOICE, fieldDefList.get(0).getType());
        assertTrue(fieldDefList.get(0).getAllowOtherChoices());
        assertEquals(ImmutableList.of("foo", "bar"), fieldDefList.get(0).getMultiChoiceAnswerList());

        assertEquals("q2", fieldDefList.get(1).getName());
        assertFalse(fieldDefList.get(1).getRequired());
        assertEquals(UploadFieldType.BOOLEAN, fieldDefList.get(1).getType());

        // Update the survey. You can delete answer choices, fields, make compatible changes to fields.
        MultiValueConstraints updatedQ1Con = new MultiValueConstraints();
        updatedQ1Con.setAllowMultiple(true);
        updatedQ1Con.setAllowOther(false);
        updatedQ1Con.setDataType(DataType.STRING);
        
        SurveyQuestionOption option1 = new SurveyQuestionOption();
        option1.setLabel("asdf");
        
        SurveyQuestionOption option2 = new SurveyQuestionOption();
        option2.setLabel("qwerty");
        
        updatedQ1Con.setEnumeration(ImmutableList.of(option1, option2));

        SurveyQuestion updatedQ1 = new SurveyQuestion();
        updatedQ1.setIdentifier("q1");
        updatedQ1.setUiHint(UIHint.CHECKBOX);
        updatedQ1.setPrompt("Choose one or more or fewer");
        updatedQ1.setConstraints(updatedQ1Con);
        updatedQ1.setType("SurveyQuestion");

        SurveyQuestion q3 = new SurveyQuestion();
        q3.setIdentifier("q3");
        q3.setUiHint(UIHint.TEXTFIELD);
        q3.setPrompt("Write something:");
        
        StringConstraints sc = new StringConstraints();
        sc.setDataType(DataType.STRING);
        
        q3.setConstraints(sc);
        q3.setType("SurveyQuestion");
        
        GuidCreatedOnVersionHolder keysV2 = versionSurvey(keys);

        Survey surveyToUpdate = surveysApi.getSurvey(keysV2.getGuid(), keysV2.getCreatedOn()).execute().body();
        surveyToUpdate.getElements().clear();
        surveyToUpdate.getElements().add(updatedQ1);
        surveyToUpdate.getElements().add(q3);

        surveysApi.updateSurvey(surveyToUpdate.getGuid(), surveyToUpdate.getCreatedOn(), surveyToUpdate).execute();
        surveysApi.publishSurvey(keysV2.getGuid(), keysV2.getCreatedOn(), false).execute();

        // Fetch and validate schema. It should have been edited in-place, with new fields added.
        UploadSchema updatedSchema = schemasApi.getMostRecentUploadSchema(surveyId).execute().body();
        validateCommonSchemaProperties(updatedSchema, keysV2);
        assertEquals(1, updatedSchema.getRevision().intValue());

        List<UploadFieldDefinition> updatedFieldDefList = updatedSchema.getFieldDefinitions();
        assertEquals(3, updatedFieldDefList.size());

        assertEquals("q1", updatedFieldDefList.get(0).getName());
        assertFalse(updatedFieldDefList.get(0).getRequired());
        assertEquals(UploadFieldType.MULTI_CHOICE, updatedFieldDefList.get(0).getType());
        assertTrue(updatedFieldDefList.get(0).getAllowOtherChoices());
        assertEquals(ImmutableList.of("asdf", "qwerty", "foo", "bar"), updatedFieldDefList.get(0)
                .getMultiChoiceAnswerList());

        assertEquals("q3", updatedFieldDefList.get(1).getName());
        assertFalse(updatedFieldDefList.get(1).getRequired());
        assertEquals(UploadFieldType.STRING, updatedFieldDefList.get(1).getType());
        assertTrue(updatedFieldDefList.get(1).getUnboundedText());

        // q2 was deleted, but was appended to the end of the schema for compatibility. This should be identical to the
        // original q2.
        assertEquals(fieldDefList.get(1), updatedFieldDefList.get(2));
    }

    @Test
    public void incompatibleUpdate() throws Exception {
        // Create original survey. No need to validate, since this is validated in createAndUpdateSurveySchema().
        GuidCreatedOnVersionHolder keys = setupSurveySchemaTest();

        // Update the survey. We want an incompatible change, so delete q1 and modify q2's type.
        SurveyQuestion updatedQ2 = new SurveyQuestion();
        updatedQ2.setIdentifier("q2");
        updatedQ2.setUiHint(UIHint.TEXTFIELD);
        updatedQ2.setPrompt("Write something:");
        updatedQ2.setType("SurveyQuestion");
        
        StringConstraints sc = new StringConstraints();
        sc.setDataType(DataType.STRING);
        updatedQ2.setConstraints(sc);
        updatedQ2.setType("SurveyQuestion");

        GuidCreatedOnVersionHolder keysV2 = versionSurvey(keys);

        Survey surveyToUpdate = surveysApi.getSurvey(keysV2.getGuid(), keysV2.getCreatedOn()).execute().body();
        surveyToUpdate.getElements().clear();
        surveyToUpdate.getElements().add(updatedQ2);

        surveysApi.updateSurvey(surveyToUpdate.getGuid(), surveyToUpdate.getCreatedOn(), surveyToUpdate).execute();
        surveysApi.publishSurvey(keysV2.getGuid(), keysV2.getCreatedOn(), false).execute();

        // Fetch and validate schema. It should have been bumped to rev2. Deleted fields are not present.
        UploadSchema updatedSchema = schemasApi.getMostRecentUploadSchema(surveyId).execute().body();
        validateCommonSchemaProperties(updatedSchema, keysV2);
        assertEquals(2, updatedSchema.getRevision().intValue());

        List<UploadFieldDefinition> updatedFieldDefList = updatedSchema.getFieldDefinitions();
        assertEquals(1, updatedFieldDefList.size());

        assertEquals("q2", updatedFieldDefList.get(0).getName());
        assertFalse(updatedFieldDefList.get(0).getRequired());
        assertEquals(UploadFieldType.STRING, updatedFieldDefList.get(0).getType());
        assertTrue(updatedFieldDefList.get(0).getUnboundedText());
    }

    @Test
    public void explicitNewSchemaRev() throws Exception {
        // Create original survey. Again, we don't need to validate.
        GuidCreatedOnVersionHolder keys = setupSurveySchemaTest();

        // Update the survey. This is a compatible change, but we ask for a new schema rev anyway.
        MultiValueConstraints updatedQ1Con = new MultiValueConstraints();
        updatedQ1Con.setAllowMultiple(true);
        updatedQ1Con.setAllowOther(false);
        
        SurveyQuestionOption option1 = new SurveyQuestionOption();
        option1.setLabel("asdf");
        SurveyQuestionOption option2 = new SurveyQuestionOption();
        option2.setLabel("qwerty");
        updatedQ1Con.setEnumeration(ImmutableList.of(option1, option2));
        updatedQ1Con.setDataType(DataType.STRING);

        SurveyQuestion updatedQ1 = new SurveyQuestion();
        updatedQ1.setIdentifier("q1");
        updatedQ1.setUiHint(UIHint.CHECKBOX);
        updatedQ1.setPrompt("Choose one or more or fewer");
        updatedQ1.setConstraints(updatedQ1Con);
        updatedQ1.setType("SurveyQuestion");
        
        StringConstraints sc = new StringConstraints();
        sc.setDataType(DataType.STRING);

        SurveyQuestion q3 = new SurveyQuestion();
        q3.setIdentifier("q3");
        q3.setUiHint(UIHint.TEXTFIELD);
        q3.setPrompt("Write something:");
        q3.setConstraints(sc);
        q3.setType("SurveyQuestion");
        
        GuidCreatedOnVersionHolder keysV2 = versionSurvey(keys);

        Survey surveyToUpdate = surveysApi.getSurvey(keysV2.getGuid(), keysV2.getCreatedOn()).execute().body();
        surveyToUpdate.getElements().clear();
        surveyToUpdate.getElements().add(updatedQ1);
        surveyToUpdate.getElements().add(q3);

        keysV2 = surveysApi.updateSurvey(surveyToUpdate.getGuid(), surveyToUpdate.getCreatedOn(), surveyToUpdate).execute().body();
        keysV2 = surveysApi.publishSurvey(keysV2.getGuid(), keysV2.getCreatedOn(), true).execute().body();

        // Fetch and validate schema. We should get rev2, because we asked to bump the rev. We also shouldn't get any
        // of the rev 1 choices and fields.
        UploadSchema updatedSchema = schemasApi.getMostRecentUploadSchema(surveyId).execute().body();
        validateCommonSchemaProperties(updatedSchema, keysV2);
        assertEquals(2, updatedSchema.getRevision().intValue());

        List<UploadFieldDefinition> updatedFieldDefList = updatedSchema.getFieldDefinitions();
        assertEquals(2, updatedFieldDefList.size());

        assertEquals("q1", updatedFieldDefList.get(0).getName());
        assertFalse(updatedFieldDefList.get(0).getRequired());
        assertEquals(UploadFieldType.MULTI_CHOICE, updatedFieldDefList.get(0).getType());
        assertFalse(updatedFieldDefList.get(0).getAllowOtherChoices());
        assertEquals(ImmutableList.of("asdf", "qwerty"), updatedFieldDefList.get(0).getMultiChoiceAnswerList());

        assertEquals("q3", updatedFieldDefList.get(1).getName());
        assertFalse(updatedFieldDefList.get(1).getRequired());
        assertEquals(UploadFieldType.STRING, updatedFieldDefList.get(1).getType());
        assertTrue(updatedFieldDefList.get(1).getUnboundedText());
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
        q1.setType("SurveyQuestion");
        
        BooleanConstraints bc = new BooleanConstraints();
        bc.setDataType(DataType.BOOLEAN);
        
        SurveyQuestion q2 = new SurveyQuestion();
        q2.setIdentifier("q2");
        q2.setUiHint(UIHint.CHECKBOX);
        q2.setPrompt("Yes or No?");
        bc.setDataType(DataType.BOOLEAN);
        q2.setConstraints(bc);
        q2.setType("SurveyQuestion");

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
        assertEquals(UploadSchemaType.SURVEY, schema.getSchemaType());
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
