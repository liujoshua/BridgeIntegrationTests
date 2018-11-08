package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.Map;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.RestUtils;
import org.sagebionetworks.bridge.rest.api.ActivitiesApi;
import org.sagebionetworks.bridge.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.rest.api.ForResearchersApi;
import org.sagebionetworks.bridge.rest.api.ForWorkersApi;
import org.sagebionetworks.bridge.rest.api.InternalApi;
import org.sagebionetworks.bridge.rest.api.SchedulesApi;
import org.sagebionetworks.bridge.rest.model.AccountSummaryList;
import org.sagebionetworks.bridge.rest.model.ActivityEventList;
import org.sagebionetworks.bridge.rest.model.ForwardCursorScheduledActivityList;
import org.sagebionetworks.bridge.rest.model.GuidVersionHolder;
import org.sagebionetworks.bridge.rest.model.HealthDataRecord;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.SchedulePlan;
import org.sagebionetworks.bridge.rest.model.SchedulePlanList;
import org.sagebionetworks.bridge.rest.model.SignUp;
import org.sagebionetworks.bridge.rest.model.SimpleScheduleStrategy;
import org.sagebionetworks.bridge.rest.model.SmsMessage;
import org.sagebionetworks.bridge.rest.model.SmsTemplate;
import org.sagebionetworks.bridge.rest.model.SmsType;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.util.IntegTestUtils;

@SuppressWarnings("unchecked")
public class WorkerApiTest {
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
    }

    @After
    public void deleteWorker() throws Exception {
        if (worker != null) {
            worker.signOutAndDeleteUser();
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
    
    @Test
    public void retrieveUsers() throws Exception {
        String externalId = Tests.randomIdentifier(WorkerApiTest.class);
        user = new TestUserHelper.Builder(WorkerApiTest.class).withConsentUser(true)
                .withExternalId(externalId).createAndSignInUser();

        // Have the user get activities, to bootstrap timezone.
        user.getClient(ActivitiesApi.class).getScheduledActivitiesByDateRange(DateTime.now(TEST_USER_TIME_ZONE),
                DateTime.now(TEST_USER_TIME_ZONE).plusDays(1)).execute();

        // Get all participants
        AccountSummaryList list = workersApi.getParticipants("api", 0, 5, "", null, null, null).execute().body();
        assertTrue(list.getTotal() > 0);

        // Get worker participant.
        list = workersApi.getParticipants("api", 0, 5, worker.getEmail(), null, null, null).execute().body();
        assertEquals(1, list.getItems().size());
        assertEquals(worker.getEmail(), list.getItems().get(0).getEmail());
        
        // Get user participant. Include consent history in this call.
        StudyParticipant participant = workersApi.getParticipantById("api", user.getSession().getId(), true)
                .execute().body();
        assertEquals(user.getEmail(), participant.getEmail());
        assertEquals(TEST_USER_TIME_ZONE_STRING, participant.getTimeZone());
        assertNotNull(participant.getHealthCode());
        assertNotNull(participant.getConsentHistories().get("api").get(0));
        
        // get by health code, also verify we do not include consent histories.
        StudyParticipant participant2 = workersApi.getParticipantByHealthCode("api", participant.getHealthCode(), false)
                .execute().body();
        assertEquals(participant.getId(), participant2.getId());
        assertNull(participant2.getConsentHistories().get("api"));
        
        // get by external Id, also verify we do not include consent histories.
        StudyParticipant participant3 = workersApi.getParticipantByExternalId("api", externalId, false)
                .execute().body();
        assertEquals(participant.getId(), participant3.getId());
        assertNull(participant3.getConsentHistories().get("api"));
    }
    
    @Test
    public void retrieveUsersWithPhone() throws Exception {
        IntegTestUtils.deletePhoneUser(researcher);

        SignUp signUp = new SignUp().phone(IntegTestUtils.PHONE).password("P@ssword`1");
        phoneUser = TestUserHelper.createAndSignInUser(WorkerApiTest.class, true, signUp);
        
        AccountSummaryList list = workersApi.getParticipants("api", 0, 5, null, "248-6796", null, null).execute().body();
        assertEquals(1, list.getItems().size());
        assertEquals(phoneUser.getPhone().getNumber(), list.getItems().get(0).getPhone().getNumber());
        
        String userId = list.getItems().get(0).getId();
        StudyParticipant participant = workersApi.getParticipantById("api", userId, false).execute().body();
        
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
            
            SchedulePlanList plans = workersApi.getSchedulePlans("api", false).execute().body();
            
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
                .getActivityEventsForParticipant(worker.getStudyId(), worker.getSession().getId()).execute().body();
        
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
            ForwardCursorScheduledActivityList list = workersApi.getParticipantActivityHistory(
                    user.getStudyId(), user.getSession().getId(), activityGuid, DateTime.now().minusDays(2), 
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

            // Sleep to wait for global secondary index
            Thread.sleep(2000);

            ForwardCursorScheduledActivityList list = workersApi.getParticipantTaskHistory(user.getStudyId(), 
                    user.getSession().getId(), "task:CCC", DateTime.now().minusDays(2), DateTime.now().plusDays(2), 
                    null, 50).execute().body();

            assertFalse(list.getItems().isEmpty());
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
        signUp.setStudy(IntegTestUtils.STUDY_ID);
        signUp.setConsent(true);
        
        user = new TestUserHelper.Builder(WorkerApiTest.class).withSignUp(signUp).withConsentUser(true)
                .createAndSignInUser();

        // User should get activities to set that initial timezone.
        DateTime startTime = DateTime.now(TEST_USER_TIME_ZONE);
        user.getClient(ActivitiesApi.class).getScheduledActivitiesByDateRange(startTime, startTime.plusDays(1))
                .execute();

        // Test sending SMS from worker.
        String messageFromWorker = "Test message from worker.";
        workersApi.sendSmsMessageToParticipant(user.getStudyId(), user.getSession().getId(),
                new SmsTemplate().message(messageFromWorker)).execute();
        verifyPromotionalMessage(messageFromWorker);

        // Test sending SMS from researcher.
        String messageFromResearcher = "Test message from researcher";
        ForResearchersApi researcherApi = researcher.getClient(ForResearchersApi.class);
        researcherApi.sendSmsMessageToParticipant(user.getSession().getId(),
                new SmsTemplate().message(messageFromResearcher)).execute();
        verifyPromotionalMessage(messageFromResearcher);
    }

    private void verifyPromotionalMessage(String expectedMessageBody) throws Exception {
        // Verify message logs contains the expected message.
        SmsMessage message = admin.getClient(InternalApi.class).getMostRecentSmsMessage(user.getUserId()).execute()
                .body();
        assertEquals(user.getPhone().getNumber(), message.getPhoneNumber());
        assertEquals(expectedMessageBody, message.getMessageBody());
        assertNotNull(message.getMessageId());
        assertEquals(SmsType.PROMOTIONAL, message.getSmsType());
        assertEquals(user.getStudyId(), message.getStudyId());

        // Clock skew on Jenkins can be known to go as high as 10 minutes. For a robust test, simply check that the
        // message was sent within the last hour.
        assertTrue(message.getSentOn().isAfter(DateTime.now().minusHours(1)));

        // Verify the health code matches.
        StudyParticipant participant = workersApi.getParticipantById(user.getStudyId(), user.getUserId(),
                false).execute().body();
        assertEquals(participant.getHealthCode(), message.getHealthCode());

        // Verify the SMS message log was written to health data.
        DateTime messageSentOn = message.getSentOn();
        List<HealthDataRecord> recordList = user.getClient(InternalApi.class).getHealthDataByCreatedOn(messageSentOn,
                messageSentOn).execute().body().getItems();
        HealthDataRecord smsMessageRecord = recordList.stream()
                .filter(r -> r.getSchemaId().equals("sms-messages-sent-from-bridge")).findAny().get();
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
}
