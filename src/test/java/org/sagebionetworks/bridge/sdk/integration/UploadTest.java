package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.sagebionetworks.bridge.sdk.integration.Tests.STUDY_ID_1;
import static org.sagebionetworks.bridge.util.IntegTestUtils.SHARED_APP_ID;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.io.Files;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.fluent.Request;
import org.joda.time.DateTime;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.sagebionetworks.bridge.json.DefaultObjectMapper;
import org.sagebionetworks.bridge.rest.RestUtils;
import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.rest.api.ForResearchersApi;
import org.sagebionetworks.bridge.rest.api.ForWorkersApi;
import org.sagebionetworks.bridge.rest.api.UploadSchemasApi;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.exceptions.UnauthorizedException;
import org.sagebionetworks.bridge.rest.model.ExternalIdentifier;
import org.sagebionetworks.bridge.rest.model.HealthDataRecord;
import org.sagebionetworks.bridge.rest.model.RecordExportStatusRequest;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.SharingScope;
import org.sagebionetworks.bridge.rest.model.SignUp;
import org.sagebionetworks.bridge.rest.model.Study;
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
import org.sagebionetworks.bridge.rest.model.VersionHolder;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.util.IntegTestUtils;

import com.google.common.collect.Lists;

@Category(IntegrationSmokeTest.class)
@SuppressWarnings({ "ConstantConditions", "unchecked" })
public class UploadTest {
    
    private static final String EXTERNAL_ID = "upload-test-extid";
    
    // On a cold server, validation could take up to 8 seconds (most of this is downloading and caching the encryption
    // certs for the first time). Subsequent validation attempts take about 2 seconds. 5 second delay is a good
    // compromise between fast tests and not having to retry a bunch of times.
    private static final int UPLOAD_STATUS_DELAY_MILLISECONDS = 5000;

    // Retry up to 6 times, so we don't spend more than 30 seconds per test.
    private static final int UPLOAD_STATUS_DELAY_RETRIES = 6;
    
    private static TestUserHelper.TestUser worker;
    private static TestUserHelper.TestUser developer;
    private static TestUserHelper.TestUser otherStudyAdmin;
    private static TestUserHelper.TestUser researcher;
    private static TestUserHelper.TestUser studyAdmin;
    private static TestUserHelper.TestUser user;
    private static TestUserHelper.TestUser admin;

    @BeforeClass
    public static void beforeClass() throws Exception {
        admin = TestUserHelper.getSignedInAdmin();

        try {
            admin.getClient(ForAdminsApi.class).getStudy(STUDY_ID_1).execute();
        } catch(EntityNotFoundException e) {
            Study study = new Study().name(STUDY_ID_1).identifier(STUDY_ID_1);
            VersionHolder version = admin.getClient(ForAdminsApi.class).createStudy(study).execute().body();
            study.setVersion(version.getVersion());
        }
        
        // developer is to ensure schemas exist. user is to do uploads
        worker = TestUserHelper.createAndSignInUser(UploadTest.class, false, Role.WORKER);
        developer = TestUserHelper.createAndSignInUser(UploadTest.class, false, Role.DEVELOPER);
        otherStudyAdmin = TestUserHelper.createAndSignInUser(UploadTest.class, SHARED_APP_ID, Role.ADMIN);
        researcher = TestUserHelper.createAndSignInUser(UploadTest.class, false, Role.RESEARCHER);
        studyAdmin = TestUserHelper.createAndSignInUser(UploadTest.class, false, Role.ADMIN);

        ExternalIdentifier extId = new ExternalIdentifier().identifier(EXTERNAL_ID).studyId(STUDY_ID_1);
        ForResearchersApi researchersApi = researcher.getClient(ForResearchersApi.class);
        researchersApi.createExternalId(extId).execute();
        
        String emailAddress = IntegTestUtils.makeEmail(UploadTest.class);
        SignUp signUp = new SignUp().email(emailAddress).password(Tests.PASSWORD);
        signUp.setExternalId(EXTERNAL_ID); // which should, in turn, associate account to STUDY_ID.
        user = TestUserHelper.createAndSignInUser(UploadTest.class, true, signUp);

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

            // For backwards compatibility, include the new "answers" field side-by-side with the old fields. However,
            // make this an unbounded string instead of a large_text_attachment to make it easier to test.
            UploadFieldDefinition def3 = new UploadFieldDefinition().name("answers").required(true)
                    .type(UploadFieldType.STRING).unboundedText(true);

            legacySurveySchema = new UploadSchema();
            legacySurveySchema.setSchemaId("legacy-survey");
            legacySurveySchema.setRevision(1L);
            legacySurveySchema.setName("Legacy (RK/AC) Survey");
            legacySurveySchema.setSchemaType(UploadSchemaType.IOS_SURVEY);
            legacySurveySchema.addFieldDefinitionsItem(def1);
            legacySurveySchema.addFieldDefinitionsItem(def2);
            legacySurveySchema.addFieldDefinitionsItem(def3);
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
    public static void deleteOtherStudyAdmin() throws Exception {
        if (otherStudyAdmin != null) {
            otherStudyAdmin.signOutAndDeleteUser();
        }
    }

    @AfterClass
    public static void deleteResearcher() throws Exception {
        ForAdminsApi adminsApi = TestUserHelper.getSignedInAdmin().getClient(ForAdminsApi.class);
        adminsApi.deleteExternalId(EXTERNAL_ID).execute();
        if (researcher != null) {
            researcher.signOutAndDeleteUser();
        }
    }

    @AfterClass
    public static void deleteStudyAdmin() throws Exception {
        if (studyAdmin != null) {
            studyAdmin.signOutAndDeleteUser();
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
        HealthDataRecord record = testUpload(fileLeafName);
        Map<String, Object> data = RestUtils.toType(record.getData(), Map.class);
        assertEquals(3, data.size());
        assertEquals("Yes", data.get("AAA"));

        List<String> bbbAnswerList = RestUtils.toType(data.get("BBB"), List.class);
        assertEquals(3, bbbAnswerList.size());
        assertEquals("fencing", bbbAnswerList.get(0));
        assertEquals("running", bbbAnswerList.get(1));
        assertEquals("3", bbbAnswerList.get(2));

        // Answers node has all the same fields as data, except without its own answers field. Note that the answers
        // field doesn't go through canonicalization.
        // For some bizarre reason, GSON won't parse the data.get("answers"). Use Jackson to parse it into a JsonNode.
        JsonNode rawAnswerNode = DefaultObjectMapper.INSTANCE.readTree((String) data.get("answers"));
        assertEquals(2, rawAnswerNode.size());

        JsonNode rawAaaNode = rawAnswerNode.get("AAA");
        assertEquals(1, rawAaaNode.size());
        assertEquals("Yes", rawAaaNode.get(0).textValue());

        JsonNode rawBbbNode = rawAnswerNode.get("BBB");
        assertEquals(3, rawBbbNode.size());
        assertEquals("fencing", rawBbbNode.get(0).textValue());
        assertEquals("running", rawBbbNode.get(1).textValue());
        assertEquals(3, rawBbbNode.get(2).intValue());
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
        HealthDataRecord record = testUpload(fileLeafName);
        Map<String, Object> data = RestUtils.toType(record.getData(), Map.class);
        assertEquals(5, data.size());

        assertEquals("1337", data.get("record.json.PPP"));
        assertEquals("19:21:35.378", data.get("record.json.QQQ"));

        // CCC, FFF, and HHH are attachments, and the value of the data is a guid. Just verify that they exist.
        assertTrue(data.containsKey("CCC.txt"));
        assertTrue(data.containsKey("FFF.json"));
        assertTrue(data.containsKey("record.json.HHH"));
    }

    @Test
    public void schemaless() throws Exception {
        // Just test that the record was successfully submitted, has no schema, and has raw data.
        HealthDataRecord record = testUpload("schemaless-encrypted");
        assertNotNull(record);
        assertNull(record.getSchemaId());
        assertNull(record.getSchemaRevision());
        assertEquals(record.getId() + "-raw.zip", record.getRawDataAttachmentId());
    }

    private static HealthDataRecord testUpload(String fileLeafName) throws Exception {
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
        return record;
    }

    @Test
    public void miscTests() throws Exception {
        // This test tests synchronous mode, redrive, and get upload by upload ID / record ID APIs. They're all lumped
        // into a single method to avoid having to set up an upload multiple times.

        // use V2 Generic Survey, since that's the most straightforward to parse and validate.
        File file = resolveFilePath("generic-survey-encrypted");

        // Set user sharing scope, just to test metadata in upload validation.
        ForConsentedUsersApi usersApi = user.getClient(ForConsentedUsersApi.class);
        StudyParticipant participant = usersApi.getUsersParticipantRecord(false).execute().body();
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
        assertEquals(1, record.getUserStudyMemberships().size());
        assertEquals(EXTERNAL_ID, record.getUserStudyMemberships().get(STUDY_ID_1));

        Map<String, Object> data = RestUtils.toType(record.getData(), Map.class);
        assertEquals(3, data.size());
        assertNotNull(data.get("answers"));
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

        // Study admin can also retrieve this record.
        ForAdminsApi studyAdminApi = studyAdmin.getClient(ForAdminsApi.class);
        studyAdminApi.getUploadById(status.getId()).execute();
        studyAdminApi.getUploadByRecordId(record.getId()).execute();

        // Other study admin cannot retrieve this record.
        ForAdminsApi otherStudyAdminApi = otherStudyAdmin.getClient(ForAdminsApi.class);
        try {
            otherStudyAdminApi.getUploadById(status.getId()).execute();
            fail("exception expected");
        } catch (UnauthorizedException ex) {
            // expected exception
        }
        try {
            otherStudyAdminApi.getUploadByRecordId(record.getId()).execute();
            fail("exception expected");
        } catch (UnauthorizedException ex) {
            // expected exception
        }

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

    @Test
    public void notEncryptedNotZipped() throws Exception {
        // Manually make the upload request for this test. We need to write a file, since RestUtils expects a file.
        byte[] uploadContent = "dummy content".getBytes();
        File file = File.createTempFile("notEncryptedNotZipped", "txt");
        Files.write(uploadContent, file);
        String contentMd5 = Base64.encodeBase64String(DigestUtils.md5(uploadContent));

        // Create and return request.
        UploadRequest request = new UploadRequest();
        request.setName(file.getName());
        request.setContentLength(file.length());
        request.setContentMd5(contentMd5);
        request.setContentType("text/plain");
        request.setEncrypted(false);
        request.setZipped(false);

        ForConsentedUsersApi usersApi = user.getClient(ForConsentedUsersApi.class);
        UploadSession session = usersApi.requestUploadSession(request).execute().body();

        // Test CORS configuration of this pre-signed URL. This enables browsers to make these non-encrypted,
        // non-zipped uploads.
        HttpResponse response = Request.Options(session.getUrl())
                .setHeader(HttpTest.ACCESS_CONTROL_REQUEST_HEADERS, "accept, content-type")
                .setHeader(HttpTest.ACCESS_CONTROL_REQUEST_METHOD, "PUT")
                .setHeader(HttpTest.ORIGIN, "https://some.remote.server.org")
                .connectTimeout(HttpTest.TIMEOUT).execute().returnResponse();
        assertEquals(200, response.getStatusLine().getStatusCode());

        assertEquals("Should echo back the origin", "*",
                response.getFirstHeader(HttpTest.ACCESS_CONTROL_ALLOW_ORIGIN).getValue());
        assertEquals("Should echo back the access-control-allow-methods", "PUT",
                response.getFirstHeader(HttpTest.ACCESS_CONTROL_ALLOW_METHODS).getValue());
        assertEquals("Should echo back the access-control-allow-headers", "accept, content-type",
                response.getFirstHeader(HttpTest.ACCESS_CONTROL_ALLOW_HEADERS).getValue());

        // Upload the file.
        RestUtils.uploadToS3(file, session.getUrl(), "text/plain");
        String uploadId = session.getId();

        // Complete upload in synchronous mode.
        UploadValidationStatus status = usersApi.completeUploadSession(uploadId, true, false)
                .execute().body();
        assertEquals(UploadStatus.SUCCEEDED, status.getStatus());
        assertTrue(status.getMessageList().isEmpty());

        // Integ Tests doesn't have the capability to download from S3, so just verify the record has raw data.
        HealthDataRecord record = status.getRecord();
        assertNotNull(record.getRawDataAttachmentId());
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

        // User was just created just now, so we're always on day 1.
        assertEquals(1, record.getDayInStudy().intValue());

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
