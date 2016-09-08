package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.common.collect.ImmutableList;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.sagebionetworks.bridge.sdk.Roles;
import org.sagebionetworks.bridge.sdk.Session;
import org.sagebionetworks.bridge.sdk.SurveyClient;
import org.sagebionetworks.bridge.sdk.UploadSchemaClient;
import org.sagebionetworks.bridge.sdk.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.sdk.models.holders.GuidCreatedOnVersionHolder;
import org.sagebionetworks.bridge.sdk.models.holders.SimpleGuidCreatedOnVersionHolder;
import org.sagebionetworks.bridge.sdk.models.surveys.BooleanConstraints;
import org.sagebionetworks.bridge.sdk.models.surveys.MultiValueConstraints;
import org.sagebionetworks.bridge.sdk.models.surveys.StringConstraints;
import org.sagebionetworks.bridge.sdk.models.surveys.Survey;
import org.sagebionetworks.bridge.sdk.models.surveys.SurveyQuestion;
import org.sagebionetworks.bridge.sdk.models.surveys.SurveyQuestionOption;
import org.sagebionetworks.bridge.sdk.models.surveys.UiHint;
import org.sagebionetworks.bridge.sdk.models.upload.UploadFieldDefinition;
import org.sagebionetworks.bridge.sdk.models.upload.UploadFieldType;
import org.sagebionetworks.bridge.sdk.models.upload.UploadSchema;
import org.sagebionetworks.bridge.sdk.models.upload.UploadSchemaType;

public class SurveySchemaTest {
    private static final Logger LOG = LoggerFactory.getLogger(SurveySchemaTest.class);

    private static final String SURVEY_NAME = "Compatibility Test Survey";

    private static TestUserHelper.TestUser developer;
    private static UploadSchemaClient schemaClient;
    private static SurveyClient surveyClient;

    private String surveyId;

    // We use SimpleGuidCreatedOnVersionHolder, because we need to use an immutable version holder, to ensure we're
    // deleting the correct surveys.
    private Set<SimpleGuidCreatedOnVersionHolder> surveysToDelete;

    @BeforeClass
    public static void beforeClass() {
        developer = TestUserHelper.createAndSignInUser(UploadSchemaTest.class, false, Roles.DEVELOPER);
        schemaClient = developer.getSession().getUploadSchemaClient();
        surveyClient = developer.getSession().getSurveyClient();
    }

    @Before
    public void before() {
        surveyId = "SurveySchemaTest-" + RandomStringUtils.randomAlphabetic(4);

        // Init surveys to delete set. It's not clear whether JUnit will re-init member vars between each method, so we
        // do it here just to be clear.
        surveysToDelete = new HashSet<>();
    }

    @After
    public void after() {
        // cleanup surveys
        Session session = TestUserHelper.getSignedInAdmin().getSession();
        SurveyClient adminClient = session.getSurveyClient();
        for (SimpleGuidCreatedOnVersionHolder oneSurvey : surveysToDelete) {
            try {
                adminClient.deleteSurveyPermanently(oneSurvey);
            } catch (RuntimeException ex) {
                LOG.error("Error deleting survey=" + oneSurvey + ": " + ex.getMessage(), ex);
            }
        }
    }

    @After
    public void deleteSchemas() {
        try {
            schemaClient.deleteUploadSchemaAllRevisions(surveyId);
        } catch (EntityNotFoundException ex) {
            // Suppress the exception, as the test may have already deleted the schema.
        }
    }

    @AfterClass
    public static void deleteDeveloper() {
        if (developer != null) {
            developer.signOutAndDeleteUser();
        }
    }

    @Test
    public void createAndUpdateSurveySchema() {
        // Create original survey.
        GuidCreatedOnVersionHolder keys = setupSurveySchemaTest();

        // Fetch and validate schema.
        UploadSchema schema = schemaClient.getMostRecentUploadSchemaRevision(surveyId);
        validateCommonSchemaProperties(schema, keys);
        assertEquals(1, schema.getRevision().intValue());

        List<UploadFieldDefinition> fieldDefList = schema.getFieldDefinitions();
        assertEquals(2, fieldDefList.size());

        assertEquals("q1", fieldDefList.get(0).getName());
        assertFalse(fieldDefList.get(0).isRequired());
        assertEquals(UploadFieldType.MULTI_CHOICE, fieldDefList.get(0).getType());
        assertTrue(fieldDefList.get(0).getAllowOtherChoices());
        assertEquals(ImmutableList.of("foo", "bar"), fieldDefList.get(0).getMultiChoiceAnswerList());

        assertEquals("q2", fieldDefList.get(1).getName());
        assertFalse(fieldDefList.get(1).isRequired());
        assertEquals(UploadFieldType.BOOLEAN, fieldDefList.get(1).getType());

        // Update the survey. You can delete answer choices, fields, make compatible changes to fields.
        MultiValueConstraints updatedQ1Con = new MultiValueConstraints();
        updatedQ1Con.setAllowMultiple(true);
        updatedQ1Con.setAllowOther(false);
        updatedQ1Con.setEnumeration(ImmutableList.of(new SurveyQuestionOption("asdf"),
                new SurveyQuestionOption("qwerty")));

        SurveyQuestion updatedQ1 = new SurveyQuestion();
        updatedQ1.setIdentifier("q1");
        updatedQ1.setUiHint(UiHint.CHECKBOX);
        updatedQ1.setPrompt("Choose one or more or fewer");
        updatedQ1.setConstraints(updatedQ1Con);

        SurveyQuestion q3 = new SurveyQuestion();
        q3.setIdentifier("q3");
        q3.setUiHint(UiHint.TEXTFIELD);
        q3.setPrompt("Write something:");
        q3.setConstraints(new StringConstraints());

        GuidCreatedOnVersionHolder keysV2 = versionSurvey(keys);

        Survey surveyToUpdate = surveyClient.getSurvey(keysV2);
        surveyToUpdate.getElements().clear();
        surveyToUpdate.getElements().add(updatedQ1);
        surveyToUpdate.getElements().add(q3);

        surveyClient.updateSurvey(surveyToUpdate);
        surveyClient.publishSurvey(keysV2);

        // Fetch and validate schema. It should have been edited in-place, with new fields added.
        UploadSchema updatedSchema = schemaClient.getMostRecentUploadSchemaRevision(surveyId);
        validateCommonSchemaProperties(updatedSchema, keysV2);
        assertEquals(1, updatedSchema.getRevision().intValue());

        List<UploadFieldDefinition> updatedFieldDefList = updatedSchema.getFieldDefinitions();
        assertEquals(3, updatedFieldDefList.size());

        assertEquals("q1", updatedFieldDefList.get(0).getName());
        assertFalse(updatedFieldDefList.get(0).isRequired());
        assertEquals(UploadFieldType.MULTI_CHOICE, updatedFieldDefList.get(0).getType());
        assertTrue(updatedFieldDefList.get(0).getAllowOtherChoices());
        assertEquals(ImmutableList.of("asdf", "qwerty", "foo", "bar"), updatedFieldDefList.get(0)
                .getMultiChoiceAnswerList());

        assertEquals("q3", updatedFieldDefList.get(1).getName());
        assertFalse(updatedFieldDefList.get(1).isRequired());
        assertEquals(UploadFieldType.STRING, updatedFieldDefList.get(1).getType());
        assertTrue(updatedFieldDefList.get(1).isUnboundedText());

        // q2 was deleted, but was appended to the end of the schema for compatibility. This should be identical to the
        // original q2.
        assertEquals(fieldDefList.get(1), updatedFieldDefList.get(2));
    }

    @Test
    public void incompatibleUpdate() {
        // Create original survey. No need to validate, since this is validated in createAndUpdateSurveySchema().
        GuidCreatedOnVersionHolder keys = setupSurveySchemaTest();

        // Update the survey. We want an incompatible change, so delete q1 and modify q2's type.
        SurveyQuestion updatedQ2 = new SurveyQuestion();
        updatedQ2.setIdentifier("q2");
        updatedQ2.setUiHint(UiHint.TEXTFIELD);
        updatedQ2.setPrompt("Write something:");
        updatedQ2.setConstraints(new StringConstraints());

        GuidCreatedOnVersionHolder keysV2 = versionSurvey(keys);

        Survey surveyToUpdate = surveyClient.getSurvey(keysV2);
        surveyToUpdate.getElements().clear();
        surveyToUpdate.getElements().add(updatedQ2);

        surveyClient.updateSurvey(surveyToUpdate);
        surveyClient.publishSurvey(keysV2);

        // Fetch and validate schema. It should have been bumped to rev2. Deleted fields are not present.
        UploadSchema updatedSchema = schemaClient.getMostRecentUploadSchemaRevision(surveyId);
        validateCommonSchemaProperties(updatedSchema, keysV2);
        assertEquals(2, updatedSchema.getRevision().intValue());

        List<UploadFieldDefinition> updatedFieldDefList = updatedSchema.getFieldDefinitions();
        assertEquals(1, updatedFieldDefList.size());

        assertEquals("q2", updatedFieldDefList.get(0).getName());
        assertFalse(updatedFieldDefList.get(0).isRequired());
        assertEquals(UploadFieldType.STRING, updatedFieldDefList.get(0).getType());
        assertTrue(updatedFieldDefList.get(0).isUnboundedText());
    }

    @Test
    public void explicitNewSchemaRev() {
        // Create original survey. Again, we don't need to validate.
        GuidCreatedOnVersionHolder keys = setupSurveySchemaTest();

        // Update the survey. This is a compatible change, but we ask for a new schema rev anyway.
        MultiValueConstraints updatedQ1Con = new MultiValueConstraints();
        updatedQ1Con.setAllowMultiple(true);
        updatedQ1Con.setAllowOther(false);
        updatedQ1Con.setEnumeration(ImmutableList.of(new SurveyQuestionOption("asdf"),
                new SurveyQuestionOption("qwerty")));

        SurveyQuestion updatedQ1 = new SurveyQuestion();
        updatedQ1.setIdentifier("q1");
        updatedQ1.setUiHint(UiHint.CHECKBOX);
        updatedQ1.setPrompt("Choose one or more or fewer");
        updatedQ1.setConstraints(updatedQ1Con);

        SurveyQuestion q3 = new SurveyQuestion();
        q3.setIdentifier("q3");
        q3.setUiHint(UiHint.TEXTFIELD);
        q3.setPrompt("Write something:");
        q3.setConstraints(new StringConstraints());

        GuidCreatedOnVersionHolder keysV2 = versionSurvey(keys);

        Survey surveyToUpdate = surveyClient.getSurvey(keysV2);
        surveyToUpdate.getElements().clear();
        surveyToUpdate.getElements().add(updatedQ1);
        surveyToUpdate.getElements().add(q3);

        surveyClient.updateSurvey(surveyToUpdate);
        surveyClient.publishSurvey(keysV2, true);

        // Fetch and validate schema. We should get rev2, because we asked to bump the rev. We also shouldn't get any
        // of the rev 1 choices and fields.
        UploadSchema updatedSchema = schemaClient.getMostRecentUploadSchemaRevision(surveyId);
        validateCommonSchemaProperties(updatedSchema, keysV2);
        assertEquals(2, updatedSchema.getRevision().intValue());

        List<UploadFieldDefinition> updatedFieldDefList = updatedSchema.getFieldDefinitions();
        assertEquals(2, updatedFieldDefList.size());

        assertEquals("q1", updatedFieldDefList.get(0).getName());
        assertFalse(updatedFieldDefList.get(0).isRequired());
        assertEquals(UploadFieldType.MULTI_CHOICE, updatedFieldDefList.get(0).getType());
        assertFalse(updatedFieldDefList.get(0).getAllowOtherChoices());
        assertEquals(ImmutableList.of("asdf", "qwerty"), updatedFieldDefList.get(0).getMultiChoiceAnswerList());

        assertEquals("q3", updatedFieldDefList.get(1).getName());
        assertFalse(updatedFieldDefList.get(1).isRequired());
        assertEquals(UploadFieldType.STRING, updatedFieldDefList.get(1).getType());
        assertTrue(updatedFieldDefList.get(1).isUnboundedText());
    }

    private GuidCreatedOnVersionHolder setupSurveySchemaTest() {
        // set up survey
        MultiValueConstraints con = new MultiValueConstraints();
        con.setAllowMultiple(true);
        con.setAllowOther(true);
        con.setEnumeration(ImmutableList.of(new SurveyQuestionOption("foo"), new SurveyQuestionOption("bar")));

        SurveyQuestion q1 = new SurveyQuestion();
        q1.setIdentifier("q1");
        q1.setUiHint(UiHint.CHECKBOX);
        q1.setPrompt("Choose one or more or fewer");
        q1.setConstraints(con);

        SurveyQuestion q2 = new SurveyQuestion();
        q2.setIdentifier("q2");
        q2.setUiHint(UiHint.CHECKBOX);
        q2.setPrompt("Yes or No?");
        q2.setConstraints(new BooleanConstraints());

        Survey survey = new Survey();
        survey.setName(SURVEY_NAME);
        survey.setIdentifier(surveyId);
        survey.getElements().add(q1);
        survey.getElements().add(q2);

        // create and publish survey
        GuidCreatedOnVersionHolder key = createSurvey(survey);
        surveyClient.publishSurvey(key);
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

    private GuidCreatedOnVersionHolder createSurvey(Survey survey) {
        GuidCreatedOnVersionHolder versionHolder = surveyClient.createSurvey(survey);
        surveysToDelete.add(new SimpleGuidCreatedOnVersionHolder(versionHolder));
        return versionHolder;
    }

    private GuidCreatedOnVersionHolder versionSurvey(GuidCreatedOnVersionHolder survey) {
        GuidCreatedOnVersionHolder versionHolder = surveyClient.versionSurvey(survey);
        surveysToDelete.add(new SimpleGuidCreatedOnVersionHolder(versionHolder));
        return versionHolder;
    }
}
