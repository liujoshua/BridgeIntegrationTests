package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.sagebionetworks.bridge.rest.model.Role.DEVELOPER;
import static org.sagebionetworks.bridge.rest.model.Role.RESEARCHER;
import static org.sagebionetworks.bridge.sdk.integration.Tests.ORG_ID_1;
import static org.sagebionetworks.bridge.sdk.integration.Tests.ORG_ID_2;
import static org.sagebionetworks.bridge.sdk.integration.Tests.PASSWORD;
import static org.sagebionetworks.bridge.sdk.integration.Tests.STUDY_ID_1;
import static org.sagebionetworks.bridge.sdk.integration.Tests.STUDY_ID_2;
import static org.sagebionetworks.bridge.util.IntegTestUtils.TEST_APP_ID;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.sagebionetworks.bridge.rest.ClientManager;
import org.sagebionetworks.bridge.rest.api.AppsApi;
import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.rest.api.ForResearchersApi;
import org.sagebionetworks.bridge.rest.api.OrganizationsApi;
import org.sagebionetworks.bridge.rest.api.SchedulesApi;
import org.sagebionetworks.bridge.rest.api.StudiesApi;
import org.sagebionetworks.bridge.rest.api.SubpopulationsApi;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.model.AccountSummary;
import org.sagebionetworks.bridge.rest.model.AccountSummaryList;
import org.sagebionetworks.bridge.rest.model.Activity;
import org.sagebionetworks.bridge.rest.model.App;
import org.sagebionetworks.bridge.rest.model.ConsentStatus;
import org.sagebionetworks.bridge.rest.model.Criteria;
import org.sagebionetworks.bridge.rest.model.CriteriaScheduleStrategy;
import org.sagebionetworks.bridge.rest.model.Enrollment;
import org.sagebionetworks.bridge.rest.model.GuidVersionHolder;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.Schedule;
import org.sagebionetworks.bridge.rest.model.ScheduleCriteria;
import org.sagebionetworks.bridge.rest.model.SchedulePlan;
import org.sagebionetworks.bridge.rest.model.ScheduleType;
import org.sagebionetworks.bridge.rest.model.ScheduledActivity;
import org.sagebionetworks.bridge.rest.model.ScheduledActivityListV4;
import org.sagebionetworks.bridge.rest.model.SignIn;
import org.sagebionetworks.bridge.rest.model.SignUp;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.rest.model.Subpopulation;
import org.sagebionetworks.bridge.rest.model.TaskReference;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.util.IntegTestUtils;

import com.google.common.collect.ImmutableList;

public class StudyFilteringTest {
    
    public static class UserInfo {
        private final String userId;
        private final SignIn signIn;
        public UserInfo(String userId, String email) {
            this.userId = userId;
            this.signIn = new SignIn().email(email).password(Tests.PASSWORD).appId(TEST_APP_ID);
        }
        public String getId() { return userId; }
        public SignIn getSignIn() { return signIn; }
    }

    private static TestUser admin;
    private static TestUser developer;
    private static Set<String> userIdsToDelete;
    
    private static SignIn researcher1;
    private static SignIn researcher2;
    
    private static UserInfo user1;
    private static UserInfo user2;
    private static UserInfo user1and2;        
    
    @BeforeClass
    public static void before() throws Exception { 
        admin = TestUserHelper.getSignedInAdmin();
        
        developer = TestUserHelper.createAndSignInUser(StudyFilteringTest.class, false, DEVELOPER);
        
        userIdsToDelete = new HashSet<>();
        
        // Create a researcher in study1 and study2
        researcher1 = createAdmin(RESEARCHER, ORG_ID_1).getSignIn();
        researcher2 = createAdmin(RESEARCHER, ORG_ID_2).getSignIn();
        
        // Create three accounts, one study1, one study2, one studies 1 and 2
        user1 = createUser(STUDY_ID_1);
        user2 = createUser(STUDY_ID_2);
        user1and2 = createUser(STUDY_ID_1, STUDY_ID_2);        
    }

    @AfterClass
    public static void after() throws Exception {
        ForAdminsApi adminsApi = admin.getClient(ForAdminsApi.class);
        for (String userId : userIdsToDelete) {
            try {
                adminsApi.deleteUser(userId).execute();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        try {
            developer.signOutAndDeleteUser();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    @SuppressWarnings("deprecation")
    @Test
    public void filterParticipants() throws Exception { 
        // researcher1
        ClientManager manager = new ClientManager.Builder().withSignIn(researcher1).build();
        ForResearchersApi researcherApiFor1 = manager.getClient(ForResearchersApi.class);
        
        // This researcher sees 1 study users only
        AccountSummaryList list = researcherApiFor1.getParticipants(null, null, null, null, null, null).execute().body();
        assertListContainsAccount(list.getItems(), STUDY_ID_1, user1.getId());
        assertListContainsAccount(list.getItems(), STUDY_ID_1, user1and2.getId());
        
        // researcher2
        manager = new ClientManager.Builder().withSignIn(researcher2).build();
        ForResearchersApi researcherApiFor2 = manager.getClient(ForResearchersApi.class);
        
        // This researcher sees study 2 users only
        list = researcherApiFor2.getParticipants(null, null, null, null, null, null).execute().body();
        assertListContainsAccount(list.getItems(), STUDY_ID_2, user2.getId());
        assertListContainsAccount(list.getItems(), STUDY_ID_2, user1and2.getId());
        
        // Researcher 2 should not be able to get a study 1 account
        try {
            researcherApiFor2.getParticipantById(user1.getId(), false).execute().body();
            fail("Should have thrown exception");
        } catch(EntityNotFoundException e) {
        }
        // Researcher B should be able to get only study B accounts (even if mixed)
        researcherApiFor2.getParticipantById(user2.getId(), false).execute().body();
        researcherApiFor2.getParticipantById(user1and2.getId(), false).execute().body();
        
        // This should apply to any call involving user in another study, try one (fully tested in 
        // the unit tests)
        try {
            researcherApiFor2.getActivityEventsForParticipant(user1.getId()).execute();
            fail("Should have thrown exception");
        } catch(EntityNotFoundException e) {
        }
        
        // Researcher 1 should not be able to save and destroy the mapping to study 2
        StudyParticipant participant = researcherApiFor1.getParticipantById(user1and2.getId(), false).execute().body();
        researcherApiFor1.updateParticipant(user1and2.getId(), participant);
        
        participant = researcherApiFor2.getParticipantById(user1and2.getId(), false).execute().body();
        assertTrue(participant.getStudyIds().contains(STUDY_ID_2)); // this wasn't wiped out by the update.
    }
    
    // AppConfigs: while these have criteria, they can only be filtered by data that does 
    // not require authentication: language and the User-Agent string. The primary use case 
    // is to select an app config by app version, even before the user creates an account.
    
    @Test
    public void filterSubpopulations() throws Exception {
        Criteria criteria = new Criteria();
        criteria.setAllOfStudyIds(ImmutableList.of(STUDY_ID_2));
        
        Subpopulation subpop2A = new Subpopulation();
        subpop2A.criteria(criteria);
        subpop2A.setName("Optional consent for study 2");
        subpop2A.setStudyIdsAssignedOnConsent(ImmutableList.of(STUDY_ID_2));
        
        SubpopulationsApi subpopApi = developer.getClient(SubpopulationsApi.class);
        GuidVersionHolder keys = null;
        try {
            keys = subpopApi.createSubpopulation(subpop2A).execute().body();
            
            // If the rules are being applied, user 1 can see this optional subpop on sign in, but user 2 cannot.
            ClientManager client1 = new ClientManager.Builder().withSignIn(user1.getSignIn()).build();
            client1.getClient(ForConsentedUsersApi.class).getActivityEvents().execute();
            Map<String, ConsentStatus> statuses1 = client1.getSessionOfClients().getConsentStatuses();
            assertNull(statuses1.get(keys.getGuid()));
            
            ClientManager client2 = new ClientManager.Builder().withSignIn(user2.getSignIn()).build();
            client2.getClient(ForConsentedUsersApi.class).getActivityEvents().execute();
            Map<String, ConsentStatus> statuses2 = client2.getSessionOfClients().getConsentStatuses();
            assertNotNull(statuses2.get(keys.getGuid()));
        } finally {
            if (keys != null) {
                admin.getClient(ForAdminsApi.class).deleteSubpopulation(keys.getGuid(), true).execute();
            }
        }
    }
    
    @Test
    public void filterScheduling() throws Exception {
        String activityLabel = Tests.randomIdentifier(StudyFilteringTest.class);
        
        App app = admin.getClient(AppsApi.class).getUsersApp().execute().body();
        if (app.getTaskIdentifiers().isEmpty()) {
            app.setTaskIdentifiers(ImmutableList.of("task1"));
            admin.getClient(AppsApi.class).updateUsersApp(app).execute();
        }
        String taskId = app.getTaskIdentifiers().get(0);
        
        Criteria criteria = new Criteria();
        criteria.setAllOfStudyIds(ImmutableList.of(STUDY_ID_2));
        
        TaskReference ref = new TaskReference();
        ref.setIdentifier(taskId);
        Activity activity = new Activity();
        activity.setLabel(activityLabel);
        activity.setTask(ref);
        
        Schedule schedule = new Schedule();
        schedule.setScheduleType(ScheduleType.ONCE);
        schedule.setActivities(ImmutableList.of(activity));
        
        ScheduleCriteria scheduleCriteria = new ScheduleCriteria();
        scheduleCriteria.setCriteria(criteria);
        scheduleCriteria.setSchedule(schedule);
        
        CriteriaScheduleStrategy strategy = new CriteriaScheduleStrategy();
        strategy.setScheduleCriteria(ImmutableList.of(scheduleCriteria));
        
        SchedulePlan plan = new SchedulePlan();
        plan.setLabel(StudyFilteringTest.class.getSimpleName() + " Schedule Plan");
        plan.setStrategy(strategy);
        
        SchedulesApi schedulesApi = developer.getClient(SchedulesApi.class);
        GuidVersionHolder keys = null;
        try {
            keys = schedulesApi.createSchedulePlan(plan).execute().body();
            
            // Time zone changes break this test, both dates have to be in the same time zone. UTC accomplishes
            // that. See BRIDGE-2126
            DateTime startOn = DateTime.now(DateTimeZone.UTC);
            DateTime endOn = startOn.plusDays(4);
            
            // If the rules are being applied, user AB can see this activity, but neither A nor B can see it
            ClientManager client1and2 = new ClientManager.Builder().withSignIn(user1and2.getSignIn()).build();
            ScheduledActivityListV4 list1and2 = client1and2.getClient(ForConsentedUsersApi.class)
                    .getScheduledActivitiesByDateRange(startOn, endOn).execute().body();
            assertFalse(noneMatchActivityLabel(list1and2.getItems(), activityLabel));
            
            ClientManager client1 = new ClientManager.Builder().withSignIn(user1.getSignIn()).build();
            ScheduledActivityListV4 list1 = client1.getClient(ForConsentedUsersApi.class)
                    .getScheduledActivitiesByDateRange(startOn, endOn).execute().body();
            assertTrue(noneMatchActivityLabel(list1.getItems(), activityLabel));
            
            ClientManager client2 = new ClientManager.Builder().withSignIn(user2.getSignIn()).build();
            ScheduledActivityListV4 list2 = client2.getClient(ForConsentedUsersApi.class)
                    .getScheduledActivitiesByDateRange(startOn, endOn).execute().body();
            assertFalse(noneMatchActivityLabel(list2.getItems(), activityLabel));
        } finally {
            if (keys != null) {
                admin.getClient(ForAdminsApi.class).deleteSchedulePlan(keys.getGuid(), true).execute();
            }
        }
    }
    
    private boolean noneMatchActivityLabel(List<ScheduledActivity> activities, String activityLabel) {
        return activities.stream().filter((act) -> act.getActivity()
                .getLabel().equals(activityLabel)).count() == 0;
    }
    
    private void assertListContainsAccount(List<AccountSummary> summaries, String callerStudyId, String userId) {
        for (AccountSummary summary : summaries) {
            if (summary.getId().equals(userId) &&
                summary.getStudyIds().contains(callerStudyId)) {
                return;
            }
        }
        fail("Did not find account with ID or the account contains an invalid study: " + userId);
    }
    
    private static UserInfo createAdmin(Role role, String orgId) throws Exception {
        String email = IntegTestUtils.makeEmail(StudyFilteringTest.class);
        SignUp signUp = new SignUp().email(email).password(PASSWORD).appId(TEST_APP_ID)
                .consent(false).roles(ImmutableList.of(role));
                
        ForAdminsApi adminApi = admin.getClient(ForAdminsApi.class);
        String userId = adminApi.createUser(signUp).execute().body().getId();
        userIdsToDelete.add(userId);
        
        admin.getClient(OrganizationsApi.class).addMember(orgId, userId).execute();
        
        return new UserInfo(userId, email);
    }
    
    private static UserInfo createUser(String... studyIds) throws Exception {
        String email = IntegTestUtils.makeEmail(StudyFilteringTest.class);
        SignUp signUp = new SignUp().email(email).password(PASSWORD).appId(TEST_APP_ID)
                .consent(true);

        ForAdminsApi adminApi = admin.getClient(ForAdminsApi.class);
        StudiesApi studiesApi = admin.getClient(StudiesApi.class);
        String userId = adminApi.createUser(signUp).execute().body().getId();
        for (String studyId : studyIds) {
            studiesApi.enrollParticipant(studyId, new Enrollment().userId(userId)).execute();
        }
        userIdsToDelete.add(userId);
        return new UserInfo(userId, email);
    }
}
