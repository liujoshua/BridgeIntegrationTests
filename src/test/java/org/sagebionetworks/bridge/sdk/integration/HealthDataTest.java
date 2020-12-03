package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.sagebionetworks.bridge.sdk.integration.Tests.STUDY_ID_1;

import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.sagebionetworks.bridge.json.DefaultObjectMapper;
import org.sagebionetworks.bridge.rest.RestUtils;
import org.sagebionetworks.bridge.rest.api.AppsApi;
import org.sagebionetworks.bridge.rest.api.HealthDataApi;
import org.sagebionetworks.bridge.rest.api.InternalApi;
import org.sagebionetworks.bridge.rest.api.ParticipantsApi;
import org.sagebionetworks.bridge.rest.api.SurveysApi;
import org.sagebionetworks.bridge.rest.api.UploadSchemasApi;
import org.sagebionetworks.bridge.rest.exceptions.BadRequestException;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.model.App;
import org.sagebionetworks.bridge.rest.model.DataType;
import org.sagebionetworks.bridge.rest.model.GuidCreatedOnVersionHolder;
import org.sagebionetworks.bridge.rest.model.HealthDataRecord;
import org.sagebionetworks.bridge.rest.model.HealthDataSubmission;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.SharingScope;
import org.sagebionetworks.bridge.rest.model.StringConstraints;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.rest.model.Survey;
import org.sagebionetworks.bridge.rest.model.SurveyQuestion;
import org.sagebionetworks.bridge.rest.model.UIHint;
import org.sagebionetworks.bridge.rest.model.UploadFieldDefinition;
import org.sagebionetworks.bridge.rest.model.UploadFieldType;
import org.sagebionetworks.bridge.rest.model.UploadSchema;
import org.sagebionetworks.bridge.rest.model.UploadSchemaType;
import org.sagebionetworks.bridge.rest.model.UploadValidationStrictness;
import org.sagebionetworks.bridge.user.TestUserHelper;

@Category(IntegrationSmokeTest.class)
@SuppressWarnings({ "ConstantConditions", "unchecked" })
public class HealthDataTest {
    private static final String APP_VERSION = "version 1.0.0, build 2";
    private static final DateTimeZone CREATED_ON_TIMEZONE = DateTimeZone.forOffsetHours(9);
    private static final String CREATED_ON_TIMEZONE_STRING = "+0900";
    private static final String PHONE_INFO = "Integration Tests";
    private static final String SCHEMA_ID = "health-data-integ-test-schema";
    private static final long SCHEMA_REV = 1L;
    private static final String SURVEY_ID = "health-data-integ-test-survey";

    private static TestUserHelper.TestUser developer;
    private static String externalIdentifier;
    private static AppsApi studiesApi;
    private static DateTime surveyCreatedOn;
    private static String surveyGuid;
    private static TestUserHelper.TestUser user;

    private DateTime createdOn;

    @BeforeClass
    public static void beforeClass() throws Exception {
        developer = TestUserHelper.createAndSignInUser(HealthDataTest.class, false, Role.DEVELOPER);
        studiesApi = developer.getClient(AppsApi.class);

        // Ensure schema exists, so we have something to submit against.
        UploadSchemasApi uploadSchemasApi = developer.getClient(UploadSchemasApi.class);
        UploadSchema schema = null;
        try {
            schema = uploadSchemasApi.getUploadSchema(SCHEMA_ID, SCHEMA_REV).execute().body();
        } catch (EntityNotFoundException ex) {
            // no-op
        }
        if (schema == null) {
            // Set both fields to explicitly true, to test validation.
            UploadFieldDefinition fooField = new UploadFieldDefinition();
            fooField.setName("foo");
            fooField.setType(UploadFieldType.STRING);
            fooField.setMaxLength(24);
            fooField.setRequired(true);

            UploadFieldDefinition barField = new UploadFieldDefinition();
            barField.setName("bar");
            barField.setType(UploadFieldType.ATTACHMENT_V2);
            barField.setMimeType("text/json");
            barField.setFileExtension(".json");
            barField.setRequired(true);

            schema = new UploadSchema();
            schema.setSchemaId(SCHEMA_ID);
            schema.setRevision(SCHEMA_REV);
            schema.setName("Health Data Integ Test Schema");
            schema.setSchemaType(UploadSchemaType.IOS_DATA);
            schema.setFieldDefinitions(ImmutableList.of(fooField, barField));
            uploadSchemasApi.createUploadSchema(schema).execute();
        }

        // Also ensure survey exists. We can't get survey by identifier, so the next best thing is to get the schema
        // by ID, and if it doesn't exist, we know to create the survey.
        UploadSchema surveySchema = null;
        try {
            surveySchema = uploadSchemasApi.getMostRecentUploadSchema(SURVEY_ID).execute().body();
            surveyGuid = surveySchema.getSurveyGuid();
            surveyCreatedOn = surveySchema.getSurveyCreatedOn();
        } catch (EntityNotFoundException ex) {
            // no op
        }
        if (surveySchema == null) {
            // Make a simple survey with one text answer.
            StringConstraints constraints = new StringConstraints();
            constraints.setDataType(DataType.STRING);
            constraints.setMaxLength(24);

            SurveyQuestion question = new SurveyQuestion();
            question.setIdentifier("answer-me");
            question.setConstraints(constraints);
            question.setPrompt("Answer me:");
            question.setUiHint(UIHint.TEXTFIELD);

            Survey survey = new Survey().name("Health Data Integ Test Survey").identifier(SURVEY_ID)
                    .addElementsItem(question);

            // Create and publish the survey and get its guid/createdOn.
            SurveysApi surveysApi = developer.getClient(SurveysApi.class);
            GuidCreatedOnVersionHolder surveyKeys = surveysApi.createSurvey(survey).execute().body();
            surveysApi.publishSurvey(surveyKeys.getGuid(), surveyKeys.getCreatedOn(), false).execute();

            // Remake the survey schema to include the old format survey fields, so that we can test for backwards
            // compatibility. Also, remake the "answers" field as an unbounded string to make it easier to test.
            surveySchema = uploadSchemasApi.getMostRecentUploadSchema(SURVEY_ID).execute().body();
            surveySchema.setRevision(null);
            surveySchema.getFieldDefinitions().clear();

            UploadFieldDefinition surveyQuestionFieldDef = new UploadFieldDefinition().name("answer-me")
                    .required(false).type(UploadFieldType.STRING).maxLength(24);
            surveySchema.addFieldDefinitionsItem(surveyQuestionFieldDef);

            UploadFieldDefinition answersFieldDef = new UploadFieldDefinition().name("answers").required(true)
                    .type(UploadFieldType.STRING).unboundedText(true);
            surveySchema.addFieldDefinitionsItem(answersFieldDef);

            uploadSchemasApi.createUploadSchema(surveySchema).execute();

            // Version and bump the survey to ensure that it points to the most up-to-date version of our schema.
            surveyKeys = surveysApi.versionSurvey(surveyKeys.getGuid(), surveyKeys.getCreatedOn()).execute().body();
            surveyGuid = surveyKeys.getGuid();
            surveyCreatedOn = surveyKeys.getCreatedOn();
            surveysApi.publishSurvey(surveyKeys.getGuid(), surveyKeys.getCreatedOn(), false).execute();
        }
        assertNotNull(surveyGuid);
        assertNotNull(surveyCreatedOn);

        // Set up user with data groups, external ID, and sharing scope.
        externalIdentifier = Tests.randomIdentifier(HealthDataTest.class);
        user = new TestUserHelper.Builder(UploadTest.class)
                .withExternalIds(ImmutableMap.of(STUDY_ID_1, externalIdentifier))
                .withConsentUser(true).createAndSignInUser();
        ParticipantsApi participantsApi = user.getClient(ParticipantsApi.class);

        StudyParticipant participant = participantsApi.getUsersParticipantRecord(false).execute().body();
        participant.setDataGroups(ImmutableList.of("group1"));
        participant.setSharingScope(SharingScope.SPONSORS_AND_PARTNERS);
        participantsApi.updateUsersParticipantRecord(participant).execute();
    }

    @AfterClass
    public static void deleteUser() throws Exception {
        if (user != null) {
            user.signOutAndDeleteUser();
        }
    }

    @AfterClass
    public static void resetUploadValidationStrictness() throws Exception {
        // Some of these tests change the strictness for the test. Reset it to REPORT.
        setUploadValidationStrictness(UploadValidationStrictness.REPORT);

        // Then delete the developer afterwards.
        if (developer != null) {
            developer.signOutAndDeleteUser();
        }
    }

    private static void setUploadValidationStrictness(UploadValidationStrictness strictness) throws Exception {
        App app = studiesApi.getUsersApp().execute().body();
        app.setUploadValidationStrictness(strictness);
        studiesApi.updateUsersApp(app).execute();
    }

    @Before
    public void before() {
        createdOn = DateTime.now(CREATED_ON_TIMEZONE);
    }

    @Test
    public void submitBySchema() throws Exception {
        // Set app to strict validation, so we throw if validation fails.
        setUploadValidationStrictness(UploadValidationStrictness.STRICT);

        // make health data to submit - Use a map instead of a Jackson JSON node, because mixing JSON libraries causes
        // bad things to happen.
        Map<String, String> data = ImmutableMap.<String, String>builder().put("foo", "foo value")
                .put("bar", "This is an attachment").build();
        Map<String, Object> metadata = ImmutableMap.<String, Object>builder().put("taskRunId", "test-task-guid")
                .put("lastMedicationHoursAgo", 3).build();
        HealthDataSubmission submission = new HealthDataSubmission().appVersion(APP_VERSION).createdOn(createdOn)
                .data(data).metadata(metadata).phoneInfo(PHONE_INFO).schemaId(SCHEMA_ID).schemaRevision(SCHEMA_REV);

        // submit and validate
        HealthDataRecord record = user.getClient(HealthDataApi.class).submitHealthData(submission).execute().body();
        assertEquals(APP_VERSION, record.getAppVersion());
        assertNotNull(record.getId());
        assertEquals(1, record.getDayInStudy().intValue());
        assertEquals(PHONE_INFO, record.getPhoneInfo());
        assertEquals(record.getId() + "-raw.json", record.getRawDataAttachmentId());
        assertEquals(SCHEMA_ID, record.getSchemaId());
        assertEquals(SCHEMA_REV, record.getSchemaRevision().longValue());
        assertNotNull(record.getUploadDate());
        assertNotNull(record.getUploadedOn());
        assertEquals(SharingScope.SPONSORS_AND_PARTNERS, record.getUserSharingScope());
        assertEquals(externalIdentifier, record.getUserExternalId());
        assertEquals(ImmutableList.of("group1"), record.getUserDataGroups());

        // createdOn is flattened to UTC server side.
        assertEquals(createdOn.getMillis(), record.getCreatedOn().getMillis());
        assertEquals(CREATED_ON_TIMEZONE_STRING, record.getCreatedOnTimeZone());

        // Data has foo "foo value" and bar is an attachment.
        Map<String, String> returnedDataMap = RestUtils.toType(record.getData(), Map.class);
        assertEquals(2, returnedDataMap.size());
        assertEquals("foo value", returnedDataMap.get("foo"));
        assertTrue(returnedDataMap.containsKey("bar"));
        assertNotEquals("This is an attachment", returnedDataMap.get("bar"));

        // Metadata just contains app version and phone info
        Map<String, String> returnedMetadataMap = RestUtils.toType(record.getMetadata(), Map.class);
        assertEquals(2, returnedMetadataMap.size());
        assertEquals(APP_VERSION, returnedMetadataMap.get("appVersion"));
        assertEquals(PHONE_INFO, returnedMetadataMap.get("phoneInfo"));

        // UserMetadata contains user-submitted metadata.
        // For whatever reason, GSON converts our int into a double.
        Map<String, Object> returnedUserMetadataMap = RestUtils.toType(record.getUserMetadata(), Map.class);
        assertEquals(2, returnedUserMetadataMap.size());
        assertEquals("test-task-guid", returnedUserMetadataMap.get("taskRunId"));
        assertEquals(3.0, (double) returnedUserMetadataMap.get("lastMedicationHoursAgo"), 0.001);

        // We can get the record back from the API.
        Optional<HealthDataRecord> returnedRecord = Tests.retryHelper(() -> user.getClient(InternalApi.class)
                        .getHealthDataByCreatedOn(createdOn, createdOn).execute().body().getItems().stream()
                        .filter(r -> r.getSchemaId().equals(SCHEMA_ID)).findAny(),
                Optional::isPresent);
        assertEquals(record, returnedRecord.get());
    }

    @Test
    public void submitBySurvey() throws Exception {
        // make health data to submit - Use a map instead of a Jackson JSON node, because mixing JSON libraries causes
        // bad things to happen.
        Map<String, String> data = ImmutableMap.<String, String>builder().put("answer-me", "C").build();
        HealthDataSubmission submission = new HealthDataSubmission().appVersion(APP_VERSION).createdOn(createdOn)
                .data(data).phoneInfo(PHONE_INFO).surveyGuid(surveyGuid).surveyCreatedOn(surveyCreatedOn);

        // submit and validate - Most of the record attributes are already validated in the previous test. Just
        // validate survey ID was set properly as the schema ID and that the data is correct.
        HealthDataRecord record = user.getClient(HealthDataApi.class).submitHealthData(submission).execute().body();
        assertEquals(SURVEY_ID, record.getSchemaId());
        assertNotNull(record.getSchemaRevision());

        Map<String, String> returnedDataMap = RestUtils.toType(record.getData(), Map.class);
        assertEquals(2, returnedDataMap.size());
        assertEquals("C", returnedDataMap.get("answer-me"));

        // For some bizarre reason, GSON won't parse the data.get("answers"). Use Jackson to parse it into a JsonNode.
        JsonNode rawAnswersNode = DefaultObjectMapper.INSTANCE.readTree(returnedDataMap.get("answers"));
        assertEquals(1, rawAnswersNode.size());
        assertEquals("C", rawAnswersNode.get("answer-me").textValue());
    }

    @Test
    public void schemalessHealthData() throws Exception {
        // make health data to submit - Use a map instead of a Jackson JSON node, because mixing JSON libraries causes
        // bad things to happen.
        Map<String, String> data = ImmutableMap.<String, String>builder().put("foo", "bar").build();
        HealthDataSubmission submission = new HealthDataSubmission().appVersion(APP_VERSION).createdOn(createdOn)
                .data(data).phoneInfo(PHONE_INFO);

        // submit and validate - Most of the record attributes are already validated in previous tests. Just validate
        // that the record was successfully submitted, has no schema, and has raw data.
        HealthDataRecord record = user.getClient(HealthDataApi.class).submitHealthData(submission).execute().body();
        assertNotNull(record);
        assertNull(record.getSchemaId());
        assertNull(record.getSchemaRevision());
        assertEquals(record.getId() + "-raw.json", record.getRawDataAttachmentId());
    }

    @Test
    public void developerCanSubmitHealthData() throws Exception {
        // make health data to submit - Use a map instead of a Jackson JSON node, because mixing JSON libraries causes
        // bad things to happen.
        Map<String, String> data = ImmutableMap.<String, String>builder().put("answer-me", "C").build();
        HealthDataSubmission submission = new HealthDataSubmission().appVersion(APP_VERSION).createdOn(createdOn)
                .data(data).phoneInfo(PHONE_INFO).surveyGuid(surveyGuid).surveyCreatedOn(surveyCreatedOn);

        // submit and validate - Most of the record attributes are already validated in the previous test. Just
        // validate survey ID was set properly as the schema ID and that the data is correct.
        HealthDataRecord record = developer.getClient(InternalApi.class).submitHealthDataForParticipant(
                user.getUserId(), submission).execute().body();
        assertEquals(SURVEY_ID, record.getSchemaId());
        assertNotNull(record.getSchemaRevision());

        Map<String, String> returnedDataMap = RestUtils.toType(record.getData(), Map.class);
        assertEquals(2, returnedDataMap.size());
        assertEquals("C", returnedDataMap.get("answer-me"));
        assertNotNull(returnedDataMap.get("answers"));

        // User can get the health data too.
        Optional<HealthDataRecord> returnedRecord = Tests.retryHelper(() -> user.getClient(InternalApi.class)
                        .getHealthDataByCreatedOn(createdOn, createdOn).execute().body().getItems().stream()
                        .filter(r -> r.getSchemaId().equals(SURVEY_ID)).findAny(),
                Optional::isPresent);
        assertEquals(record, returnedRecord.get());
    }

    @Test
    public void strictValidationThrows() throws Exception {
        setUploadValidationStrictness(UploadValidationStrictness.STRICT);

        // Make health data. This submission has foo, but not bar. Since bar is required, this trips Strict Validation.
        Map<String, String> data = ImmutableMap.<String, String>builder().put("foo", "foo value").build();
        HealthDataSubmission submission = new HealthDataSubmission().appVersion(APP_VERSION).createdOn(createdOn)
                .data(data).phoneInfo(PHONE_INFO).schemaId(SCHEMA_ID).schemaRevision(SCHEMA_REV);

        // submit and catch exception
        try {
            user.getClient(HealthDataApi.class).submitHealthData(submission).execute().body();
            fail("expected exception");
        } catch (BadRequestException ex) {
            assertTrue(ex.getMessage().contains("Required attachment field bar missing"));
        }
    }

    @Test
    public void reportStrictnessHasErrorMessage() throws Exception {
        setUploadValidationStrictness(UploadValidationStrictness.REPORT);

        // Make health data. This submission has foo, but not bar. Since bar is required, this trips Strict Validation.
        Map<String, String> data = ImmutableMap.<String, String>builder().put("foo", "foo value").build();
        HealthDataSubmission submission = new HealthDataSubmission().appVersion(APP_VERSION).createdOn(createdOn)
                .data(data).phoneInfo(PHONE_INFO).schemaId(SCHEMA_ID).schemaRevision(SCHEMA_REV);

        // submit and validate - Most of the record attributes are already validated in the previous test. Just
        // validate data and validationErrors.
        HealthDataRecord record = user.getClient(HealthDataApi.class).submitHealthData(submission).execute().body();
        assertTrue(record.getValidationErrors().contains("Required attachment field bar missing"));

        Map<String, String> returnedDataMap = RestUtils.toType(record.getData(), Map.class);
        assertEquals(1, returnedDataMap.size());
        assertEquals("foo value", returnedDataMap.get("foo"));
    }

    @Test
    public void warningStrictnessHasNoExceptionNorErrorMessage() throws Exception {
        setUploadValidationStrictness(UploadValidationStrictness.WARNING);

        // Make health data. This submission has foo, but not bar. Since bar is required, this trips Strict Validation.
        Map<String, String> data = ImmutableMap.<String, String>builder().put("foo", "foo value").build();
        HealthDataSubmission submission = new HealthDataSubmission().appVersion(APP_VERSION).createdOn(createdOn)
                .data(data).phoneInfo(PHONE_INFO).schemaId(SCHEMA_ID).schemaRevision(SCHEMA_REV);

        // submit and validate - Most of the record attributes are already validated in the previous test. Just
        // validate data and validate that it has no validationErrors.
        HealthDataRecord record = user.getClient(HealthDataApi.class).submitHealthData(submission).execute().body();
        assertNull(record.getValidationErrors());

        Map<String, String> returnedDataMap = RestUtils.toType(record.getData(), Map.class);
        assertEquals(1, returnedDataMap.size());
        assertEquals("foo value", returnedDataMap.get("foo"));
    }
}
