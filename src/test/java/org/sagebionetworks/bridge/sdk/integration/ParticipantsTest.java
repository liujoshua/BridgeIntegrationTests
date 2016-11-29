package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.sagebionetworks.bridge.sdk.integration.Tests.assertListsEqualIgnoringOrder;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.sdk.integration.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.rest.RestUtils;
import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.rest.api.ParticipantsApi;
import org.sagebionetworks.bridge.rest.api.SchedulesApi;
import org.sagebionetworks.bridge.rest.exceptions.BadRequestException;
import org.sagebionetworks.bridge.rest.exceptions.ConsentRequiredException;
import org.sagebionetworks.bridge.rest.model.AccountStatus;
import org.sagebionetworks.bridge.rest.model.AccountSummary;
import org.sagebionetworks.bridge.rest.model.AccountSummaryList;
import org.sagebionetworks.bridge.rest.model.ActivityList;
import org.sagebionetworks.bridge.rest.model.ConsentStatus;
import org.sagebionetworks.bridge.rest.model.GuidVersionHolder;
import org.sagebionetworks.bridge.rest.model.IdentifierHolder;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.SchedulePlan;
import org.sagebionetworks.bridge.rest.model.ScheduledActivity;
import org.sagebionetworks.bridge.rest.model.ScheduledActivityList;
import org.sagebionetworks.bridge.rest.model.SharingScope;
import org.sagebionetworks.bridge.rest.model.SignUp;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.rest.model.UploadList;
import org.sagebionetworks.bridge.rest.model.UploadRequest;
import org.sagebionetworks.bridge.rest.model.UploadSession;
import org.sagebionetworks.bridge.rest.model.Withdrawal;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class ParticipantsTest {
    private TestUser admin;
    private TestUser researcher;
    
    @Before
    public void before() throws Exception {
        admin = TestUserHelper.getSignedInAdmin();
        researcher = TestUserHelper.createAndSignInUser(ParticipantsTest.class, true, Role.RESEARCHER);
    }
    
    @After
    public void after() throws Exception {
        if (researcher != null) {
            researcher.signOutAndDeleteUser();
        }
    }

    // Note: A very similar test exists in UserParticipantTest
    @Test
    public void canGetAndUpdateSelf() throws Exception {
        TestUser user = TestUserHelper.createAndSignInUser(ParticipantsTest.class, true);
        try {
            ForConsentedUsersApi userApi = user.getClient(ForConsentedUsersApi.class);

            StudyParticipant self = userApi.getUsersParticipantRecord().execute().body();
            assertEquals(user.getEmail(), self.getEmail());

            // Update and verify changes. Right now there's not a lot that can be changed
            List<String> set = Lists.newArrayList();
            set.add("nl");
            set.add("en");

            self.setLanguages(set);
            self.setDataGroups(Lists.newArrayList("group1"));
            self.setNotifyByEmail(false);
            self.setSharingScope(SharingScope.ALL_QUALIFIED_RESEARCHERS);

            userApi.updateUsersParticipantRecord(self).execute();
            
            self = userApi.getUsersParticipantRecord().execute().body();
            assertEquals(SharingScope.ALL_QUALIFIED_RESEARCHERS, self.getSharingScope());
            assertEquals(Lists.newArrayList("group1"), self.getDataGroups());
            // also language, but this hasn't been added to the session object yet.
        } finally {
            user.signOutAndDeleteUser();
        }
    }
    
    @Test
    public void retrieveParticipant() throws Exception {
        ParticipantsApi participantsApi = researcher.getClient(ParticipantsApi.class);
        
        StudyParticipant participant = participantsApi.getParticipant(researcher.getSession().getId()).execute().body();
        // Verify that what we know about this test user exists in the record
        assertEquals(researcher.getEmail(), participant.getEmail());
        assertEquals(researcher.getSession().getId(), participant.getId());
        assertTrue(participant.getRoles().contains(Role.RESEARCHER));
        assertFalse(participant.getConsentHistories().get("api").isEmpty());
    }
    
    @Test
    public void canRetrieveAndPageThroughParticipants() throws Exception {
        ParticipantsApi participantsApi = researcher.getClient(ParticipantsApi.class);
        
        AccountSummaryList summaries = participantsApi.getParticipants(0, 10, null, null, null).execute().body();

        int total = summaries.getTotal();
        Collections.sort(summaries.getItems(), Comparator.comparing(AccountSummary::getCreatedOn));
        DateTime oldest = summaries.getItems().get(0).getCreatedOn();
        DateTime newest = summaries.getItems().get(summaries.getItems().size()-1).getCreatedOn();
        
        // Well we know there's at least two accounts... the admin and the researcher.
        assertEquals((Long)0L, summaries.getOffsetBy());
        assertEquals((Integer)10, summaries.getPageSize());
        assertTrue(summaries.getItems().size() <= summaries.getTotal());
        assertTrue(summaries.getItems().size() > 2);
        
        AccountSummary summary = summaries.getItems().get(0);
        assertNotNull(summary.getCreatedOn());
        assertNotNull(summary.getEmail());
        assertNotNull(summary.getStatus());
        assertNotNull(summary.getId());
        
        // Filter to only the researcher
        summaries = participantsApi.getParticipants(0, 10, researcher.getEmail(), null, null).execute().body();
        assertEquals(1, summaries.getItems().size());
        assertEquals(researcher.getEmail(), summaries.getItems().get(0).getEmail());
        assertEquals(researcher.getEmail(), summaries.getEmailFilter());

        // Date filter that would not include the newest participant
        summaries = participantsApi.getParticipants(0, 10, null, null, oldest.plusDays(1)).execute().body();
        assertTrue(summaries.getTotal() < total);
        assertNull(summaries.getStartDate());
        assertEquals(summaries.getEndDate(), oldest.plusDays(1));
        doesNotIncludeThisAccountCreatedOn(summaries, newest);
        
        // Date filter that would not include the oldest participant
        summaries = participantsApi.getParticipants(0, 10, null, newest.minusDays(1), null).execute().body();
        assertTrue(summaries.getTotal() < total);
        assertEquals(summaries.getStartDate(), newest.minusDays(1));
        assertNull(summaries.getEndDate());
        doesNotIncludeThisAccountCreatedOn(summaries, oldest);
    }
    
    private boolean doesNotIncludeThisAccountCreatedOn(AccountSummaryList list, DateTime createdOn) {
        for (AccountSummary summary : list.getItems()) {
            if (summary.getCreatedOn().equals(createdOn)) {
                return false;
            }
        }
        return true;
    }
    
    @Test(expected = BadRequestException.class)
    public void cannotSetBadOffset() throws Exception {
        ParticipantsApi participantsApi = researcher.getClient(ParticipantsApi.class);
        
        participantsApi.getParticipants(-1, 10, null, null, null).execute();
    }
    
    @Test(expected = BadRequestException.class)
    public void cannotSetBadPageSize() throws Exception {
        ParticipantsApi participantsApi = researcher.getClient(ParticipantsApi.class);
        
        participantsApi.getParticipants(0, 4, null, null, null).execute();
    }
    
    @Test
    public void crudParticipant() throws Exception {
        String email = Tests.makeEmail(ParticipantsTest.class);
        Map<String,String> attributes = new ImmutableMap.Builder<String,String>().put("phone","123-456-7890").build();
        List<String> languages = Lists.newArrayList("en","fr");
        List<String> dataGroups = Lists.newArrayList("sdk-int-1", "sdk-int-2");
        DateTime createdOn = null;
        
        SignUp participant = new SignUp();
        participant.setFirstName("FirstName");
        participant.setLastName("LastName");
        participant.setPassword("P@ssword1!");
        participant.setEmail(email);
        participant.setExternalId("externalID");
        participant.setSharingScope(SharingScope.ALL_QUALIFIED_RESEARCHERS);
        participant.setNotifyByEmail(true);
        participant.setDataGroups(dataGroups);
        participant.setLanguages(languages);
        participant.setStatus(AccountStatus.DISABLED); // should be ignored
        participant.setAttributes(attributes);
        
        ParticipantsApi participantsApi = researcher.getClient(ParticipantsApi.class);
        IdentifierHolder idHolder = participantsApi.createParticipant(participant).execute().body();
        
        String id = idHolder.getIdentifier();
        try {
            // It has been persisted. Right now we don't get the ID back so we have to conduct a 
            // search by email to get this user.
            // Can be found through paged results
            AccountSummaryList search = participantsApi.getParticipants(0, 10, email, null, null).execute().body();
            assertEquals((Integer)1, search.getTotal());
            AccountSummary summary = search.getItems().get(0);
            assertEquals("FirstName", summary.getFirstName());
            assertEquals("LastName", summary.getLastName());
            assertEquals(email, summary.getEmail());
            
            // Can also get by the ID
            StudyParticipant retrieved = participantsApi.getParticipant(id).execute().body();
            assertEquals("FirstName", retrieved.getFirstName());
            assertEquals("LastName", retrieved.getLastName());
            assertEquals(email, retrieved.getEmail());
            assertEquals("externalID", retrieved.getExternalId());
            assertEquals(SharingScope.ALL_QUALIFIED_RESEARCHERS, retrieved.getSharingScope());
            assertTrue(retrieved.getNotifyByEmail());
            assertListsEqualIgnoringOrder(dataGroups, retrieved.getDataGroups());
            assertListsEqualIgnoringOrder(languages, retrieved.getLanguages());
            assertEquals(attributes.get("phone"), retrieved.getAttributes().get("phone"));
            assertEquals(AccountStatus.UNVERIFIED, retrieved.getStatus());
            assertNotNull(retrieved.getCreatedOn());
            assertNotNull(retrieved.getId());
            createdOn = retrieved.getCreatedOn();
            
            // Update the user. Identified by the email address
            Map<String,String> newAttributes = new ImmutableMap.Builder<String,String>().put("phone","206-555-1212").build();
            List<String> newLanguages = Lists.newArrayList("de","sw");
            List<String> newDataGroups = Lists.newArrayList("group1");
            
            StudyParticipant newParticipant = new StudyParticipant();
            newParticipant.setFirstName("FirstName2");
            newParticipant.setLastName("LastName2");
            newParticipant.setEmail(email);
            newParticipant.setExternalId("externalID2");
            newParticipant.setSharingScope(SharingScope.NO_SHARING);
            newParticipant.setNotifyByEmail(false);
            newParticipant.setDataGroups(newDataGroups);
            newParticipant.setLanguages(newLanguages);
            newParticipant.setAttributes(newAttributes);
            newParticipant.setStatus(AccountStatus.ENABLED);
            
            participantsApi.updateParticipant(id, newParticipant).execute();
            
            // We think there are issues with customData consistency. For the moment, pause.
            Thread.sleep(300);
            
            // Get it again, verify it has been updated
            retrieved = participantsApi.getParticipant(id).execute().body();
            assertEquals("FirstName2", retrieved.getFirstName());
            assertEquals("LastName2", retrieved.getLastName());
            assertEquals(email, retrieved.getEmail());
            assertEquals("externalID2", retrieved.getExternalId());
            assertEquals(SharingScope.NO_SHARING, retrieved.getSharingScope());
            assertFalse(retrieved.getNotifyByEmail());
            assertListsEqualIgnoringOrder(newDataGroups, retrieved.getDataGroups());
            assertListsEqualIgnoringOrder(newLanguages, retrieved.getLanguages());
            assertEquals(id, retrieved.getId());
            assertEquals(newAttributes.get("phone"), retrieved.getAttributes().get("phone"));
            assertEquals(AccountStatus.ENABLED, retrieved.getStatus()); // user was enabled
            assertEquals(createdOn, retrieved.getCreatedOn()); // hasn't been changed, still exists
        } finally {
            if (id != null) {
                admin.getClient(ForAdminsApi.class).deleteUser(id).execute();
            }
        }
    }
    
    @Test
    public void canSendRequestResetPasswordEmail() throws Exception {
        ParticipantsApi participantsApi = researcher.getClient(ParticipantsApi.class);
        
        // This is sending an email, which is difficult to verify, but this at least should not throw an error.
        participantsApi.sendParticipantResetPasswordEmail(researcher.getSession().getId()).execute();
    }
    
    @Test
    public void canResendEmailVerification() throws Exception {
        ParticipantsApi participantsApi = researcher.getClient(ParticipantsApi.class);
        
        // This is sending an email, which is difficult to verify, but this at least should not throw an error.
        participantsApi.sendParticipantEmailVerification(researcher.getSession().getId()).execute();
    }
    
    @Test
    public void canResendConsentAgreement() throws Exception {
        String userId =  researcher.getSession().getId();

        ConsentStatus status = researcher.getSession().getConsentStatuses().values().iterator().next();
        ParticipantsApi participantsApi = researcher.getClient(ParticipantsApi.class);
        
        participantsApi.resendParticipantConsentAgreement(userId, status.getSubpopulationGuid()).execute();
    }
    
    @Test
    public void canWithdrawUserFromStudy() throws Exception {
        TestUser user = TestUserHelper.createAndSignInUser(ParticipantsTest.class, true);
        String userId = user.getSession().getId();
        try {
            // Can get activities without an error... user is indeed consented.
            ForConsentedUsersApi usersApi = user.getClient(ForConsentedUsersApi.class);
            
            usersApi.getScheduledActivities("+07:00", 1, null).execute();
            RestUtils.isUserConsented(user.getSession());
            user.signOut();

            Withdrawal withdrawal = new Withdrawal();
            withdrawal.setReason("Testing withdrawal API.");
            
            ParticipantsApi participantsApi = researcher.getClient(ParticipantsApi.class);
            participantsApi.withdrawParticipantFromStudy(userId, withdrawal).execute();
            
            user.signInAgain();
            fail("Should have thrown consent exception");
        } catch(ConsentRequiredException e) {
            RestUtils.isUserConsented(e.getSession());
        } finally {
            user.signOutAndDeleteUser();
        }
    }
    
    @Test
    public void getActivityHistory() throws Exception {
        // Make the user a developer so with one account, we can generate some tasks
        TestUser user = TestUserHelper.createAndSignInUser(ParticipantsTest.class, true, Role.DEVELOPER);
        ForConsentedUsersApi usersApi = user.getClient(ForConsentedUsersApi.class);
        
        SchedulesApi schedulePlanApi = user.getClient(SchedulesApi.class);
        SchedulePlan plan = Tests.getDailyRepeatingSchedulePlan();
        
        GuidVersionHolder planKeys = schedulePlanApi.createSchedulePlan(plan).execute().body();
        try {
            String userId = user.getSession().getId();

            // Now ask for something, so activities are generated
            ScheduledActivityList activities = usersApi.getScheduledActivities("+00:00", 4, null).execute().body();
            
            // Verify there is more than one activity
            int count = activities.getItems().size();
            assertTrue(count > 1);

            // Finish one of them so there is one less in the user's API
            ScheduledActivity finishMe = activities.getItems().get(0);
            finishMe.setStartedOn(DateTime.now());
            finishMe.setFinishedOn(DateTime.now());
            usersApi.updateScheduledActivities(activities.getItems()).execute();
            
            // Finished task is now no longer in the list the user sees
            activities = usersApi.getScheduledActivities("+00:00", 4, null).execute().body();
            assertTrue(activities.getItems().size() < count);
            
            // But the researcher will still see the full list
            ParticipantsApi participantsApi = researcher.getClient(ParticipantsApi.class);
            ActivityList resActivities = participantsApi.getParticipantActivities(userId, null, null).execute().body();
            assertEquals(count, resActivities.getItems().size());
            
            // Researcher can delete all the activities as well.
            participantsApi.deleteParticipantActivities(userId).execute();
            
            resActivities = participantsApi.getParticipantActivities(userId, null, null).execute().body();
            assertEquals(0, resActivities.getItems().size());
        } finally {
            if (user != null) {
                schedulePlanApi.deleteSchedulePlan(planKeys.getGuid()).execute();
                user.signOutAndDeleteUser();
            }
        }
    }

    @Test
    public void getParticipantUploads() throws Exception {
        TestUser user = TestUserHelper.createAndSignInUser(ParticipantsTest.class, true);
        String userId = user.getSession().getId();
        try {
            // Create a REQUESTED record that we can retrieve through the reporting API.
            UploadRequest request = new UploadRequest();
            request.setContentType("application/zip");
            request.setContentLength(100L);
            request.setContentMd5("ABC");
            request.setName("upload.zip");
            
            ForConsentedUsersApi usersApi = user.getClient(ForConsentedUsersApi.class);
            UploadSession uploadSession = usersApi.requestUploadSession(request).execute().body();
            
            // This does depend on a GSI, so pause for a bit.
            Thread.sleep(500);
            
            ParticipantsApi participantsApi = researcher.getClient(ParticipantsApi.class);
            
            // Jenkins has gotten minutes off from the current time, causing this query to fail. Adjust the range
            // to ensure if the clock drifts, within reason, the query will still succeed.
            DateTime endTime = DateTime.now(DateTimeZone.UTC).plusHours(2);
            DateTime startTime = endTime.minusDays(1).minusHours(21);

            UploadList results = participantsApi.getParticipantUploads(userId, startTime, endTime).execute().body();
            
            String uploadId = results.getItems().get(0).getUploadId();

            assertEquals(uploadSession.getId(), uploadId);
            assertTrue(results.getItems().get(0).getContentLength() > 0);
            assertEquals(startTime, results.getStartTime());
            assertEquals(endTime, results.getEndTime());
        } finally {
            if (user != null) {
                user.signOutAndDeleteUser();
            }
        }
    }
}
