package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.Locale;

import org.joda.time.DateTime;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.sagebionetworks.bridge.sdk.ClientProvider;
import org.sagebionetworks.bridge.sdk.Roles;
import org.sagebionetworks.bridge.sdk.UploadSchemaClient;
import org.sagebionetworks.bridge.sdk.UserClient;
import org.sagebionetworks.bridge.sdk.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.sdk.models.upload.UploadFieldDefinition;
import org.sagebionetworks.bridge.sdk.models.upload.UploadFieldType;
import org.sagebionetworks.bridge.sdk.models.upload.UploadRequest;
import org.sagebionetworks.bridge.sdk.models.upload.UploadSchema;
import org.sagebionetworks.bridge.sdk.models.upload.UploadSchemaType;
import org.sagebionetworks.bridge.sdk.models.upload.UploadSession;
import org.sagebionetworks.bridge.sdk.models.upload.UploadStatus;
import org.sagebionetworks.bridge.sdk.models.upload.UploadValidationStatus;

@Category(IntegrationSmokeTest.class)
public class UploadTest {
    private static final Logger LOG = LoggerFactory.getLogger(UploadTest.class);

    // On a cold server, validation could take up to 8 seconds (most of this is downloading and caching the encryption
    // certs for the first time). Subsequent validation attempts take about 2 seconds. 5 second delay is a good
    // compromise between fast tests and not having to retry a bunch of times.
    private static final int UPLOAD_STATUS_DELAY_MILLISECONDS = 5000;

    // Retry up to 6 times, so we don't spend more than 30 seconds per test.
    private static final int UPLOAD_STATUS_DELAY_RETRIES = 6;

    private static TestUserHelper.TestUser developer;
    private static TestUserHelper.TestUser user;

    @BeforeClass
    public static void beforeClass() {
        // developer is to ensure schemas exist. user is to do uploads
        developer = TestUserHelper.createAndSignInUser(UploadTest.class, false, Roles.DEVELOPER);
        user = TestUserHelper.createAndSignInUser(UploadTest.class, true);

        // ensure schemas exist, so we have something to upload against
        UploadSchemaClient uploadSchemaClient = developer.getSession().getUploadSchemaClient();

        UploadSchema legacySurveySchema = null;
        try {
            legacySurveySchema = uploadSchemaClient.getMostRecentUploadSchemaRevision("legacy-survey");
        } catch (EntityNotFoundException ex) {
            // no-op
        }
        if (legacySurveySchema == null) {
            legacySurveySchema = new UploadSchema.Builder().withSchemaId("legacy-survey").withRevision(1)
                    .withName("Legacy (RK/AC) Survey").withSchemaType(UploadSchemaType.IOS_SURVEY)
                    .withFieldDefinitions(
                            new UploadFieldDefinition.Builder().withName("AAA").withType(UploadFieldType.SINGLE_CHOICE)
                                    .build(),
                            new UploadFieldDefinition.Builder().withName("BBB").withType(UploadFieldType.MULTI_CHOICE)
                                    .build())
                    .build();
            uploadSchemaClient.createSchemaRevisionV4(legacySurveySchema);
        }

        UploadSchema legacyNonSurveySchema = null;
        try {
            legacyNonSurveySchema = uploadSchemaClient.getMostRecentUploadSchemaRevision("legacy-non-survey");
        } catch (EntityNotFoundException ex) {
            // no-op
        }
        if (legacyNonSurveySchema == null) {
            // Field types are already tested in UploadHandlersEndToEndTest in BridgePF unit tests. Don't need to
            // exhaustively test all field types, just a few representative ones: non-JSON attachment, JSON attachment,
            // attachment in JSON record, v1 type (string), v2 type (time)
            legacyNonSurveySchema = new UploadSchema.Builder().withSchemaId("legacy-non-survey").withRevision(1)
                    .withName("Legacy (RK/AC) Non-Survey").withSchemaType(UploadSchemaType.IOS_DATA)
                    .withFieldDefinitions(
                            new UploadFieldDefinition.Builder().withName("CCC.txt")
                                    .withType(UploadFieldType.ATTACHMENT_V2).build(),
                            new UploadFieldDefinition.Builder().withName("FFF.json")
                                    .withType(UploadFieldType.ATTACHMENT_V2).build(),
                            new UploadFieldDefinition.Builder().withName("record.json.HHH")
                                    .withType(UploadFieldType.ATTACHMENT_V2).build(),
                            new UploadFieldDefinition.Builder().withName("record.json.PPP")
                                    .withType(UploadFieldType.STRING).build(),
                            new UploadFieldDefinition.Builder().withName("record.json.QQQ")
                                    .withType(UploadFieldType.TIME_V2).build())
                    .build();
            uploadSchemaClient.createSchemaRevisionV4(legacyNonSurveySchema);
        }
    }

    @AfterClass
    public static void deleteResearcher() {
        if (developer != null) {
            developer.signOutAndDeleteUser();
        }
    }

    @AfterClass
    public static void deleteUser() {
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
        UploadRequest req = makeRequest(filePath);

        // upload to server
        UserClient userClient = user.getSession().getUserClient();
        UploadSession session = userClient.requestUploadSession(req);
        String uploadId = session.getId();
        LOG.info("UploadId=" + uploadId);
        userClient.upload(session, req, filePath);

        // get validation status
        UploadValidationStatus status = null;
        for (int i = 0; i < UPLOAD_STATUS_DELAY_RETRIES; i++) {
            Thread.sleep(UPLOAD_STATUS_DELAY_MILLISECONDS);

            status = userClient.getUploadStatus(session.getId());
            if (status.getStatus() == UploadStatus.VALIDATION_FAILED) {
                // Short-circuit. Validation failed. No need to retry.
                fail("Upload validation failed, UploadId=" + uploadId);
            } else if (status.getStatus() == UploadStatus.SUCCEEDED) {
                break;
            }
        }

        assertNotNull("Upload status is not null, UploadId=" + uploadId, status);
        assertEquals("Upload succeeded, UploadId=" + uploadId, UploadStatus.SUCCEEDED, status.getStatus());
        assertTrue("Upload has no validation messages, UploadId=" + uploadId, status.getMessageList().isEmpty());
    }

    @Test
    public void cannotUploadAfterExpirationDate() throws Exception {
        // Arbitrarily choose one of the test files. It doesn't matter which. It'll never make it to the server.
        String filePath = resolveFilePath("legacy-non-survey-encrypted");
        UploadRequest req = makeRequest(filePath);

        // request upload session
        UserClient userClient = user.getSession().getUserClient();
        UploadSession session = userClient.requestUploadSession(req);

        // Get number of milliseconds between now and expiration time, plus one second.
        long millis = session.getExpires()
                .minus(DateTime.now().getMillis())
                .plusSeconds(1)
                .getMillis();
        Thread.sleep(millis);

        try {
            userClient.upload(session, req, filePath);
            fail("userClient upload should have failed.");
        } catch (Exception e) {
            assertEquals("Exception thrown should be an illegal argument exception.",
                    e.getClass(), IllegalArgumentException.class);
        }
    }

    private static UploadRequest makeRequest(String filePath) throws Exception {
        File file = new File(filePath);
        return new UploadRequest.Builder().withFile(file).withContentType("application/zip").build();
    }

    // returns the path relative to the root of the project
    private static String resolveFilePath(String fileLeafName) {
        String envName = ClientProvider.getConfig().getEnvironment().name().toLowerCase(Locale.ENGLISH);
        return "src/test/resources/upload-test/" + envName + "/" + fileLeafName;
    }
}
