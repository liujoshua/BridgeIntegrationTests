package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.sagebionetworks.bridge.sdk.ClientProvider;
import org.sagebionetworks.bridge.sdk.Config;
import org.sagebionetworks.bridge.sdk.Environment;
import org.sagebionetworks.bridge.sdk.ParticipantClient;
import org.sagebionetworks.bridge.sdk.Roles;
import org.sagebionetworks.bridge.sdk.SchedulePlanClient;
import org.sagebionetworks.bridge.sdk.UserClient;
import org.sagebionetworks.bridge.sdk.exceptions.ConsentRequiredException;
import org.sagebionetworks.bridge.sdk.integration.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.sdk.models.accounts.SharingScope;
import org.sagebionetworks.bridge.sdk.models.accounts.StudyParticipant;
import org.sagebionetworks.bridge.sdk.models.holders.GuidVersionHolder;
import org.sagebionetworks.bridge.sdk.models.holders.IdentifierHolder;
import org.sagebionetworks.bridge.sdk.models.schedules.SchedulePlan;
import org.sagebionetworks.bridge.sdk.models.schedules.ScheduledActivity;
import org.sagebionetworks.bridge.sdk.models.subpopulations.ConsentStatus;
import org.sagebionetworks.bridge.sdk.models.subpopulations.SubpopulationGuid;
import org.sagebionetworks.bridge.sdk.models.upload.Upload;
import org.sagebionetworks.bridge.sdk.models.upload.UploadRequest;
import org.sagebionetworks.bridge.sdk.models.upload.UploadSession;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;

import org.sagebionetworks.bridge.sdk.models.DateTimeRangeResourceList;
import org.sagebionetworks.bridge.sdk.models.PagedResourceList;
import org.sagebionetworks.bridge.sdk.models.ResourceList;
import org.sagebionetworks.bridge.sdk.models.accounts.AccountStatus;
import org.sagebionetworks.bridge.sdk.models.accounts.AccountSummary;

public class ParticipantsTest {
    private static final Logger LOG = LoggerFactory.getLogger(UploadTest.class);
    
    private TestUser admin;
    private TestUser researcher;
    
    @Before
    public void before() {
        admin = TestUserHelper.getSignedInAdmin();
        researcher = TestUserHelper.createAndSignInUser(ParticipantsTest.class, true, Roles.RESEARCHER);
    }
    
    @After
    public void after() {
        if (researcher != null) {
            researcher.signOutAndDeleteUser();
        }
    }

    // Note: A very similar test exists in UserParticipantTest
    @Test
    @Ignore
    public void canGetAndUpdateSelf() {
        TestUser user = TestUserHelper.createAndSignInUser(ParticipantsTest.class, true);
        try {
            UserClient userClient = user.getSession().getUserClient();
            
            StudyParticipant self = userClient.getStudyParticipant();
            assertEquals(user.getEmail(), self.getEmail());

            // Update and verify changes. Right now there's not a lot that can be changed
            StudyParticipant updates = new StudyParticipant.Builder().copyOf(self)
                    .withLanguages(Tests.newLinkedHashSet("nl","en"))
                    .withDataGroups(Sets.newHashSet("group1"))
                    .withNotifyByEmail(false)
                    .withSharingScope(SharingScope.ALL_QUALIFIED_RESEARCHERS)
                    .build();
            
            userClient.saveStudyParticipant(updates);
            assertEquals(SharingScope.ALL_QUALIFIED_RESEARCHERS, user.getSession().getStudyParticipant().getSharingScope());
            assertEquals(Sets.newHashSet("group1"), user.getSession().getStudyParticipant().getDataGroups());
            // also language, but this hasn't been added to the session object yet.
        } finally {
            user.signOutAndDeleteUser();
        }
    }
    
    @Test
    @Ignore
    public void retrieveParticipant() {
        ParticipantClient participantClient = researcher.getSession().getParticipantClient();
        
        StudyParticipant participant = participantClient.getStudyParticipant(researcher.getSession().getStudyParticipant().getId());
        // Verify that what we know about this test user exists in the record
        assertEquals(researcher.getEmail(), participant.getEmail());
        assertEquals(researcher.getSession().getStudyParticipant().getId(), participant.getId());
        assertTrue(participant.getRoles().contains(Roles.RESEARCHER));
        assertFalse(participant.getConsentHistories().get("api").isEmpty());
    }
    
    @Test
    @Ignore
    public void canRetrieveAndPageThroughParticipants() {
        ParticipantClient participantClient = researcher.getSession().getParticipantClient();
        
        PagedResourceList<AccountSummary> summaries = participantClient.getPagedAccountSummaries(0, 10, null);

        // Well we know there's at least two accounts... the admin and the researcher.
        assertEquals(new Integer(0), summaries.getOffsetBy());
        assertEquals(10, summaries.getPageSize());
        assertTrue(summaries.getItems().size() <= summaries.getTotal());
        assertTrue(summaries.getItems().size() > 2);
        
        AccountSummary summary = summaries.getItems().get(0);
        assertNotNull(summary.getCreatedOn());
        assertNotNull(summary.getEmail());
        assertNotNull(summary.getStatus());
        assertNotNull(summary.getId());
        
        // Filter to only the researcher
        summaries = participantClient.getPagedAccountSummaries(0, 10, researcher.getEmail());
        assertEquals(1, summaries.getItems().size());
        assertEquals(researcher.getEmail(), summaries.getItems().get(0).getEmail());
        assertEquals(researcher.getEmail(), summaries.getFilters().get("emailFilter"));
    }
    
    @Test(expected = IllegalArgumentException.class)
    @Ignore
    public void cannotSetBadOffset() {
        ParticipantClient participantClient = researcher.getSession().getParticipantClient();
        participantClient.getPagedAccountSummaries(-1, 10, null);
    }
    
    @Test(expected = IllegalArgumentException.class)
    @Ignore
    public void cannotSetBadPageSize() {
        ParticipantClient participantClient = researcher.getSession().getParticipantClient();
        participantClient.getPagedAccountSummaries(0, 4, null);
    }
    
    @Test
    @Ignore
    public void crudParticipant() throws Exception {
        String email = Tests.makeEmail(ParticipantsTest.class);
        Map<String,String> attributes = new ImmutableMap.Builder<String,String>().put("phone","123-456-7890").build();
        LinkedHashSet<String> languages = Tests.newLinkedHashSet("en","fr");
        Set<String> dataGroups = Sets.newHashSet("sdk-int-1", "sdk-int-2");
        DateTime createdOn = null;
        
        StudyParticipant participant = new StudyParticipant.Builder()
            .withFirstName("FirstName")
            .withLastName("LastName")
            .withPassword("P@ssword1!")
            .withEmail(email)
            .withExternalId("externalID")
            .withSharingScope(SharingScope.ALL_QUALIFIED_RESEARCHERS)
            .withNotifyByEmail(true)
            .withDataGroups(dataGroups)
            .withLanguages(languages)
            .withStatus(AccountStatus.DISABLED) // should be ignored
            .withAttributes(attributes)
            .build();
        ParticipantClient participantClient = researcher.getSession().getParticipantClient();
        IdentifierHolder idHolder = participantClient.createStudyParticipant(participant);
        
        String id = idHolder.getIdentifier();
        try {
            // It has been persisted. Right now we don't get the ID back so we have to conduct a 
            // search by email to get this user.
            // Can be found through paged results
            PagedResourceList<AccountSummary> search = participantClient.getPagedAccountSummaries(0, 10, email);
            assertEquals(1, search.getTotal());
            AccountSummary summary = search.getItems().get(0);
            assertEquals("FirstName", summary.getFirstName());
            assertEquals("LastName", summary.getLastName());
            assertEquals(email, summary.getEmail());
            
            // Can also get by the ID
            StudyParticipant retrieved = participantClient.getStudyParticipant(id);
            assertEquals("FirstName", retrieved.getFirstName());
            assertEquals("LastName", retrieved.getLastName());
            assertEquals(email, retrieved.getEmail());
            assertEquals("externalID", retrieved.getExternalId());
            assertNull(retrieved.getPassword());
            assertEquals(SharingScope.ALL_QUALIFIED_RESEARCHERS, retrieved.getSharingScope());
            assertTrue(retrieved.isNotifyByEmail());
            assertEquals(dataGroups, retrieved.getDataGroups());
            assertEquals(languages, retrieved.getLanguages());
            assertEquals(attributes.get("phone"), retrieved.getAttributes().get("phone"));
            assertEquals(AccountStatus.UNVERIFIED, retrieved.getStatus());
            assertNotNull(retrieved.getCreatedOn());
            assertNotNull(retrieved.getId());
            createdOn = retrieved.getCreatedOn();
            
            // Update the user. Identified by the email address
            Map<String,String> newAttributes = new ImmutableMap.Builder<String,String>().put("phone","206-555-1212").build();
            LinkedHashSet<String> newLanguages = Tests.newLinkedHashSet("de","sw");
            Set<String> newDataGroups = Sets.newHashSet("group1");
            
            StudyParticipant newParticipant = new StudyParticipant.Builder()
                    .withFirstName("FirstName2")
                    .withLastName("LastName2")
                    .withEmail(email)
                    .withExternalId("externalID2")
                    .withSharingScope(SharingScope.NO_SHARING)
                    .withNotifyByEmail(false)
                    .withDataGroups(newDataGroups)
                    .withLanguages(newLanguages)
                    .withAttributes(newAttributes)
                    .withId(id)
                    .withStatus(AccountStatus.ENABLED)
                    .build();
            participantClient.updateStudyParticipant(newParticipant);
            
            // We think there are issues with customData consistency. For the moment, pause.
            Thread.sleep(300);
            
            // Get it again, verify it has been updated
            retrieved = participantClient.getStudyParticipant(id);
            assertEquals("FirstName2", retrieved.getFirstName());
            assertEquals("LastName2", retrieved.getLastName());
            assertEquals(email, retrieved.getEmail());
            assertEquals("externalID2", retrieved.getExternalId());
            assertNull(retrieved.getPassword());
            assertEquals(SharingScope.NO_SHARING, retrieved.getSharingScope());
            assertFalse(retrieved.isNotifyByEmail());
            assertEquals(newDataGroups, retrieved.getDataGroups());
            assertEquals(newLanguages, retrieved.getLanguages());
            assertEquals(id, retrieved.getId());
            assertEquals(newAttributes.get("phone"), retrieved.getAttributes().get("phone"));
            assertEquals(AccountStatus.ENABLED, retrieved.getStatus()); // user was enabled
            assertEquals(createdOn, retrieved.getCreatedOn()); // hasn't been changed, still exists
        } finally {
            if (id != null) {
                admin.getSession().getAdminClient().deleteUser(id);    
            }
        }
    }
    
    @Test
    @Ignore
    public void canSendRequestResetPasswordEmail() {
        ParticipantClient participantClient = researcher.getSession().getParticipantClient();
        
        // This is sending an email, which is difficult to verify, but this at least should not throw an error.
        String userId = researcher.getSession().getStudyParticipant().getId();
        participantClient.requestResetPassword(userId);
    }
    
    @Test
    @Ignore
    public void canResendEmailVerification() {
        String userId =  researcher.getSession().getStudyParticipant().getId();
        ParticipantClient participantClient = researcher.getSession().getParticipantClient();
        
        participantClient.resendEmailVerification(userId);
    }
    
    @Test
    @Ignore
    public void canResendConsentAgreement() {
        String userId =  researcher.getSession().getStudyParticipant().getId();

        ConsentStatus status = researcher.getSession().getConsentStatuses().values().iterator().next();
        ParticipantClient participantClient = researcher.getSession().getParticipantClient();
        
        participantClient.resendConsentAgreement(userId, new SubpopulationGuid(status.getSubpopulationGuid()));
    }
    
    @Test
    @Ignore
    public void canWithdrawUserFromStudy() throws Exception {
        TestUser user = TestUserHelper.createAndSignInUser(ParticipantsTest.class, true);
        String userId = user.getSession().getStudyParticipant().getId();
        try {
            // Can get activities without an error... user is indeed consented.
            user.getSession().getUserClient().getScheduledActivities(1, DateTimeZone.UTC);
            // Session reflects this
            for (ConsentStatus status : user.getSession().getConsentStatuses().values()) {
                assertTrue(status.isConsented());
            }
            user.signOut();

            researcher.getSession().getParticipantClient().withdrawAllConsentsToResearch(userId,
                    "Testing withdrawal API.");
            
            user.signInAgain();
            fail("Should have thrown consent exception");
        } catch(ConsentRequiredException e) {
            for (ConsentStatus status : e.getSession().getConsentStatuses().values()) {
                assertFalse(status.isConsented());
            }            
        } finally {
            user.signOutAndDeleteUser();
        }
    }
    
    @Test
    @Ignore
    public void getActivityHistory() {
        // Make the user a developer so with one account, we can generate some tasks
        TestUser user = TestUserHelper.createAndSignInUser(ParticipantsTest.class, true, Roles.DEVELOPER);
        UserClient userClient = user.getSession().getUserClient();
        
        SchedulePlanClient spClient = user.getSession().getSchedulePlanClient();
        SchedulePlan plan = Tests.getDailyRepeatingSchedulePlan();
        GuidVersionHolder planKeys = spClient.createSchedulePlan(plan);
        try {
            String userId = user.getSession().getStudyParticipant().getId();

            // Now ask for something, so activities are generated
            ResourceList<ScheduledActivity> activities = userClient.getScheduledActivities(4, DateTimeZone.UTC);
            
            // Verify there is more than one activity
            int count = activities.getItems().size();
            assertTrue(count > 1);

            // Finish one of them so there is one less in the user's API
            ScheduledActivity finishMe = activities.getItems().get(0);
            finishMe.setStartedOn(DateTime.now());
            finishMe.setFinishedOn(DateTime.now());
            userClient.updateScheduledActivities(activities.getItems());
            
            // Finished task is now no longer in the list the user sees
            activities = userClient.getScheduledActivities(4, DateTimeZone.UTC);
            assertTrue(activities.getItems().size() < count);
            
            // But the researcher will still see the full list
            ParticipantClient participantClient = researcher.getSession().getParticipantClient();
            PagedResourceList<ScheduledActivity> resActivities = participantClient.getActivityHistory(userId, null, null);
            assertEquals(count, resActivities.getItems().size());
            
            // Researcher can delete all the activities as well.
            participantClient.deleteParticipantActivities(userId);
            
            resActivities = participantClient.getActivityHistory(userId, null, null);
            assertEquals(0, resActivities.getItems().size());
            
        } finally {
            if (user != null) {
                spClient.deleteSchedulePlan(planKeys.getGuid());
                user.signOutAndDeleteUser();
            }
        }
    }
    
    @Test
    public void getParticipantUploads() throws Exception {
        TestUser user = TestUserHelper.createAndSignInUser(ParticipantsTest.class, true);
        String userId = user.getSession().getStudyParticipant().getId();
        try {
            // Create a REQUESTED record that we can retrieve through the reporting API.
            UploadRequest request = new UploadRequest.Builder()
                    .withContentType("application/zip")
                    .withFile(new File(fileName())).build();
            
            UserClient userClient = user.getSession().getUserClient();
            UploadSession uploadSession = userClient.requestUploadSession(request);
            LOG.info(uploadSession.toString());
            
            user.signOut();
            // I think, but I'm not 100% sure, that we have an eventual consistency issue that's failing this test.
            Thread.sleep(30000);
            researcher.signInAgain();
            
            ParticipantClient participantClient = researcher.getSession().getParticipantClient();
            
            DateTime endTime = DateTime.now(DateTimeZone.UTC);
            DateTime startTime = endTime.minusDays(1).minusHours(23);

            DateTimeRangeResourceList<Upload> results = participantClient.getUploads(userId, startTime, endTime);
            LOG.info(results.toString());

            assertEquals(uploadSession.getId(), results.getItems().get(0).getUploadId());
            assertTrue(results.getItems().get(0).getContentLength() > 0);
            assertEquals(startTime, results.getStartTime());
            assertEquals(endTime, results.getEndTime());
            
        } finally {
            if (user != null) {
                user.signOutAndDeleteUser();
            }
        }
    }
    
    private String fileName() {
        // We don't care much about this file as we'll never attempt to upload it, but the SDK enforces its existence
        String envName = ClientProvider.getConfig().getEnvironment().name().toLowerCase();
        return "src/test/resources/upload-test/" + envName + "/legacy-non-survey-encrypted";
    }
}
