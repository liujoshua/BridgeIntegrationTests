package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.common.collect.ImmutableList;
import org.joda.time.DateTime;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.sagebionetworks.bridge.rest.RestUtils;
import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.rest.api.ForWorkersApi;
import org.sagebionetworks.bridge.rest.api.UploadSchemasApi;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.model.HealthDataRecord;
import org.sagebionetworks.bridge.rest.model.RecordExportStatusRequest;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.SharingScope;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.rest.model.SynapseExporterStatus;
import org.sagebionetworks.bridge.rest.model.Upload;
import org.sagebionetworks.bridge.rest.model.UploadFieldDefinition;
import org.sagebionetworks.bridge.rest.model.UploadFieldType;
import org.sagebionetworks.bridge.rest.model.UploadRequest;
import org.sagebionetworks.bridge.rest.model.UploadSchema;
import org.sagebionetworks.bridge.rest.model.UploadSchemaType;
import org.sagebionetworks.bridge.rest.model.UploadSession;
import org.sagebionetworks.bridge.rest.model.UploadStatus;
import org.sagebionetworks.bridge.rest.model.UploadValidationStatus;
import org.sagebionetworks.bridge.user.TestUserHelper;

import com.google.common.collect.Lists;

@Category(IntegrationSmokeTest.class)
@SuppressWarnings("unchecked")
public class UploadTest {

    // On a cold server, validation could take up to 8 seconds (most of this is downloading and caching the encryption
    // certs for the first time). Subsequent validation attempts take about 2 seconds. 5 second delay is a good
    // compromise between fast tests and not having to retry a bunch of times.
    private static final int UPLOAD_STATUS_DELAY_MILLISECONDS = 5000;

    // Retry up to 6 times, so we don't spend more than 30 seconds per test.
    private static final int UPLOAD_STATUS_DELAY_RETRIES = 6;

    private static TestUserHelper.TestUser worker;
    private static TestUserHelper.TestUser developer;
    private static TestUserHelper.TestUser user;
    private static TestUserHelper.TestUser admin;

    @BeforeClass
    public static void beforeClass() throws Exception {
        // developer is to ensure schemas exist. user is to do uploads
        worker = TestUserHelper.createAndSignInUser(UploadTest.class, false, Role.WORKER);
        developer = TestUserHelper.createAndSignInUser(UploadTest.class, false, Role.DEVELOPER);
        user = TestUserHelper.createAndSignInUser(UploadTest.class, true);
        admin = TestUserHelper.getSignedInAdmin();
        
        // ensure schemas exist, so we have something to upload against
        UploadSchemasApi uploadSchemasApi = developer.getClient(UploadSchemasApi.class);

        UploadSchema legacySurveySchema = null;
        try {
            legacySurveySchema = uploadSchemasApi.getMostRecentUploadSchema("legacy-survey").execute().body();
        } catch (EntityNotFoundException ex) {
            // no-op
        }
        if (legacySurveySchema == null) {
            UploadFieldDefinition def1 = new UploadFieldDefinition();
            def1.setName("AAA");
            def1.setType(UploadFieldType.SINGLE_CHOICE);
            
            UploadFieldDefinition def2 = new UploadFieldDefinition();
            def2.setName("BBB");
            def2.setAllowOtherChoices(Boolean.FALSE);
            def2.setType(UploadFieldType.MULTI_CHOICE);
            def2.setMultiChoiceAnswerList(Lists.newArrayList("fencing", "football", "running", "swimming", "3"));
            
            legacySurveySchema = new UploadSchema();
            legacySurveySchema.setSchemaId("legacy-survey");
            legacySurveySchema.setRevision(1L);
            legacySurveySchema.setName("Legacy (RK/AC) Survey");
            legacySurveySchema.setSchemaType(UploadSchemaType.IOS_SURVEY);
            legacySurveySchema.setFieldDefinitions(Lists.newArrayList(def1,def2));
            uploadSchemasApi.createUploadSchema(legacySurveySchema).execute();
        }

        UploadSchema legacyNonSurveySchema = null;
        try {
            legacyNonSurveySchema = uploadSchemasApi.getMostRecentUploadSchema("legacy-non-survey").execute().body();
        } catch (EntityNotFoundException ex) {
            // no-op
        }
        if (legacyNonSurveySchema == null) {
            // Field types are already tested in UploadHandlersEndToEndTest in BridgePF unit tests. Don't need to
            // exhaustively test all field types, just a few representative ones: non-JSON attachment, JSON attachment,
            // attachment in JSON record, v1 type (string), v2 type (time)
            UploadFieldDefinition def1 = new UploadFieldDefinition();
            def1.setName("CCC.txt");
            def1.setType(UploadFieldType.ATTACHMENT_V2);
            UploadFieldDefinition def2 = new UploadFieldDefinition();
            def2.setName("FFF.json");
            def2.setType(UploadFieldType.ATTACHMENT_V2);
            UploadFieldDefinition def3 = new UploadFieldDefinition();
            def3.setName("record.json.HHH");
            def3.setType(UploadFieldType.ATTACHMENT_V2);
            UploadFieldDefinition def4 = new UploadFieldDefinition();
            def4.setName("record.json.PPP");
            def4.setType(UploadFieldType.STRING);
            UploadFieldDefinition def5 = new UploadFieldDefinition();
            def5.setName("record.json.QQQ");
            def5.setType(UploadFieldType.TIME_V2);
            
            legacyNonSurveySchema = new UploadSchema();
            legacyNonSurveySchema.setSchemaId("legacy-non-survey");
            legacyNonSurveySchema.setRevision(1L);
            legacyNonSurveySchema.setName("Legacy (RK/AC) Non-Survey");
            legacyNonSurveySchema.setSchemaType(UploadSchemaType.IOS_DATA);
            legacyNonSurveySchema.setFieldDefinitions(Lists.newArrayList(def1,def2,def3,def4,def5));
            uploadSchemasApi.createUploadSchema(legacyNonSurveySchema).execute();
        }
    }

    @AfterClass
    public static void deleteWorker() throws Exception {
        if (worker != null) {
            worker.signOutAndDeleteUser();
        }
    }

    @AfterClass
    public static void deleteDeveloper() throws Exception {
        if (developer != null) {
            developer.signOutAndDeleteUser();
        }
    }

    @AfterClass
    public static void deleteUser() throws Exception {
        if (user != null) {
            user.signOutAndDeleteUser();
        }
    }

    @Test
    public void legacySurvey() throws Exception {
        testSurvey("legacy-survey-encrypted");
    }

    @Test
    public void genericSurvey() throws Exception {
        testSurvey("generic-survey-encrypted");
    }

    private static void testSurvey(String fileLeafName) throws Exception {
        Map<String, Object> data = testUpload(fileLeafName);
        assertEquals(2, data.size());
        assertEquals("Yes", data.get("AAA"));

        List<String> bbbAnswerList = RestUtils.toType(data.get("BBB"), List.class);
        assertEquals(3, bbbAnswerList.size());
        assertEquals("fencing", bbbAnswerList.get(0));
        assertEquals("running", bbbAnswerList.get(1));
        assertEquals("3", bbbAnswerList.get(2));
    }

    @Test
    public void legacyNonSurvey() throws Exception {
        testNonSurvey("legacy-non-survey-encrypted");
    }

    @Test
    public void genericNonSurvey() throws Exception {
        testNonSurvey("generic-non-survey-encrypted");
    }

    private static void testNonSurvey(String fileLeafName) throws Exception {
        Map<String, Object> data = testUpload(fileLeafName);
        assertEquals(5, data.size());

        assertEquals("1337", data.get("record.json.PPP"));
        assertEquals("19:21:35.378", data.get("record.json.QQQ"));

        // CCC, FFF, and HHH are attachments, and the value of the data is a guid. Just verify that they exist.
        assertTrue(data.containsKey("CCC.txt"));
        assertTrue(data.containsKey("FFF.json"));
        assertTrue(data.containsKey("record.json.HHH"));
    }

    private static Map<String, Object> testUpload(String fileLeafName) throws Exception {
        // set up request
        File file = resolveFilePath(fileLeafName);
        
        ForConsentedUsersApi usersApi = user.getClient(ForConsentedUsersApi.class);
        UploadSession session = RestUtils.upload(usersApi, file);
        
        String uploadId = session.getId();
        
        // get validation status
        UploadValidationStatus status = null;
        for (int i = 0; i < UPLOAD_STATUS_DELAY_RETRIES; i++) {
            Thread.sleep(UPLOAD_STATUS_DELAY_MILLISECONDS);

            status = usersApi.getUploadStatus(session.getId()).execute().body();
            if (status.getStatus() == UploadStatus.VALIDATION_FAILED) {
                // Short-circuit. Validation failed. No need to retry.
                fail("Upload validation failed, UploadId=" + uploadId);
            } else if (status.getStatus() == UploadStatus.SUCCEEDED) {
                break;
            }
        }
        // userClient.upload marks the download complete
        // marking an already completed download as complete again should succeed (and be a no-op)
        worker.getClient(ForWorkersApi.class).completeUploadSession(session.getId(), false, false)
                .execute();

        validateUploadValidationStatus(uploadId, status);

        // check for update record export status, no need to activate exporter in testing
        HealthDataRecord record = status.getRecord();
        assertEquals(record.getId() + "-raw.zip", record.getRawDataAttachmentId());

        RecordExportStatusRequest statusRequest = new RecordExportStatusRequest();
        statusRequest.setRecordIds(ImmutableList.of(record.getId()));
        statusRequest.setSynapseExporterStatus(SynapseExporterStatus.NOT_EXPORTED);
        worker.getClient(ForWorkersApi.class).updateRecordExportStatuses(statusRequest).execute();

        status = usersApi.getUploadStatus(session.getId()).execute().body();
        assertEquals(SynapseExporterStatus.NOT_EXPORTED, status.getRecord().getSynapseExporterStatus());

        // Return data, so individual tests can validate.
        return RestUtils.toType(record.getData(), Map.class);
    }

    @Test
    public void miscTests() throws Exception {
        // This test tests synchronous mode, redrive, and get upload by upload ID / record ID APIs. They're all lumped
        // into a single method to avoid having to set up an upload multiple times.

        // use V2 Generic Survey, since that's the most straightforward to parse and validate.
        File file = resolveFilePath("generic-survey-encrypted");

        // Set user sharing scope, just to test metadata in upload validation.
        ForConsentedUsersApi usersApi = user.getClient(ForConsentedUsersApi.class);
        StudyParticipant participant = usersApi.getUsersParticipantRecord().execute().body();
        participant.setSharingScope(SharingScope.ALL_QUALIFIED_RESEARCHERS);
        usersApi.updateUsersParticipantRecord(participant).execute();

        // Upload the file.
        UploadRequest request = RestUtils.makeUploadRequestForFile(file);
        UploadSession session = usersApi.requestUploadSession(request).execute().body();
        RestUtils.uploadToS3(file, session.getUrl());
        String uploadId = session.getId();

        // Complete upload in synchronous mode.
        UploadValidationStatus status = usersApi.completeUploadSession(uploadId, true, false)
                .execute().body();
        validateUploadValidationStatus(uploadId, status);

        // Validate the record data.
        HealthDataRecord record = status.getRecord();
        assertEquals(SharingScope.ALL_QUALIFIED_RESEARCHERS, record.getUserSharingScope());

        Map<String, Object> data = RestUtils.toType(record.getData(), Map.class);
        assertEquals(2, data.size());
        assertEquals("Yes", data.get("AAA"));

        List<String> bbbAnswerList = RestUtils.toType(data.get("BBB"), List.class);
        assertEquals(3, bbbAnswerList.size());
        assertEquals("fencing", bbbAnswerList.get(0));
        assertEquals("running", bbbAnswerList.get(1));
        assertEquals("3", bbbAnswerList.get(2));
        
        // Should be possible to retrieve this record
        ForAdminsApi adminApi = admin.getClient(ForAdminsApi.class);
        
        Upload retrieved1 = adminApi.getUploadById(status.getId()).execute().body();
        Upload retrieved2 = adminApi.getUploadByRecordId(record.getId()).execute().body();
        
        assertNotNull(retrieved1.getHealthData());
        assertNotNull(retrieved2.getHealthData());
        assertEquals(retrieved1, retrieved2);

        // Worker can also retrieve this records.
        ForWorkersApi workerApi = worker.getClient(ForWorkersApi.class);

        Upload retrieved3 = workerApi.getUploadById(status.getId()).execute().body();
        Upload retrieved4 = workerApi.getUploadByRecordId(record.getId()).execute().body();

        assertNotNull(retrieved3.getHealthData());
        assertNotNull(retrieved4.getHealthData());
        assertEquals(retrieved3, retrieved4);

        // Change the user's sharing scope. This is the simplest change we can make that will be reflected when we
        // redrive the upload.
        participant.setSharingScope(SharingScope.SPONSORS_AND_PARTNERS);
        usersApi.updateUsersParticipantRecord(participant).execute();

        // Redrive.
        UploadValidationStatus status2 = usersApi.completeUploadSession(uploadId, true, true)
                .execute().body();
        validateUploadValidationStatus(uploadId, status2);

        // Validate.
        HealthDataRecord record2 = status2.getRecord();
        assertEquals(SharingScope.SPONSORS_AND_PARTNERS, record2.getUserSharingScope());
        assertEquals(record.getData(), record2.getData());
    }

    // returns the path relative to the root of the project
    private static File resolveFilePath(String fileLeafName) {
        String envName = user.getClientManager().getConfig().getEnvironment().name().toLowerCase(Locale.ENGLISH);
        String filePath = "src/test/resources/upload-test/" + envName + "/" + fileLeafName;
        return new File(filePath);
    }

    private static void validateUploadValidationStatus(String uploadId, UploadValidationStatus status) {
        assertNotNull("Upload status is not null, UploadId=" + uploadId, status);
        assertEquals("Upload succeeded, UploadId=" + uploadId, UploadStatus.SUCCEEDED, status.getStatus());
        assertTrue("Upload has no validation messages, UploadId=" + uploadId, status.getMessageList().isEmpty());

        // Test some basic record properties.
        HealthDataRecord record = status.getRecord();
        assertEquals(uploadId, record.getUploadId());
        assertNotNull(record.getId());
        assertEquals("version 1.0.0, build 1", record.getAppVersion());
        assertEquals("Integration Tests", record.getPhoneInfo());

        // For createdOn and createdOnTimeZone, these exist in the test files, but are kind of all over the place. For
        // now, just verify that the createdOn exists and that createdOnTimeZone can be parsed as a timezone as part of
        // a date.
        assertNotNull(record.getCreatedOn());
        assertNotNull(DateTime.parse("2017-01-25T16:36" + record.getCreatedOnTimeZone()));

        // Verify metadata. This is the same in all test cases to make things simple.
        Map<String, Object> userMetadata = RestUtils.toType(record.getUserMetadata(), Map.class);
        assertEquals(2, userMetadata.size());
        assertEquals("test-task-guid", userMetadata.get("taskRunId"));
        assertEquals(3.0, (double) userMetadata.get("lastMedicationHoursAgo"), 0.001);
    }
}
