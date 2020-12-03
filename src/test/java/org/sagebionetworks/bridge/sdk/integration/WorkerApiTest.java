package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.sagebionetworks.bridge.rest.model.Role.WORKER;
import static org.sagebionetworks.bridge.sdk.integration.Tests.STUDY_ID_1;
import static org.sagebionetworks.bridge.util.IntegTestUtils.SHARED_APP_ID;
import static org.sagebionetworks.bridge.util.IntegTestUtils.TEST_APP_ID;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableMap;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.RestUtils;
import org.sagebionetworks.bridge.rest.api.ActivitiesApi;
import org.sagebionetworks.bridge.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.rest.api.ForSuperadminsApi;
import org.sagebionetworks.bridge.rest.api.ForWorkersApi;
import org.sagebionetworks.bridge.rest.api.InternalApi;
import org.sagebionetworks.bridge.rest.api.SchedulesApi;
import org.sagebionetworks.bridge.rest.model.AccountSummary;
import org.sagebionetworks.bridge.rest.model.AccountSummaryList;
import org.sagebionetworks.bridge.rest.model.AccountSummarySearch;
import org.sagebionetworks.bridge.rest.model.ActivityEventList;
import org.sagebionetworks.bridge.rest.model.App;
import org.sagebionetworks.bridge.rest.model.ForwardCursorScheduledActivityList;
import org.sagebionetworks.bridge.rest.model.GuidVersionHolder;
import org.sagebionetworks.bridge.rest.model.HealthDataRecord;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.SchedulePlan;
import org.sagebionetworks.bridge.rest.model.SchedulePlanList;
import org.sagebionetworks.bridge.rest.model.SignIn;
import org.sagebionetworks.bridge.rest.model.SignUp;
import org.sagebionetworks.bridge.rest.model.SimpleScheduleStrategy;
import org.sagebionetworks.bridge.rest.model.SmsMessage;
import org.sagebionetworks.bridge.rest.model.SmsTemplate;
import org.sagebionetworks.bridge.rest.model.SmsType;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.util.IntegTestUtils;

@SuppressWarnings({ "ConstantConditions", "Guava", "unchecked" })
public class WorkerApiTest {
    private static final String SYNAPSE_USER_ID = "00000";
    private static final DateTimeZone TEST_USER_TIME_ZONE = DateTimeZone.forOffsetHours(-8);
    private static final String TEST_USER_TIME_ZONE_STRING = "-08:00";
    
    TestUser admin;
    TestUser worker;
    TestUser researcher;
    TestUser developer;
    TestUser phoneUser;
    TestUser user;
    ForWorkersApi workersApi;

    @Before
    public void before() throws Exception {
        admin = TestUserHelper.getSignedInAdmin();
        worker = TestUserHelper.createAndSignInUser(WorkerApiTest.class, true, Role.WORKER);
        researcher = TestUserHelper.createAndSignInUser(WorkerApiTest.class, true, Role.RESEARCHER);
        developer = TestUserHelper.createAndSignInUser(WorkerApiTest.class, true, Role.DEVELOPER);
        workersApi = worker.getClient(ForWorkersApi.class);
        
        // Turn on healthcode sharing, it is usually off 
        ForSuperadminsApi superadminApi = admin.getClient(ForSuperadminsApi.class);
        App app = superadminApi.getApp(TEST_APP_ID).execute().body();
        if (!app.isHealthCodeExportEnabled()) {
            app.setHealthCodeExportEnabled(true);
            superadminApi.updateApp(TEST_APP_ID, app).execute();
        }
    }

    @After
    public void deleteWorker() throws Exception {
        if (worker != null) {
            worker.signOutAndDeleteUser();
        }

        // Turn off healthcode sharing to clean up
        ForSuperadminsApi superadminApi = admin.getClient(ForSuperadminsApi.class);
        App app = superadminApi.getApp(TEST_APP_ID).execute().body();
        if (app.isHealthCodeExportEnabled()) {
            app.setHealthCodeExportEnabled(false);
            superadminApi.updateApp(TEST_APP_ID, app).execute();
        }
    }
    @After
    public void deletePhoneUser() throws Exception {
        if (phoneUser != null) {
            phoneUser.signOutAndDeleteUser();
        }
    }
    @After
    public void deleteResearcher() throws Exception {
        if (researcher != null) {
            researcher.signOutAndDeleteUser();
        }
    }
    @After
    public void deleteDeveloper() throws Exception {
        if (developer != null) {
            developer.signOutAndDeleteUser();
        }
    }
    @After
    public void deleteUser() throws Exception {
        if (user != null) {
            user.signOutAndDeleteUser();
        }
    }
    
    @SuppressWarnings("deprecation")
    @Test
    public void retrieveUsers() throws Exception {
        String externalId = Tests.randomIdentifier(WorkerApiTest.class);
        
        user = new TestUserHelper.Builder(WorkerApiTest.class).withConsentUser(true)
                .withExternalIds(ImmutableMap.of(STUDY_ID_1, externalId)).withSynapseUserId(SYNAPSE_USER_ID).createAndSignInUser();

        // Have the user get activities, to bootstrap timezone.
        user.getClient(ActivitiesApi.class).getScheduledActivitiesByDateRange(DateTime.now(TEST_USER_TIME_ZONE),
                DateTime.now(TEST_USER_TIME_ZONE).plusDays(1)).execute();

        // Get all participants
        AccountSummaryList list = workersApi.getParticipantsForApp(TEST_APP_ID, 0, 5, "", null, null, null).execute()
                .body();
        assertTrue(list.getTotal() > 0);

        // Get worker participant.
        list = workersApi.getParticipantsForApp(TEST_APP_ID, 0, 5, worker.getEmail(), null, null, null).execute().body();
        assertEquals(1, list.getItems().size());
        assertEquals(worker.getEmail(), list.getItems().get(0).getEmail());
        
        // Get user participant. Include consent history in this call.
        StudyParticipant participant = workersApi.getParticipantByIdForApp(TEST_APP_ID, user.getSession().getId(), true)
                .execute().body();
        assertEquals(user.getEmail(), participant.getEmail());
        assertEquals(TEST_USER_TIME_ZONE_STRING, participant.getTimeZone());
        assertNotNull(participant.getHealthCode());
        assertNotNull(participant.getConsentHistories().get(TEST_APP_ID).get(0));
        
        // get by health code, also verify we do not include consent histories.
        StudyParticipant participant2 = workersApi
                .getParticipantByHealthCodeForApp(TEST_APP_ID, participant.getHealthCode(), false).execute().body();
        assertEquals(participant.getId(), participant2.getId());
        assertNull(participant2.getConsentHistories().get(TEST_APP_ID));
        
        // get by external Id, also verify we do not include consent histories.
        StudyParticipant participant3 = workersApi
                .getParticipantByExternalIdForApp(TEST_APP_ID, externalId, false).execute().body();
        assertEquals(participant.getId(), participant3.getId());
        assertNull(participant3.getConsentHistories().get(TEST_APP_ID));
        
        // get by synapse user id
        StudyParticipant participant4 = workersApi
                .getParticipantBySynapseUserIdForApp(TEST_APP_ID, SYNAPSE_USER_ID, false).execute().body();
        assertEquals(participant.getId(), participant4.getId());
    }
    
    @SuppressWarnings("deprecation")
    @Test
    public void retrieveUsersWithPhone() throws Exception {
        IntegTestUtils.deletePhoneUser();

        SignUp signUp = new SignUp().phone(IntegTestUtils.PHONE).password("P@ssword`1");
        phoneUser = TestUserHelper.createAndSignInUser(WorkerApiTest.class, true, signUp);
        
        AccountSummaryList list = workersApi.getParticipantsForApp(TEST_APP_ID, 0, 5, null, "248-6796", null, null).execute().body();
        assertEquals(1, list.getItems().size());
        assertEquals(phoneUser.getPhone().getNumber(), list.getItems().get(0).getPhone().getNumber());
        
        String userId = list.getItems().get(0).getId();
        StudyParticipant participant = workersApi.getParticipantByIdForApp(TEST_APP_ID, userId, false).execute().body();
        
        assertEquals(phoneUser.getPhone().getNumber(), participant.getPhone().getNumber());
        assertNotNull(participant.getHealthCode());
    }
    
    @Test
    public void retrieveStudiesSchedulePlans() throws Exception {
        SchedulePlan plan = Tests.getABTestSchedulePlan();
        SchedulesApi planApi = developer.getClient(SchedulesApi.class);
        GuidVersionHolder guid = null;
        try {
            guid = planApi.createSchedulePlan(plan).execute().body();
            
            SchedulePlanList plans = workersApi.getSchedulePlansForApp(TEST_APP_ID, false).execute().body();
            
            final String theGuid = guid.getGuid();
            if (!plans.getItems().stream().anyMatch((onePlan) -> onePlan.getGuid().equals(theGuid))) {
                fail("Should have found schedule plan.");
            }
        } finally {
            if (guid != null) {
                admin.getClient(SchedulesApi.class).deleteSchedulePlan(guid.getGuid(), true).execute();
            }
        }
    }
    
    @Test
    public void retrieveUsersActivityEvents() throws Exception {
        ActivityEventList list = workersApi
                .getActivityEventsForParticipantAndApp(worker.getAppId(), worker.getSession().getId()).execute().body();
        
        assertFalse(list.getItems().isEmpty());
    }
    
    @Test
    public void retrieveUsersActivities() throws Exception {
        SchedulePlan plan = Tests.getDailyRepeatingSchedulePlan();
        SchedulesApi planApi = developer.getClient(SchedulesApi.class);
        
        user = new TestUserHelper.Builder(WorkerApiTest.class).withConsentUser(true).createAndSignInUser();
        
        GuidVersionHolder guid = null;
        try {
            guid = planApi.createSchedulePlan(plan).execute().body();
            plan = planApi.getSchedulePlan(guid.getGuid()).execute().body();
            
            ForConsentedUsersApi userApi = user.getClient(ForConsentedUsersApi.class);
            userApi.getScheduledActivities("-07:00", 4, 1).execute();
            
            String activityGuid = ((SimpleScheduleStrategy) plan.getStrategy())
                    .getSchedule().getActivities().get(0).getGuid();
            ForwardCursorScheduledActivityList list = workersApi.getParticipantActivityHistoryForApp(
                    user.getAppId(), user.getSession().getId(), activityGuid, DateTime.now().minusDays(2), 
                    DateTime.now().plusDays(2), null, 50).execute().body();

            assertFalse(list.getItems().isEmpty());
        } finally {
            if (guid != null) {
                admin.getClient(SchedulesApi.class).deleteSchedulePlan(guid.getGuid(), true).execute();    
            }
        }
    }
    
    @Test
    public void retrieveUsersActivitiesByType() throws Exception {
        SchedulePlan plan = Tests.getDailyRepeatingSchedulePlan();
        SchedulesApi planApi = developer.getClient(SchedulesApi.class);
        
        user = new TestUserHelper.Builder(WorkerApiTest.class).withConsentUser(true).createAndSignInUser();
        
        GuidVersionHolder guid = null;
        try {
            guid = planApi.createSchedulePlan(plan).execute().body();
            plan = planApi.getSchedulePlan(guid.getGuid()).execute().body();
            
            ForConsentedUsersApi userApi = user.getClient(ForConsentedUsersApi.class);
            userApi.getScheduledActivities("-07:00", 4, 1).execute();

            Tests.retryHelper(() -> workersApi.getParticipantTaskHistoryForApp(user.getAppId(),
                    user.getSession().getId(), "task:CCC", DateTime.now().minusDays(2), DateTime.now().plusDays(2),
                    null, 50).execute().body().getItems(),
                    Predicates.not(List::isEmpty));
        } finally {
            if (guid != null) {
                admin.getClient(SchedulesApi.class).deleteSchedulePlan(guid.getGuid(), true).execute();    
            }
        }
    }

    @Test
    public void sendUserSmsMessage() throws Exception {
        SignUp signUp = new SignUp();
        signUp.setPhone(IntegTestUtils.PHONE);
        signUp.setAppId(TEST_APP_ID);
        signUp.setConsent(true);

        user = new TestUserHelper.Builder(WorkerApiTest.class).withSignUp(signUp).withConsentUser(true)
                .createAndSignInUser();

        // User should get activities to set that initial timezone.
        DateTime startTime = DateTime.now(TEST_USER_TIME_ZONE);
        user.getClient(ActivitiesApi.class).getScheduledActivitiesByDateRange(startTime, startTime.plusDays(1))
                .execute();

        // Test sending SMS from worker.
        String messageFromWorker = "Test message from worker.";
        workersApi.sendSmsMessageToParticipantForApp(user.getAppId(), user.getSession().getId(),
                new SmsTemplate().message(messageFromWorker)).execute();
        verifyPromotionalMessage(messageFromWorker);
    }

    private void verifyPromotionalMessage(String expectedMessageBody) throws Exception {
        // Verify message logs contains the expected message.
        SmsMessage message = admin.getClient(InternalApi.class).getMostRecentSmsMessage(user.getUserId()).execute()
                .body();
        assertEquals(user.getPhone().getNumber(), message.getPhoneNumber());
        assertEquals(expectedMessageBody, message.getMessageBody());
        assertNotNull(message.getMessageId());
        assertEquals(SmsType.PROMOTIONAL, message.getSmsType());
        assertEquals(user.getAppId(), message.getAppId());

        // Clock skew on Jenkins can be known to go as high as 10 minutes. For a robust test, simply check that the
        // message was sent within the last hour.
        assertTrue(message.getSentOn().isAfter(DateTime.now().minusHours(1)));

        // Verify the health code matches.
        StudyParticipant participant = workersApi.getParticipantByIdForApp(user.getAppId(), user.getUserId(),
                false).execute().body();
        assertEquals(participant.getHealthCode(), message.getHealthCode());

        // Verify the SMS message log was written to health data.
        DateTime messageSentOn = message.getSentOn();
        Optional<HealthDataRecord> smsMessageRecordOpt = Tests.retryHelper(() -> user.getClient(InternalApi.class)
                        .getHealthDataByCreatedOn(messageSentOn, messageSentOn).execute().body().getItems().stream()
                        .filter(r -> r.getSchemaId().equals("sms-messages-sent-from-bridge")).findAny(),
                Optional::isPresent);
        HealthDataRecord smsMessageRecord = smsMessageRecordOpt.get();
        assertEquals("sms-messages-sent-from-bridge", smsMessageRecord.getSchemaId());
        assertEquals(1, smsMessageRecord.getSchemaRevision().intValue());

        assertEquals(messageSentOn.getMillis(), smsMessageRecord.getCreatedOn().getMillis());
        assertEquals("-0800", smsMessageRecord.getCreatedOnTimeZone());

        // Verify data.
        Map<String, String> recordDataMap = RestUtils.toType(smsMessageRecord.getData(), Map.class);
        assertEquals("Promotional", recordDataMap.get("smsType"));
        assertEquals(expectedMessageBody, recordDataMap.get("messageBody"));

        DateTime recordSentOn = DateTime.parse(recordDataMap.get("sentOn"));
        assertEquals(messageSentOn.getMillis(), recordSentOn.getMillis());
        assertEquals(TEST_USER_TIME_ZONE, recordSentOn.getZone());
    }
    
    /**
     * Verify that a worker, associated to an organization (Sage Bionetworks), can see users across the app
     * boundary. Uses 'api' and 'shared' app context for testing.
     */
    @Test
    public void retrieveUsersBetweenApps() throws Exception {
        ForSuperadminsApi adminsApi = admin.getClient(ForSuperadminsApi.class);
        adminsApi.adminChangeApp(new SignIn().appId(SHARED_APP_ID)).execute();
        TestUser sharedUser = new TestUserHelper.Builder(WorkerApiTest.class).withAppId(SHARED_APP_ID).createUser();
        
        adminsApi.adminChangeApp(new SignIn().appId(TEST_APP_ID)).execute();
        TestUser testUser = new TestUserHelper.Builder(WorkerApiTest.class).withAppId(TEST_APP_ID).createUser();
        
        // This worker is by default in Sage Bionetworks, and thus is associated to studies in the 'api'
        // context. when the worker calls into another context, those APIs should work.
        TestUser worker = TestUserHelper.createAndSignInUser(WorkerApiTest.class, false, WORKER);
        try {
            ForWorkersApi workerApi = worker.getClient(ForWorkersApi.class);
            
            // First verify that list methods work...
            AccountSummaryList sharedList = workerApi.getParticipantsForApp(SHARED_APP_ID, 0, 50, null, null, null, null).execute().body();
            assertUserInList(sharedList.getItems(), sharedUser.getUserId());
            
            AccountSummaryList apiList = workerApi.getParticipantsForApp(TEST_APP_ID, 0, 50, null, null, null, null).execute().body();
            assertUserInList(apiList.getItems(), testUser.getUserId());
            
            // Getting individual accounts also works
            StudyParticipant p1 = workerApi.getParticipantByIdForApp(SHARED_APP_ID, sharedUser.getUserId(), false).execute().body();
            assertEquals(sharedUser.getUserId(), p1.getId());
            
            StudyParticipant p2 = workerApi.getParticipantByIdForApp(TEST_APP_ID, testUser.getUserId(), false).execute().body();
            assertEquals(testUser.getUserId(), p2.getId());
            
            AccountSummarySearch search = new AccountSummarySearch();
            search.startTime(DateTime.now().minusHours(1));
            search.endTime(DateTime.now().plusHours(1));
            
            AccountSummaryList sharedSearchList = workerApi.searchAccountSummariesForApp(
                    SHARED_APP_ID, search).execute().body();
            assertUserInList(sharedSearchList.getItems(), sharedUser.getUserId());
            
            AccountSummaryList testSearchList = workerApi.searchAccountSummariesForApp(
                    TEST_APP_ID, search).execute().body();
            assertUserInList(testSearchList.getItems(), testUser.getUserId());
            
        } finally {
            if (sharedUser != null) {
                sharedUser.signOutAndDeleteUser();
            }
            if (testUser != null) {
                testUser.signOutAndDeleteUser();
            }
            if (worker != null) {
                worker.signOutAndDeleteUser();
            }
        }
    }
    
    private void assertUserInList(List<AccountSummary> list, String userId) {
        for (AccountSummary summary : list) {
            if (summary.getId().equals(userId)) {
                return;
            }
        }
        fail("Could not find user in list");
    }
}
