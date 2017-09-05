package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.Locale;

import com.google.common.collect.ImmutableList;
import org.joda.time.DateTime;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.sagebionetworks.bridge.rest.RestUtils;
import org.sagebionetworks.bridge.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.rest.api.ForWorkersApi;
import org.sagebionetworks.bridge.rest.api.UploadSchemasApi;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.model.HealthDataRecord;
import org.sagebionetworks.bridge.rest.model.RecordExportStatusRequest;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.SynapseExporterStatus;
import org.sagebionetworks.bridge.rest.model.UploadFieldDefinition;
import org.sagebionetworks.bridge.rest.model.UploadFieldType;
import org.sagebionetworks.bridge.rest.model.UploadSchema;
import org.sagebionetworks.bridge.rest.model.UploadSchemaType;
import org.sagebionetworks.bridge.rest.model.UploadSession;
import org.sagebionetworks.bridge.rest.model.UploadStatus;
import org.sagebionetworks.bridge.rest.model.UploadValidationStatus;

import com.google.common.collect.Lists;

@Category(IntegrationSmokeTest.class)
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

    @BeforeClass
    public static void beforeClass() throws Exception {
        // developer is to ensure schemas exist. user is to do uploads
        worker = TestUserHelper.createAndSignInUser(UploadTest.class, false, Role.WORKER);
        developer = TestUserHelper.createAndSignInUser(UploadTest.class, false, Role.DEVELOPER);
        user = TestUserHelper.createAndSignInUser(UploadTest.class, true);

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
    public static void deleteResearcher() throws Exception {
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
        testUpload("legacy-survey-encrypted");
    }

    @Test
    public void legacyNonSurvey() throws Exception {
        testUpload("legacy-non-survey-encrypted");
    }

    private static void testUpload(String fileLeafName) throws Exception {
        // set up request
        String filePath = resolveFilePath(fileLeafName);
        File file = new File(filePath);
        
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
        worker.getClient(ForWorkersApi.class).completeUploadSession(session.getId());

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

        // check for update record export status, no need to activate exporter in testing
        RecordExportStatusRequest statusRequest = new RecordExportStatusRequest();
        statusRequest.setRecordIds(ImmutableList.of(record.getId()));
        statusRequest.setSynapseExporterStatus(SynapseExporterStatus.NOT_EXPORTED);
        worker.getClient(ForWorkersApi.class).updateRecordExportStatuses(statusRequest).execute();

        status = usersApi.getUploadStatus(session.getId()).execute().body();
        assertEquals(SynapseExporterStatus.NOT_EXPORTED, status.getRecord().getSynapseExporterStatus());
    }

    // returns the path relative to the root of the project
    private static String resolveFilePath(String fileLeafName) {
        String envName = user.getClientManager().getConfig().getEnvironment().name().toLowerCase(Locale.ENGLISH);
        return "src/test/resources/upload-test/" + envName + "/" + fileLeafName;
    }
}
