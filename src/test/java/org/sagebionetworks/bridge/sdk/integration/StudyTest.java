package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.sdk.ClientInfo;
import org.sagebionetworks.bridge.sdk.ClientProvider;
import org.sagebionetworks.bridge.sdk.Roles;
import org.sagebionetworks.bridge.sdk.StudyClient;
import org.sagebionetworks.bridge.sdk.UserClient;
import org.sagebionetworks.bridge.sdk.integration.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.sdk.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.sdk.exceptions.UnauthorizedException;
import org.sagebionetworks.bridge.sdk.exceptions.UnsupportedVersionException;
import org.sagebionetworks.bridge.sdk.models.DateTimeRangeResourceList;
import org.sagebionetworks.bridge.sdk.models.ResourceList;
import org.sagebionetworks.bridge.sdk.models.holders.VersionHolder;
import org.sagebionetworks.bridge.sdk.models.studies.OperatingSystem;
import org.sagebionetworks.bridge.sdk.models.studies.Study;
import org.sagebionetworks.bridge.sdk.models.upload.Upload;
import org.sagebionetworks.bridge.sdk.models.upload.UploadRequest;
import org.sagebionetworks.bridge.sdk.models.upload.UploadSession;

public class StudyTest {
    
    private TestUser admin;
    private boolean createdStudy;
    private Study study;

    @Before
    public void before() {
        admin = TestUserHelper.getSignedInAdmin();
        createdStudy = false;
        study = null;
    }
    
    @After
    public void after() {
        ClientProvider.setClientInfo(new ClientInfo.Builder().build());
        if (createdStudy && study != null) {
            admin.getSession().getStudyClient().deleteStudy(study.getIdentifier());
        }
        admin.getSession().signOut();
    }

    @Test
    public void crudStudy() throws Exception {
        StudyClient studyClient = admin.getSession().getStudyClient();
        
        String identifier = Tests.randomIdentifier(StudyTest.class);
        study = Tests.getStudy(identifier, null);
        assertNull("study version should be null", study.getVersion());
        
        VersionHolder holder = studyClient.createStudy(study);
        createdStudy = true;
        assertVersionHasUpdated(holder, study, null);

        Study newStudy = studyClient.getStudy(study.getIdentifier());
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
        assertEquals("dataGroups should be equal", study.getDataGroups(), newStudy.getDataGroups());
        assertEquals("android minSupportedAppVersions should be equal", study.getMinSupportedAppVersions().get(OperatingSystem.ANDROID),
                newStudy.getMinSupportedAppVersions().get(OperatingSystem.ANDROID));
        assertEquals("iOS minSupportedAppVersions should be equal", study.getMinSupportedAppVersions().get(OperatingSystem.IOS),
                newStudy.getMinSupportedAppVersions().get(OperatingSystem.IOS));
        // This was set to true even though we didn't set it.
        assertTrue("strictUploadValidationEnabled should be true", newStudy.isStrictUploadValidationEnabled());
        // And this is true because admins can set it to true. 
        assertTrue("healthCodeExportEnabled should be true", newStudy.isHealthCodeExportEnabled());
        // And this is also true
        assertTrue("emailVerificationEnabled should be true", newStudy.isEmailVerificationEnabled());
        
        Long oldVersion = newStudy.getVersion();
        alterStudy(newStudy);
        holder = studyClient.updateStudy(newStudy);
        assertVersionHasUpdated(holder, newStudy, oldVersion);
        
        Study newerStudy = studyClient.getStudy(newStudy.getIdentifier());
        assertEquals("Altered Test Study [SDK]", newerStudy.getName());
        assertEquals("test3@test.com", newerStudy.getSupportEmail());
        assertEquals("test4@test.com", newerStudy.getConsentNotificationEmail());

        studyClient.deleteStudy(identifier);
        try {
            studyClient.getStudy(identifier);
            fail("Should have thrown exception");
        } catch(EntityNotFoundException e) {
            // expected exception
        }
        study = null;
    }

    @Test
    public void researcherCannotAccessAnotherStudy() {
        TestUser researcher = TestUserHelper.createAndSignInUser(StudyTest.class, false, Roles.RESEARCHER);
        try {
            String identifier = Tests.randomIdentifier(StudyTest.class);
            study = Tests.getStudy(identifier, null);

            StudyClient studyClient = admin.getSession().getStudyClient();
            studyClient.createStudy(study);
            createdStudy = true;

            try {
                researcher.getSession().getStudyClient().getStudy(identifier);
                fail("Should not have been able to get this other study");
            } catch(UnauthorizedException e) {
                assertEquals("Unauthorized HTTP response code", 403, e.getStatusCode());
            }
        } finally {
            researcher.signOutAndDeleteUser();
        }
    }

    @Test(expected = UnauthorizedException.class)
    public void butNormalUserCannotAccessStudy() {
        TestUser user = TestUserHelper.createAndSignInUser(StudyTest.class, false);
        try {
            StudyClient rclient = user.getSession().getStudyClient();
            rclient.getCurrentStudy();
        } finally {
            user.signOutAndDeleteUser();
        }
    }
    
    @Test
    public void developerCannotSetHealthCodeToExportOrVerifyEmailWorkflow() {
        TestUser developer = TestUserHelper.createAndSignInUser(StudyTest.class, false, Roles.DEVELOPER);
        try {
            StudyClient studyClient = developer.getSession().getStudyClient();
            
            Study study = studyClient.getCurrentStudy();
            study.setHealthCodeExportEnabled(true);
            study.setEmailVerificationEnabled(false);
            studyClient.updateCurrentStudy(study);
            
            study = studyClient.getCurrentStudy();
            assertFalse("healthCodeExportEnabled should be true", study.isHealthCodeExportEnabled());
            assertTrue("emailVersificationEnabled should be true", study.isEmailVerificationEnabled());
        } finally {
            developer.signOutAndDeleteUser();
        }
    }

    @Test
    public void adminCanGetAllStudies() {
        StudyClient studyClient = admin.getSession().getStudyClient();
        
        ResourceList<Study> studies = studyClient.getAllStudies();
        assertTrue("Should be more than zero studies", studies.getTotal() > 0);
    }
    
    @Test
    public void userCannotAccessApisWithDeprecatedClient() {
        StudyClient studyClient = admin.getSession().getStudyClient();
        Study study = studyClient.getStudy(Tests.TEST_KEY);
        // Set a minimum value that should not any other tests
        if (study.getMinSupportedAppVersions().get(OperatingSystem.ANDROID) == null) {
            study.getMinSupportedAppVersions().put(OperatingSystem.ANDROID, 1);
            studyClient.updateStudy(study);
        }
        TestUser user = TestUserHelper.createAndSignInUser(StudyTest.class, true);
        try {
            
            // This is a version zero client, it should not be accepted
            ClientProvider.setClientInfo(new ClientInfo.Builder().withDevice("Unknown").withOsName("Android")
                    .withOsVersion("1").withAppName(Tests.APP_NAME).withAppVersion(0).build());
            user.getSession().getUserClient().getScheduledActivities(3, DateTimeZone.UTC);
            fail("Should have thrown exception");
            
        } catch(UnsupportedVersionException e) {
            // This is good.
        } finally {
            user.signOutAndDeleteUser();
        }
    }
    
    @Test
    public void getStudyUploads() throws Exception {
        TestUser developer = TestUserHelper.createAndSignInUser(StudyTest.class, false, Roles.DEVELOPER);
        TestUser user = TestUserHelper.createAndSignInUser(ParticipantsTest.class, true);
        TestUser user2 = TestUserHelper.createAndSignInUser(ParticipantsTest.class, true);
        try {
            StudyClient studyClient = developer.getSession().getStudyClient();
            DateTime startTime = DateTime.now().minusHours(2);
            DateTime endTime = startTime.plusHours(4);
            int count = studyClient.getUploads(startTime, endTime).getItems().size();

            // Create a REQUESTED record that we can retrieve through the reporting API.
            UploadRequest request = new UploadRequest.Builder()
                    .withContentType("application/zip").withContentLength(100).withContentMd5("ABC")
                    .withName("upload.zip").build();
            
            UserClient userClient = user.getSession().getUserClient();
            UploadSession uploadSession = userClient.requestUploadSession(request);
            
            UserClient userClient2 = user2.getSession().getUserClient();
            UploadSession uploadSession2 = userClient2.requestUploadSession(request);
            
            Thread.sleep(1000); // This does depend on a GSI, so pause for a bit.
            
            // This should retrieve both of the user's uploads.
            DateTimeRangeResourceList<Upload> results = studyClient.getUploads(startTime, endTime);
            assertTrue(startTime.equals(results.getStartTime()));
            assertTrue(endTime.equals(results.getEndTime()));

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
    
    private Upload getUpload(DateTimeRangeResourceList<Upload> results, String guid) {
        for (Upload upload : results.getItems()) {
            if (upload.getUploadId().equals(guid)) {
                return upload;
            }
        }
        return null;
    }
    
    private void assertVersionHasUpdated(VersionHolder holder, Study study, Long oldVersion) {
        assertNotNull("versionHolder should not have null version", holder.getVersion());
        assertNotNull("study should not have null versin", study.getVersion());
        assertEquals("holder version and study version should be equal", holder.getVersion(), study.getVersion());
        if (oldVersion != null) {
            assertNotEquals("old version should not equal study's current version", oldVersion, study.getVersion());
        }
    }
    
    private void alterStudy(Study study) {
        study.setName("Altered Test Study [SDK]");
        study.setSupportEmail("test3@test.com");
        study.setConsentNotificationEmail("test4@test.com");
    }

}
