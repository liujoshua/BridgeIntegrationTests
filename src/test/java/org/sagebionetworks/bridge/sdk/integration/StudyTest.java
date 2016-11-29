package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.sdk.integration.TestUserHelper.TestUser;

import org.sagebionetworks.bridge.rest.ClientManager;
import org.sagebionetworks.bridge.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.rest.api.StudiesApi;
import org.sagebionetworks.bridge.rest.api.UploadsApi;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.exceptions.UnauthorizedException;
import org.sagebionetworks.bridge.rest.exceptions.UnsupportedVersionException;
import org.sagebionetworks.bridge.rest.model.ClientInfo;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.Study;
import org.sagebionetworks.bridge.rest.model.StudyList;
import org.sagebionetworks.bridge.rest.model.Upload;
import org.sagebionetworks.bridge.rest.model.UploadList;
import org.sagebionetworks.bridge.rest.model.UploadRequest;
import org.sagebionetworks.bridge.rest.model.UploadSession;
import org.sagebionetworks.bridge.rest.model.VersionHolder;

public class StudyTest {
    
    private TestUser admin;
    private String studyId;

    @Before
    public void before() {
        admin = TestUserHelper.getSignedInAdmin();
    }
    
    @After
    public void after() throws Exception {
        if (studyId != null) {
            admin.getClient(StudiesApi.class).deleteStudy(studyId).execute();
        }
        admin.signOut();
    }

    @Test
    public void crudStudy() throws Exception {
        StudiesApi studiesApi = admin.getClient(StudiesApi.class);
        
        studyId = Tests.randomIdentifier(StudyTest.class);
        Study study = Tests.getStudy(studyId, null);
        assertNull("study version should be null", study.getVersion());
        
        VersionHolder holder = studiesApi.createStudy(study).execute().body();
        assertNotNull(holder.getVersion());

        Study newStudy = studiesApi.getStudy(study.getIdentifier()).execute().body();

        study.addDataGroupsItem("test_user"); // added by the server, required for equality of dataGroups.
        
        // Verify study has password/email templates
        assertNotNull("password policy should not be null", newStudy.getPasswordPolicy());
        assertNotNull("verify email template should not be null", newStudy.getVerifyEmailTemplate());
        assertNotNull("password reset template should not be null", newStudy.getResetPasswordTemplate());
        assertEquals("name should be equal", study.getName(), newStudy.getName());
        assertEquals("minAgeOfConsent should be equal", study.getMinAgeOfConsent(), newStudy.getMinAgeOfConsent());
        assertEquals("sponsorName should be equal", study.getSponsorName(), newStudy.getSponsorName());
        assertEquals("supportEmail should be equal", study.getSupportEmail(), newStudy.getSupportEmail());
        assertEquals("technicalEmail should be equal", study.getTechnicalEmail(), newStudy.getTechnicalEmail());
        assertTrue("usesCustomExportSchedule should be true", study.getUsesCustomExportSchedule());
        assertEquals("consentNotificationEmail should be equal", study.getConsentNotificationEmail(), newStudy.getConsentNotificationEmail());
        assertEquals("userProfileAttributes should be equal", study.getUserProfileAttributes(), newStudy.getUserProfileAttributes());
        assertEquals("taskIdentifiers should be equal", study.getTaskIdentifiers(), newStudy.getTaskIdentifiers());
        assertTrue("dataGroups should be equal", Tests.assertListsEqualIgnoringOrder(study.getDataGroups(), newStudy.getDataGroups()));
        assertEquals("android minSupportedAppVersions should be equal", study.getMinSupportedAppVersions().get("Android"),
                newStudy.getMinSupportedAppVersions().get("Android"));
        assertEquals("iOS minSupportedAppVersions should be equal", study.getMinSupportedAppVersions().get("iPhone OS"),
                newStudy.getMinSupportedAppVersions().get("iPhone OS"));
        // This was set to true even though we didn't set it.
        assertTrue("strictUploadValidationEnabled should be true", newStudy.getStrictUploadValidationEnabled());
        // And this is true because admins can set it to true. 
        assertTrue("healthCodeExportEnabled should be true", newStudy.getHealthCodeExportEnabled());
        // And this is also true
        assertTrue("emailVerificationEnabled should be true", newStudy.getEmailVerificationEnabled());
        
        Long oldVersion = newStudy.getVersion();
        alterStudy(newStudy);
        holder = studiesApi.updateStudy(newStudy.getIdentifier(), newStudy).execute().body();
        
        Study newerStudy = studiesApi.getStudy(newStudy.getIdentifier()).execute().body();
        assertTrue(newerStudy.getVersion() > oldVersion);
        
        assertEquals("Altered Test Study [SDK]", newerStudy.getName());
        assertEquals("test3@test.com", newerStudy.getSupportEmail());
        assertEquals("test4@test.com", newerStudy.getConsentNotificationEmail());

        studiesApi.deleteStudy(studyId).execute();
        try {
            studiesApi.getStudy(studyId).execute();
            fail("Should have thrown exception");
        } catch(EntityNotFoundException e) {
            // expected exception
        }
        studyId = null;
    }

    @Test
    public void researcherCannotAccessAnotherStudy() throws Exception {
        TestUser researcher = TestUserHelper.createAndSignInUser(StudyTest.class, false, Role.RESEARCHER);
        try {
            studyId = Tests.randomIdentifier(StudyTest.class);
            Study study = Tests.getStudy(studyId, null);

            StudiesApi adminStudiesApi = admin.getClient(StudiesApi.class);
            adminStudiesApi.createStudy(study).execute();

            try {
                StudiesApi resStudiesApi = researcher.getClient(StudiesApi.class);
                resStudiesApi.getStudy(studyId).execute();
                fail("Should not have been able to get this other study");
            } catch(UnauthorizedException e) {
                assertEquals("Unauthorized HTTP response code", 403, e.getStatusCode());
            }
        } finally {
            researcher.signOutAndDeleteUser();
        }
    }

    @Test(expected = UnauthorizedException.class)
    public void butNormalUserCannotAccessStudy() throws Exception {
        TestUser user = TestUserHelper.createAndSignInUser(StudyTest.class, false);
        try {
            StudiesApi studiesApi = user.getClient(StudiesApi.class);
            studiesApi.getUsersStudy().execute();
        } finally {
            user.signOutAndDeleteUser();
        }
    }
    
    @Test
    public void developerCannotSetHealthCodeToExportOrVerifyEmailWorkflow() throws Exception {
        TestUser developer = TestUserHelper.createAndSignInUser(StudyTest.class, false, Role.DEVELOPER);
        try {
            StudiesApi studiesApi = developer.getClient(StudiesApi.class);
            
            Study study = studiesApi.getUsersStudy().execute().body();
            study.setHealthCodeExportEnabled(true);
            study.setEmailVerificationEnabled(false);
            studiesApi.updateUsersStudy(study).execute();
            
            study = studiesApi.getUsersStudy().execute().body();
            assertFalse("healthCodeExportEnabled should be true", study.getHealthCodeExportEnabled());
            assertTrue("emailVersificationEnabled should be true", study.getEmailVerificationEnabled());
        } finally {
            developer.signOutAndDeleteUser();
        }
    }

    @Test
    public void adminCanGetAllStudies() throws Exception {
        StudiesApi studiesApi = admin.getClient(StudiesApi.class);
        
        StudyList studies = studiesApi.getStudies(null).execute().body();
        assertTrue("Should be more than zero studies", studies.getTotal() > 0);
    }
    
    @Test
    public void userCannotAccessApisWithDeprecatedClient() throws Exception {
        StudiesApi studiesApi = admin.getClient(StudiesApi.class);
        Study study = studiesApi.getStudy(Tests.TEST_KEY).execute().body();
        // Set a minimum value that should not any other tests
        if (study.getMinSupportedAppVersions().get("Android") == null) {
            study.getMinSupportedAppVersions().put("Android", 1);
            studiesApi.updateUsersStudy(study).execute();
        }
        TestUser user = TestUserHelper.createAndSignInUser(StudyTest.class, true);
        try {
            
            // This is a version zero client, it should not be accepted
            ClientInfo clientInfo = new ClientInfo();
            clientInfo.setDeviceName("Unknown");
            clientInfo.setOsName("Android");
            clientInfo.setOsVersion("1");
            clientInfo.setAppName(Tests.APP_NAME);
            clientInfo.setAppVersion(0);
            
            ClientManager manager = new ClientManager.Builder()
                    .withSignIn(user.getSignIn())
                    .withClientInfo(clientInfo)
                    .build();
            
            ForConsentedUsersApi usersApi = manager.getClient(ForConsentedUsersApi.class);
            
            usersApi.getScheduledActivities("+00:00", 3, null).execute();
            fail("Should have thrown exception");
            
        } catch(UnsupportedVersionException e) {
            // This is good.
        } finally {
            user.signOutAndDeleteUser();
        }
    }
    
    @Test
    public void getStudyUploads() throws Exception {
        TestUser developer = TestUserHelper.createAndSignInUser(StudyTest.class, false, Role.DEVELOPER);
        TestUser user = TestUserHelper.createAndSignInUser(ParticipantsTest.class, true);
        TestUser user2 = TestUserHelper.createAndSignInUser(ParticipantsTest.class, true);
        try {
            UploadsApi devUploadsApi = developer.getClient(UploadsApi.class);
            DateTime startTime = DateTime.now(DateTimeZone.UTC).minusHours(2);
            DateTime endTime = startTime.plusHours(4);
            int count = devUploadsApi.getUploads(startTime, endTime).execute().body().getItems().size();

            // Create a REQUESTED record that we can retrieve through the reporting API.
            UploadRequest request = new UploadRequest();
            request.setName("upload.zip");
            request.setContentType("application/zip");
            request.setContentLength(100L);
            request.setContentMd5("ABC");
            
            ForConsentedUsersApi usersApi = user.getClient(ForConsentedUsersApi.class);
            UploadSession uploadSession = usersApi.requestUploadSession(request).execute().body();
            
            UploadSession uploadSession2 = usersApi.requestUploadSession(request).execute().body();
            
            Thread.sleep(1000); // This does depend on a GSI, so pause for a bit.
            
            // This should retrieve both of the user's uploads.
            StudiesApi studiesApi = developer.getClient(StudiesApi.class);
            UploadList results = studiesApi.getUploads(startTime, endTime).execute().body();
            assertEquals(startTime, results.getStartTime());
            assertEquals(endTime, results.getEndTime());

            assertEquals(count+2, results.getItems().size());
            assertNotNull(getUpload(results, uploadSession.getId()));
            assertNotNull(getUpload(results, uploadSession2.getId()));
        } finally {
            if (user != null) {
                user.signOutAndDeleteUser();
            }
            if (user2 != null) {
                user2.signOutAndDeleteUser();
            }
            if (developer != null) {
                developer.signOutAndDeleteUser();
            }
        }
    }
    
    private Upload getUpload(UploadList results, String guid) {
        for (Upload upload : results.getItems()) {
            if (upload.getUploadId().equals(guid)) {
                return upload;
            }
        }
        return null;
    }
    
    private void alterStudy(Study study) {
        study.setName("Altered Test Study [SDK]");
        study.setSupportEmail("test3@test.com");
        study.setConsentNotificationEmail("test4@test.com");
    }

}
