package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.joda.time.DateTime;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sagebionetworks.bridge.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.rest.api.ForResearchersApi;
import org.sagebionetworks.bridge.rest.api.ForWorkersApi;
import org.sagebionetworks.bridge.rest.api.SchedulesApi;
import org.sagebionetworks.bridge.rest.model.AccountSummaryList;
import org.sagebionetworks.bridge.rest.model.ActivityEventList;
import org.sagebionetworks.bridge.rest.model.ForwardCursorScheduledActivityList;
import org.sagebionetworks.bridge.rest.model.GuidVersionHolder;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.SchedulePlan;
import org.sagebionetworks.bridge.rest.model.SchedulePlanList;
import org.sagebionetworks.bridge.rest.model.SignUp;
import org.sagebionetworks.bridge.rest.model.SimpleScheduleStrategy;
import org.sagebionetworks.bridge.rest.model.SmsTemplate;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.sdk.integration.TestUserHelper.TestUser;

public class WorkerApiTest {
    
    TestUser worker;
    TestUser researcher;
    TestUser developer;
    TestUser phoneUser;
    TestUser user;
    ForWorkersApi workersApi;

    @Before
    public void before() throws Exception {
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
        
        AccountSummaryList list = workersApi.getParticipants("api", 0, 5, "", null, null, null).execute().body();
        assertTrue(list.getTotal() > 0);
        
        list = workersApi.getParticipants("api", 0, 5, worker.getEmail(), null, null, null).execute().body();
        assertEquals(1, list.getItems().size());
        assertEquals(worker.getEmail(), list.getItems().get(0).getEmail());
        
        // Include consent history in this call.
        StudyParticipant participant = workersApi.getParticipantById("api", user.getSession().getId(), true)
                .execute().body();
        assertEquals(user.getEmail(), participant.getEmail());
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
        Tests.deletePhoneUser(researcher);

        SignUp signUp = new SignUp().phone(Tests.PHONE).password("P@ssword`1");
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
            
            SchedulePlanList plans = workersApi.getSchedulePlans("api").execute().body();
            
            final String theGuid = guid.getGuid();
            if (!plans.getItems().stream().anyMatch((onePlan) -> onePlan.getGuid().equals(theGuid))) {
                fail("Should have found schedule plan.");
            }
        } finally {
            if (guid != null) {
                planApi.deleteSchedulePlan(guid.getGuid()).execute();    
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
                planApi.deleteSchedulePlan(guid.getGuid()).execute();    
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
            
            ForwardCursorScheduledActivityList list = workersApi.getParticipantTaskHistory(user.getStudyId(), 
                    user.getSession().getId(), "task:CCC", DateTime.now().minusDays(2), DateTime.now().plusDays(2), 
                    null, 50).execute().body();

            assertFalse(list.getItems().isEmpty());
        } finally {
            if (guid != null) {
                planApi.deleteSchedulePlan(guid.getGuid()).execute();    
            }
        }
    }
    
    @Test
    public void sendUserSmsMessage() throws Exception {
        SignUp signUp = new SignUp();
        signUp.setPhone(Tests.PHONE);    
        signUp.setStudy(Tests.STUDY_ID);
        signUp.setConsent(true);
        
        user = new TestUserHelper.Builder(WorkerApiTest.class).withSignUp(signUp).withConsentUser(true)
                .createAndSignInUser();

        // It doesn't fail...
        SmsTemplate message = new SmsTemplate().message("Test message.");
        workersApi.sendSmsMessageToParticipant(user.getStudyId(), user.getSession().getId(), message).execute();
        
        ForResearchersApi researcherApi = researcher.getClient(ForResearchersApi.class);
        
        researcherApi.sendSmsMessageToParticipant(user.getSession().getId(), message).execute();
    }
    
}
