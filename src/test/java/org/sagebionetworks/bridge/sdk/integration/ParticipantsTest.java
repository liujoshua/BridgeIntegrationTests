package org.sagebionetworks.bridge.sdk.integration;

import static java.lang.Boolean.TRUE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.sagebionetworks.bridge.rest.model.AccountStatus.DISABLED;
import static org.sagebionetworks.bridge.rest.model.AccountStatus.ENABLED;
import static org.sagebionetworks.bridge.rest.model.AccountStatus.UNVERIFIED;
import static org.sagebionetworks.bridge.rest.model.Role.RESEARCHER;
import static org.sagebionetworks.bridge.rest.model.SharingScope.ALL_QUALIFIED_RESEARCHERS;
import static org.sagebionetworks.bridge.rest.model.SharingScope.NO_SHARING;
import static org.sagebionetworks.bridge.sdk.integration.Tests.SUBSTUDY_ID_1;
import static org.sagebionetworks.bridge.sdk.integration.Tests.assertListsEqualIgnoringOrder;
import static org.sagebionetworks.bridge.util.IntegTestUtils.PHONE;

import org.apache.commons.lang3.RandomStringUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import retrofit2.Response;

import org.sagebionetworks.bridge.rest.RestUtils;
import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.rest.api.ParticipantsApi;
import org.sagebionetworks.bridge.rest.api.SchedulesApi;
import org.sagebionetworks.bridge.rest.exceptions.ConsentRequiredException;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.exceptions.InvalidEntityException;
import org.sagebionetworks.bridge.rest.model.AccountSummary;
import org.sagebionetworks.bridge.rest.model.AccountSummaryList;
import org.sagebionetworks.bridge.rest.model.Activity;
import org.sagebionetworks.bridge.rest.model.ConsentStatus;
import org.sagebionetworks.bridge.rest.model.ExternalIdentifier;
import org.sagebionetworks.bridge.rest.model.ForwardCursorScheduledActivityList;
import org.sagebionetworks.bridge.rest.model.GuidVersionHolder;
import org.sagebionetworks.bridge.rest.model.IdentifierHolder;
import org.sagebionetworks.bridge.rest.model.IdentifierUpdate;
import org.sagebionetworks.bridge.rest.model.Message;
import org.sagebionetworks.bridge.rest.model.Phone;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.SchedulePlan;
import org.sagebionetworks.bridge.rest.model.ScheduledActivity;
import org.sagebionetworks.bridge.rest.model.ScheduledActivityList;
import org.sagebionetworks.bridge.rest.model.SignIn;
import org.sagebionetworks.bridge.rest.model.SignUp;
import org.sagebionetworks.bridge.rest.model.SimpleScheduleStrategy;
import org.sagebionetworks.bridge.rest.model.Study;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.rest.model.UploadList;
import org.sagebionetworks.bridge.rest.model.UploadRequest;
import org.sagebionetworks.bridge.rest.model.UploadSession;
import org.sagebionetworks.bridge.rest.model.UserSessionInfo;
import org.sagebionetworks.bridge.rest.model.VersionHolder;
import org.sagebionetworks.bridge.rest.model.Withdrawal;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.util.IntegTestUtils;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ParticipantsTest {
    private TestUser admin;
    private TestUser developer;
    private TestUser researcher;
    private TestUser phoneUser;
    private TestUser emailUser;
    private ExternalIdentifier externalId;
    
    @Before
    public void before() throws Exception {
        admin = TestUserHelper.getSignedInAdmin();
        developer = new TestUserHelper.Builder(ParticipantsTest.class).withRoles(Role.DEVELOPER).withConsentUser(true)
                .createAndSignInUser();
        researcher = new TestUserHelper.Builder(ParticipantsTest.class).withRoles(RESEARCHER).withConsentUser(true)
                .createAndSignInUser();
        
        externalId = Tests.createExternalId(ParticipantsTest.class, developer, SUBSTUDY_ID_1);
        
        IntegTestUtils.deletePhoneUser(researcher);
        
        ForAdminsApi adminApi = admin.getClient(ForAdminsApi.class);
        Study study = adminApi.getUsersStudy().execute().body();
        if (!study.isPhoneSignInEnabled() || !study.isEmailSignInEnabled()) {
            study.setPhoneSignInEnabled(true);
            study.setEmailSignInEnabled(true);
            adminApi.updateStudy(study.getIdentifier(), study).execute();
        }
    }
    
    @After
    public void after() throws Exception {
        Tests.deleteExternalId(externalId);
        if (developer != null) {
            developer.signOutAndDeleteUser();
        }
        if (researcher != null) {
            researcher.signOutAndDeleteUser();
        }
        if (phoneUser != null) {
            phoneUser.signOutAndDeleteUser();
        }
        if (emailUser != null) {
            emailUser.signOutAndDeleteUser();
        }
    }

    // Note: A very similar test exists in UserParticipantTest
    @SuppressWarnings("unchecked")
    @Test
    public void canGetAndUpdateSelf() throws Exception {
        TestUser user = TestUserHelper.createAndSignInUser(ParticipantsTest.class, true);
        try {
            ForConsentedUsersApi userApi = user.getClient(ForConsentedUsersApi.class);

            StudyParticipant self = userApi.getUsersParticipantRecord(false).execute().body();
            assertEquals(user.getEmail(), self.getEmail());

            // Update and verify changes. Right now there's not a lot that can be changed
            List<String> languages = ImmutableList.of("nl", "fr", "en");

            self.setLanguages(languages);
            self.setDataGroups(ImmutableList.of("group1"));
            self.setSharingScope(ALL_QUALIFIED_RESEARCHERS);
            self.setNotifyByEmail(null); // BRIDGE-1604: should use default value: true
            
            List<String> clientData = new ArrayList<>();
            clientData.add("A");
            clientData.add("B");
            clientData.add("C");
            self.setClientData(clientData);

            userApi.updateUsersParticipantRecord(self).execute();
            
            // Session should reflect these updates, right now.
            List<String> capturedDataGroups = user.getSession().getDataGroups();
            assertTrue(capturedDataGroups.contains("group1"));
            List<String> capturedData = RestUtils.toType(user.getSession().getClientData(), List.class);
            assertEquals("A", capturedData.get(0));
            assertEquals("B", capturedData.get(1));
            assertEquals("C", capturedData.get(2));
            
            self = userApi.getUsersParticipantRecord(false).execute().body();
            assertEquals(ALL_QUALIFIED_RESEARCHERS, self.getSharingScope());
            assertEquals(ImmutableList.of("group1"), self.getDataGroups());
            assertTrue(self.isNotifyByEmail());  // BRIDGE-1604: true value returned
            
            List<String> deserClientData = (List<String>)RestUtils.toType(self.getClientData(), List.class);
            assertEquals("A", deserClientData.get(0));
            assertEquals("B", deserClientData.get(1));
            assertEquals("C", deserClientData.get(2));
            
            assertEquals(ImmutableList.of("nl", "fr", "en"), self.getLanguages());
        } finally {
            user.signOutAndDeleteUser();
        }
    }
    
    @Test
    public void retrieveParticipant() throws Exception {
        TestUser user = new TestUserHelper.Builder(ParticipantsTest.class)
                .withExternalId(externalId.getIdentifier()).createAndSignInUser();
        
        ParticipantsApi researcherParticipantsApi = researcher.getClient(ParticipantsApi.class);
        ForAdminsApi adminApi = admin.getClient(ForAdminsApi.class);
        Study study = adminApi.getStudy(admin.getStudyId()).execute().body();
        
        try {
            study.setHealthCodeExportEnabled(true);
            VersionHolder version = adminApi.updateStudy(study.getIdentifier(), study).execute().body();
            study.version(version.getVersion());
            
            StudyParticipant participant = researcherParticipantsApi.getParticipantById(
                    user.getSession().getId(), true).execute().body();
            // Verify that what we know about this test user exists in the record
            assertEquals(user.getEmail(), participant.getEmail());
            assertEquals(user.getSession().getId(), participant.getId());
            assertFalse(participant.getConsentHistories().isEmpty());
            
            StudyParticipant participant2 = researcherParticipantsApi.getParticipantByHealthCode(
                    participant.getHealthCode(), false).execute().body();
            assertEquals(user.getEmail(), participant2.getEmail());
            assertEquals(user.getSession().getId(), participant2.getId());
            assertTrue(participant2.getConsentHistories().isEmpty());
            
            String externalId = Iterables.getFirst(participant.getExternalIds().values(), null);
            // Get this participant using an external ID
            StudyParticipant participant3 = researcherParticipantsApi.getParticipantByExternalId(
                    externalId, false).execute().body();
            assertEquals(user.getEmail(), participant3.getEmail());
            assertEquals(user.getSession().getId(), participant3.getId());
            assertTrue(participant2.getConsentHistories().isEmpty());
        } finally {
            user.signOutAndDeleteUser();
            study.setHealthCodeExportEnabled(false);
            VersionHolder version = adminApi.updateStudy(study.getIdentifier(), study).execute().body();
            study.version(version.getVersion());
        }
    }
    
    @SuppressWarnings("deprecation")
    @Test
    public void canRetrieveAndPageThroughParticipants() throws Exception {
        ParticipantsApi participantsApi = researcher.getClient(ParticipantsApi.class);

        AccountSummaryList summaries = participantsApi.getParticipants(0, 10, null, null, null, null).execute().body();

        int total = summaries.getTotal();
        Collections.sort(summaries.getItems(), Comparator.comparing(AccountSummary::getCreatedOn));
        DateTime oldest = summaries.getItems().get(0).getCreatedOn();
        DateTime newest = summaries.getItems().get(summaries.getItems().size()-1).getCreatedOn();
        
        // Well we know there's at least the bootstrap admin account
        assertEquals(0, summaries.getRequestParams().getOffsetBy().intValue());
        assertEquals(10, summaries.getRequestParams().getPageSize().intValue());
        assertTrue(summaries.getItems().size() <= summaries.getTotal());
        assertTrue(summaries.getItems().size() > 1);
        
        AccountSummary summary = summaries.getItems().get(0);
        assertNotNull(summary.getCreatedOn());
        assertNotNull(summary.getStatus());
        assertNotNull(summary.getId());
        
        // Filter to only the researcher
        summaries = participantsApi.getParticipants(0, 10, researcher.getEmail(), null, null, null).execute().body();
        assertEquals(1, summaries.getItems().size());
        assertEquals(researcher.getEmail(), summaries.getItems().get(0).getEmail());
        assertEquals(researcher.getEmail(), summaries.getRequestParams().getEmailFilter());

        // Date filter that would not include the newest participant
        summaries = participantsApi.getParticipants(0, 10, null, null, null, newest.minusMillis(1)).execute().body();
        assertTrue(summaries.getTotal() < total);
        assertNull(summaries.getRequestParams().getStartTime());
        assertEquals(summaries.getRequestParams().getEndTime(), newest.minusMillis(1));
        doesNotIncludeThisAccountCreatedOn(summaries, newest);
        
        // Date filter that would not include the oldest participant
        summaries = participantsApi.getParticipants(0, 10, null, null, oldest.plusMillis(1), null).execute().body();
        assertTrue(summaries.getTotal() < total);
        assertEquals(summaries.getRequestParams().getStartTime(), oldest.plusMillis(1));
        assertNull(summaries.getRequestParams().getEndTime());
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
    
    @SuppressWarnings("deprecation")
    @Test(expected = InvalidEntityException.class)
    public void cannotSetBadOffset() throws Exception {
        ParticipantsApi participantsApi = researcher.getClient(ParticipantsApi.class);
        
        participantsApi.getParticipants(-1, 10, null, null, null, null).execute();
    }
    
    @SuppressWarnings("deprecation")
    @Test(expected = InvalidEntityException.class)
    public void cannotSetBadPageSize() throws Exception {
        ParticipantsApi participantsApi = researcher.getClient(ParticipantsApi.class);
        
        participantsApi.getParticipants(0, 4, null, null, null, null).execute();
    }
    
    @SuppressWarnings("deprecation")
    @Test
    public void crudParticipant() throws Exception {
        String email = IntegTestUtils.makeEmail(ParticipantsTest.class);
        Map<String,String> attributes = new ImmutableMap.Builder<String,String>().put("can_be_recontacted","true").build();
        List<String> languages = Lists.newArrayList("en","fr");
        List<String> dataGroups = Lists.newArrayList("sdk-int-1", "sdk-int-2");
        DateTime createdOn;
        
        SignUp participant = new SignUp();
        participant.setFirstName("FirstName");
        participant.setLastName("LastName");
        participant.setPassword("P@ssword1!");
        participant.setEmail(email);
        participant.setPhone(PHONE);
        participant.setExternalId(externalId.getIdentifier());
        participant.setSharingScope(ALL_QUALIFIED_RESEARCHERS);
        // BRIDGE-1604: leave notifyByEmail to its default value (should be true)
        participant.setDataGroups(dataGroups);
        participant.setLanguages(languages);
        participant.setStatus(DISABLED); // should be ignored
        participant.setAttributes(attributes);
        
        ParticipantsApi participantsApi = researcher.getClient(ParticipantsApi.class);
        IdentifierHolder idHolder = participantsApi.createParticipant(participant).execute().body();
        
        String id = idHolder.getIdentifier();
        try {
            // It has been persisted. Right now we don't get the ID back so we have to conduct a 
            // search by email to get this user.
            // Can be found through paged results
            AccountSummaryList search = participantsApi.getParticipants(0, 10, email, null, null, null).execute().body();
            assertEquals((Integer)1, search.getTotal());
            AccountSummary summary = search.getItems().get(0);
            assertEquals("FirstName", summary.getFirstName());
            assertEquals("LastName", summary.getLastName());
            assertEquals(email, summary.getEmail());
            
            // Can also get by the ID
            StudyParticipant retrieved = participantsApi.getParticipantById(id, true).execute().body();
            assertEquals("FirstName", retrieved.getFirstName());
            assertEquals("LastName", retrieved.getLastName());
            assertEquals(email, retrieved.getEmail());
            assertTrue(retrieved.getExternalIds().values().contains(externalId.getIdentifier()));
            assertEquals(ALL_QUALIFIED_RESEARCHERS, retrieved.getSharingScope());
            assertTrue(retrieved.isNotifyByEmail());
            assertListsEqualIgnoringOrder(dataGroups, retrieved.getDataGroups());
            assertListsEqualIgnoringOrder(languages, retrieved.getLanguages());
            assertEquals(attributes.get("can_be_recontacted"), retrieved.getAttributes().get("can_be_recontacted"));
            assertEquals(UNVERIFIED, retrieved.getStatus());
            assertNotNull(retrieved.getCreatedOn());
            assertNotNull(retrieved.getId());
            Phone retrievedPhone = retrieved.getPhone();
            assertEquals(PHONE.getNumber(), retrievedPhone.getNumber());
            // Tests.PHONE.getNationalFormat() isn't formatted on the client, so we have to hard-code it.
            assertEquals("(971) 248-6796", retrievedPhone.getNationalFormat());
            assertEquals("US", retrievedPhone.getRegionCode());
            assertFalse(retrieved.isEmailVerified());
            assertFalse(retrieved.isPhoneVerified());
            createdOn = retrieved.getCreatedOn();
            
            // Update the user. Identified by the email address
            Map<String,String> newAttributes = new ImmutableMap.Builder<String,String>().put("can_be_recontacted","206-555-1212").build();
            List<String> newLanguages = Lists.newArrayList("de","sw");
            List<String> newDataGroups = Lists.newArrayList("group1");
            
            StudyParticipant newParticipant = new StudyParticipant();
            newParticipant.setFirstName("FirstName2");
            newParticipant.setLastName("LastName2");
            newParticipant.setEmail(email);
            newParticipant.setSharingScope(NO_SHARING);
            newParticipant.setNotifyByEmail(null);
            newParticipant.setDataGroups(newDataGroups);
            newParticipant.setLanguages(newLanguages);
            newParticipant.setAttributes(newAttributes);
            newParticipant.setStatus(ENABLED);
            // This should not work.
            newParticipant.setEmailVerified(TRUE);
            newParticipant.setPhoneVerified(TRUE);
            Phone newPhone = new Phone().number("4152588569").regionCode("CA");
            newParticipant.setPhone(newPhone);
            
            participantsApi.updateParticipant(id, newParticipant).execute();
            
            // We think there are issues with customData consistency. For the moment, pause.
            Thread.sleep(300);
            
            // Get it again, verify it has been updated
            retrieved = participantsApi.getParticipantById(id, true).execute().body();
            assertEquals("FirstName2", retrieved.getFirstName());
            assertEquals("LastName2", retrieved.getLastName());
            assertEquals(email, retrieved.getEmail()); // This cannot be updated
            retrievedPhone = retrieved.getPhone();
            assertEquals(PHONE.getNumber(), retrievedPhone.getNumber()); // This cannot be updated
            assertEquals("US", retrievedPhone.getRegionCode());
            assertEquals("(971) 248-6796", retrievedPhone.getNationalFormat());
            assertFalse(retrieved.isEmailVerified());
            assertFalse(retrieved.isPhoneVerified());
            assertEquals(NO_SHARING, retrieved.getSharingScope());
            // BRIDGE-1604: should still be true, even though it was not sent to the server. Through participants API 
            assertTrue(retrieved.isNotifyByEmail());
            assertListsEqualIgnoringOrder(newDataGroups, retrieved.getDataGroups());
            assertListsEqualIgnoringOrder(newLanguages, retrieved.getLanguages());
            assertEquals(id, retrieved.getId());
            assertEquals(newAttributes.get("can_be_recontacted"), retrieved.getAttributes().get("can_be_recontacted"));
            assertEquals(UNVERIFIED, retrieved.getStatus()); // researchers cannot enable users
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
        Response<Message> response = participantsApi.sendParticipantResetPassword(researcher.getSession().getId()).execute();
        assertEquals(200, response.code());
    }
    
    @Test
    public void canResendEmailVerification() throws Exception {
        ParticipantsApi participantsApi = researcher.getClient(ParticipantsApi.class);
        
        // This is sending an email, which is difficult to verify, but this at least should not throw an error.
        Response<Message> response = participantsApi.sendParticipantEmailVerification(researcher.getSession().getId()).execute();
        assertEquals(200, response.code());
    }
    
    @Test
    public void canResendPhoneVerification() throws Exception {
        ParticipantsApi participantsApi = researcher.getClient(ParticipantsApi.class);
        
        // This is sending an email, which is difficult to verify, but this at least should not throw an error.
        Response<Message> response = participantsApi.sendParticipantPhoneVerification(researcher.getSession().getId()).execute();
        assertEquals(200, response.code());
    }

    @Test
    public void canResendConsentAgreement() throws Exception {
        String userId =  researcher.getSession().getId();

        ConsentStatus status = researcher.getSession().getConsentStatuses().values().iterator().next();
        ParticipantsApi participantsApi = researcher.getClient(ParticipantsApi.class);
        
        Response<Message> response = participantsApi
                .resendParticipantConsentAgreement(userId, status.getSubpopulationGuid()).execute();
        assertEquals(200, response.code());
    }
    
    @Test
    public void canWithdrawUserFromStudy() throws Exception {
        TestUser user = TestUserHelper.createAndSignInUser(ParticipantsTest.class, true);
        String userId = user.getSession().getId();
        try {
            // Can get activities without an error... user is indeed consented.
            ForConsentedUsersApi usersApi = user.getClient(ForConsentedUsersApi.class);
            
            usersApi.getScheduledActivities("+07:00", 1, null).execute();
            assertTrue(RestUtils.isUserConsented(user.getSession()));
            user.signOut();

            Withdrawal withdrawal = new Withdrawal();
            withdrawal.setReason("Testing withdrawal API.");
            
            ParticipantsApi participantsApi = researcher.getClient(ParticipantsApi.class);
            participantsApi.withdrawParticipantFromStudy(userId, withdrawal).execute();
            
            user.signInAgain();
            fail("Should have thrown exception");
        } catch(EntityNotFoundException e) {
            // expected
        } finally {
            user.signOutAndDeleteUser();
        }
    }
    
    @Test
    public void canWithdrawUserFromSubpopulation() throws Exception {
        TestUser user = TestUserHelper.createAndSignInUser(ParticipantsTest.class, true);
        String userId = user.getSession().getId();
        String subpopGuid = user.getSession().getConsentStatuses().entrySet().iterator().next().getValue()
                .getSubpopulationGuid();
        try {
            // Can get activities without an error... user is indeed consented.
            ForConsentedUsersApi usersApi = user.getClient(ForConsentedUsersApi.class);
            
            usersApi.getScheduledActivities("+07:00", 1, null).execute();
            assertTrue(RestUtils.isUserConsented(user.getSession()));
            user.signOut();

            Withdrawal withdrawal = new Withdrawal();
            withdrawal.setReason("Testing withdrawal API.");
            
            ParticipantsApi participantsApi = researcher.getClient(ParticipantsApi.class);
            participantsApi.withdrawParticipantFromSubpopulation(userId, subpopGuid, withdrawal).execute();
            
            user.signInAgain();
            fail("Should have thrown consent exception");
        } catch(ConsentRequiredException e) {
            assertFalse(RestUtils.isUserConsented(e.getSession()));
            
            ParticipantsApi userApi = user.getClient(ParticipantsApi.class);
            
            StudyParticipant participant = userApi.getUsersParticipantRecord(true).execute().body();
            assertEquals(NO_SHARING, participant.getSharingScope());
            
            // Should not be able to do this.
            participant.sharingScope(ALL_QUALIFIED_RESEARCHERS);
            UserSessionInfo updatedSession = userApi.updateUsersParticipantRecord(participant).execute().body();
            assertEquals(NO_SHARING, updatedSession.getSharingScope());
            
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

        // Set an identifiable label on the activity so we can find the generated activities later.
        String activityLabel = "activity-" + RandomStringUtils.randomAlphabetic(4);
        ((SimpleScheduleStrategy) plan.getStrategy()).getSchedule().getActivities().get(0).setLabel(activityLabel);

        GuidVersionHolder planKeys = schedulePlanApi.createSchedulePlan(plan).execute().body();
        try {
            String userId = user.getSession().getId();

            // Now ask for something, so activities are generated
            ScheduledActivityList activities = usersApi.getScheduledActivities("+00:00", 4, null).execute().body();
            
            // Verify there is more than one activity
            List<ScheduledActivity> activityList = findActivitiesByLabel(activities.getItems(), activityLabel);
            int count = activityList.size();
            assertTrue(count > 1);

            // Finish one of them so there is one less in the user's API
            ScheduledActivity finishMe = activityList.get(0);
            finishMe.setStartedOn(DateTime.now());
            finishMe.setFinishedOn(DateTime.now());
            usersApi.updateScheduledActivities(activityList).execute();
            
            // Finished task is now no longer in the list the user sees
            activities = usersApi.getScheduledActivities("+00:00", 4, null).execute().body();
            List<ScheduledActivity> activityList2 = findActivitiesByLabel(activities.getItems(), activityLabel);
            assertTrue(activityList2.size() < count);
            
            plan = schedulePlanApi.getSchedulePlan(planKeys.getGuid()).execute().body();
            String activityGuid = ((SimpleScheduleStrategy)plan.getStrategy()).getSchedule().getActivities().get(0).getGuid();
            
            // But the researcher will still see the full list
            ParticipantsApi participantsApi = researcher.getClient(ParticipantsApi.class);
            
            ForwardCursorScheduledActivityList resActivities = participantsApi
                    .getParticipantActivityHistory(userId, activityGuid, null, null, null, 50).execute().body();
            List<ScheduledActivity> activityHistoryList = findActivitiesByLabel(resActivities.getItems(),
                    activityLabel);
            assertEquals(count, activityHistoryList.size());
            
            // Researcher can delete all the activities as well.
            participantsApi.deleteParticipantActivities(userId).execute();
            
            resActivities = participantsApi
                    .getParticipantActivityHistory(userId, activityGuid, null, null, null, 50).execute().body();
            assertEquals(0, resActivities.getItems().size());
        } finally {
            admin.getClient(SchedulesApi.class).deleteSchedulePlan(planKeys.getGuid(), true).execute();
            user.signOutAndDeleteUser();
        }
    }
    
    @Test
    public void getActivityHistoryV4() throws Exception {
        TestUser user = TestUserHelper.createAndSignInUser(ParticipantsTest.class, true, Role.DEVELOPER);
        ForConsentedUsersApi usersApi = user.getClient(ForConsentedUsersApi.class);
        
        SchedulesApi schedulePlanApi = user.getClient(SchedulesApi.class);
        SchedulePlan plan = Tests.getDailyRepeatingSchedulePlan();

        // Set an identifiable label on the activity so we can find the generated activities later.
        String activityLabel = "activity-" + RandomStringUtils.randomAlphabetic(4);
        
        Activity oneActivity = ((SimpleScheduleStrategy) plan.getStrategy()).getSchedule().getActivities().get(0);
        
        String taskReferentGuid = oneActivity.getTask().getIdentifier();
        oneActivity.setLabel(activityLabel);
        
        
        GuidVersionHolder planKeys = schedulePlanApi.createSchedulePlan(plan).execute().body();
        try {
            String userId = user.getSession().getId();
            DateTime startsOn = DateTime.now();
            DateTime endsOn = DateTime.now().plusDays(2);
            
            usersApi.getScheduledActivities("+00:00", 4, null).execute().body();
            ParticipantsApi api = researcher.getClient(ParticipantsApi.class);

            // getTaskHistory() uses a secondary global index. Sleep for 2 seconds to help make sure the index is consistent.
            Thread.sleep(2000);

            ForwardCursorScheduledActivityList list = api
                    .getParticipantTaskHistory(userId, taskReferentGuid, startsOn, endsOn, null, 100).execute().body();

            // There should be activities...
            assertFalse(list.getItems().isEmpty());
            
            // These other calls return nothing though
            list = api.getParticipantSurveyHistory(userId, taskReferentGuid, startsOn, endsOn, null, 100).execute()
                    .body();
            assertTrue(list.getItems().isEmpty());
            
            list = api.getParticipantCompoundActivityHistory(userId, taskReferentGuid, startsOn, endsOn, null, 100)
                    .execute().body();
            assertTrue(list.getItems().isEmpty());
        } finally {
            admin.getClient(SchedulesApi.class).deleteSchedulePlan(planKeys.getGuid(), true).execute();
            user.signOutAndDeleteUser();
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

            UploadList results = participantsApi.getParticipantUploads(userId, startTime, endTime, null, null).execute().body();
            
            String uploadId = results.getItems().get(0).getUploadId();

            assertEquals(uploadSession.getId(), uploadId);
            assertTrue(results.getItems().get(0).getContentLength() > 0);
            assertEquals(startTime, results.getRequestParams().getStartTime());
            assertEquals(endTime, results.getRequestParams().getEndTime());
        } finally {
            user.signOutAndDeleteUser();
        }
    }
    
    @SuppressWarnings("deprecation")
    @Test
    public void crudUsersWithPhone() throws Exception {
        SignUp signUp = new SignUp().phone(IntegTestUtils.PHONE).password("P@ssword`1");
        phoneUser = TestUserHelper.createAndSignInUser(ParticipantsTest.class, true, signUp);
        
        ParticipantsApi participantsApi = researcher.getClient(ParticipantsApi.class);
        
        AccountSummaryList list = participantsApi.getParticipants(0, 5, null, "248-6796", null, null).execute().body();
        assertEquals(1, list.getItems().size());
        assertEquals(phoneUser.getPhone().getNumber(), list.getItems().get(0).getPhone().getNumber());
        
        String userId = list.getItems().get(0).getId();
        StudyParticipant participant = participantsApi.getParticipantById(userId, true).execute().body();
        
        assertEquals(phoneUser.getPhone().getNumber(), participant.getPhone().getNumber());
        assertEquals(userId, participant.getId());
    }
    
    @Test
    public void addEmailToPhoneUser() throws Exception {
        SignUp signUp = new SignUp().phone(IntegTestUtils.PHONE).password("P@ssword`1").study(IntegTestUtils.STUDY_ID);
        phoneUser = TestUserHelper.createAndSignInUser(ParticipantsTest.class, true, signUp);
        
        SignIn signIn = new SignIn().phone(signUp.getPhone()).password(signUp.getPassword()).study(IntegTestUtils.STUDY_ID);

        String email = IntegTestUtils.makeEmail(ParticipantsTest.class);
        IdentifierUpdate identifierUpdate = new IdentifierUpdate().signIn(signIn).emailUpdate(email);
        
        ForConsentedUsersApi usersApi = phoneUser.getClient(ForConsentedUsersApi.class);
        UserSessionInfo info = usersApi.updateUsersIdentifiers(identifierUpdate).execute().body();
        assertEquals(email, info.getEmail());
        
        ParticipantsApi participantsApi = researcher.getClient(ParticipantsApi.class);
        StudyParticipant retrieved = participantsApi.getParticipantById(phoneUser.getSession().getId(), true).execute().body();
        assertEquals(email, retrieved.getEmail());
        
        // But if you do it again, it should not work
        String newEmail = IntegTestUtils.makeEmail(ParticipantsTest.class);
        identifierUpdate = new IdentifierUpdate().signIn(signIn).emailUpdate(newEmail);
        
        info = usersApi.updateUsersIdentifiers(identifierUpdate).execute().body();
        assertEquals(email, info.getEmail()); // unchanged
    }

    @Test
    public void addPhoneToEmailUser() throws Exception {
        String email = IntegTestUtils.makeEmail(ParticipantsTest.class);
        SignUp signUp = new SignUp().email(email).password("P@ssword`1").study(IntegTestUtils.STUDY_ID);
        emailUser = TestUserHelper.createAndSignInUser(ParticipantsTest.class, true, signUp);
        
        SignIn signIn = new SignIn().email(signUp.getEmail()).password(signUp.getPassword()).study(IntegTestUtils.STUDY_ID);

        IdentifierUpdate identifierUpdate = new IdentifierUpdate().signIn(signIn).phoneUpdate(IntegTestUtils.PHONE);
        
        ForConsentedUsersApi usersApi = emailUser.getClient(ForConsentedUsersApi.class);
        UserSessionInfo info = usersApi.updateUsersIdentifiers(identifierUpdate).execute().body();
        assertEquals(IntegTestUtils.PHONE.getNumber(), info.getPhone().getNumber());
        
        ParticipantsApi participantsApi = researcher.getClient(ParticipantsApi.class);
        StudyParticipant retrieved = participantsApi.getParticipantById(emailUser.getSession().getId(), true).execute().body();
        assertEquals(IntegTestUtils.PHONE.getNumber(), retrieved.getPhone().getNumber());
        
        // But if you do it again, it should not work
        Phone otherPhone = new Phone().number("4082588569").regionCode("US");
        identifierUpdate = new IdentifierUpdate().signIn(signIn).phoneUpdate(otherPhone);
        
        info = usersApi.updateUsersIdentifiers(identifierUpdate).execute().body();
        assertEquals(IntegTestUtils.PHONE.getNumber(), info.getPhone().getNumber()); // unchanged
    }
    
    private static List<ScheduledActivity> findActivitiesByLabel(List<ScheduledActivity> scheduledActivityList,
            String label) {
        return scheduledActivityList.stream()
                .filter(oneScheduledActivity -> label.equals(oneScheduledActivity.getActivity().getLabel()))
                .collect(Collectors.toList());
    }
}
