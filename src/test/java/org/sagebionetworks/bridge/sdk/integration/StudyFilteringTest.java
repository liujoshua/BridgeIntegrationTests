package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
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
import org.sagebionetworks.bridge.rest.model.Study;
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
    private static StudiesApi studiesApi;
    private static Set<String> studyIdsToDelete;
    private static Set<String> userIdsToDelete;
    
    private static String studyIdA;
    private static String studyIdB;
    
    private static SignIn researcherA;
    private static SignIn researcherB;
    
    private static UserInfo userA;
    private static UserInfo userB;
    private static UserInfo userAB;        
    
    @BeforeClass
    public static void before() throws Exception { 
        admin = TestUserHelper.getSignedInAdmin();
        studiesApi = admin.getClient(StudiesApi.class);
        
        developer = TestUserHelper.createAndSignInUser(StudyFilteringTest.class, false, Role.DEVELOPER);
        
        studyIdsToDelete = new HashSet<>();
        userIdsToDelete = new HashSet<>();
        
        // Create two studies
        studyIdA = createStudy();
        studyIdB = createStudy();
        
        // Create a researcher in study A and study B
        researcherA = createUser(Role.RESEARCHER, studyIdA).getSignIn();
        researcherB = createUser(Role.RESEARCHER, studyIdB).getSignIn();
        
        // Create three accounts, one A, one B, one AB, one with nothing
        userA = createUser(null, studyIdA);
        userB = createUser(null, studyIdB);
        userAB = createUser(null, studyIdA, studyIdB);        
    }

    @AfterClass
    public static void after() throws Exception {
        ForAdminsApi adminsApi = admin.getClient(ForAdminsApi.class);
        for (String userId : userIdsToDelete) {
            try {
                adminsApi.deleteUser(userId).execute();    
            } catch(Exception e) {
                e.printStackTrace();
            }
        }
        for (String studyId : studyIdsToDelete) {
            try {
                adminsApi.deleteStudy(studyId, true).execute();    
            } catch(Exception e) {
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
        // researcherA
        ClientManager manager = new ClientManager.Builder().withSignIn(researcherA).build();
        ForResearchersApi researcherApiForA = manager.getClient(ForResearchersApi.class);
        
        // This researcher sees A study users only
        AccountSummaryList list = researcherApiForA.getParticipants(null, null, null, null, null, null).execute().body();
        assertListContainsAccount(list.getItems(), studyIdA, userA.getId());
        assertListContainsAccount(list.getItems(), studyIdA, userAB.getId());
        
        // researcherB
        manager = new ClientManager.Builder().withSignIn(researcherB).build();
        ForResearchersApi researcherApiForB = manager.getClient(ForResearchersApi.class);
        
        // This researcher sees B study users only
        list = researcherApiForB.getParticipants(null, null, null, null, null, null).execute().body();
        assertListContainsAccount(list.getItems(), studyIdB, userB.getId());
        assertListContainsAccount(list.getItems(), studyIdB, userAB.getId());
        
        // Researcher B should not be able to get a study A account
        try {
            researcherApiForB.getParticipantById(userA.getId(), false).execute().body();
            fail("Should have thrown exception");
        } catch(EntityNotFoundException e) {
        }
        // Researcher B should be able to get only study B accounts (even if mixed)
        researcherApiForB.getParticipantById(userB.getId(), false).execute().body();
        researcherApiForB.getParticipantById(userAB.getId(), false).execute().body();
        
        // This should apply to any call involving user in another study, try one (fully tested in 
        // the unit tests)
        try {
            researcherApiForB.getActivityEventsForParticipant(userA.getId()).execute();
            fail("Should have thrown exception");
        } catch(EntityNotFoundException e) {
        }
        
        // Researcher A should not be able to save and destroy the mapping to study B
        StudyParticipant participant = researcherApiForA.getParticipantById(userAB.getId(), false).execute().body();
        researcherApiForA.updateParticipant(userAB.getId(), participant);
        
        participant = researcherApiForB.getParticipantById(userAB.getId(), false).execute().body();
        assertTrue(participant.getStudyIds().contains(studyIdB)); // this wasn't wiped out by the update.
    }
    
    // AppConfigs: while these have criteria, they can only be filtered by data that does 
    // not require authentication: language and the User-Agent string. The primary use case 
    // is to select an app config by app version, even before the user creates an account.
    
    @Test
    public void filterSubpopulations() throws Exception {
        Criteria criteria = new Criteria();
        criteria.setAllOfStudyIds(ImmutableList.of(studyIdA));
        
        Subpopulation subpopA = new Subpopulation();
        subpopA.criteria(criteria);
        subpopA.setName("Optional consent for study A");
        
        SubpopulationsApi subpopApi = developer.getClient(SubpopulationsApi.class);
        GuidVersionHolder keys = null;
        try {
            keys = subpopApi.createSubpopulation(subpopA).execute().body();
            
            // If the rules are being applied, user A can see this optional subpop on sign in, but user B cannot.
            ClientManager aClient = new ClientManager.Builder().withSignIn(userA.getSignIn()).build();
            aClient.getClient(ForConsentedUsersApi.class).getActivityEvents().execute();
            Map<String, ConsentStatus> statusesA = aClient.getSessionOfClients().getConsentStatuses();
            assertNotNull(statusesA.get(keys.getGuid()));
            
            ClientManager bClient = new ClientManager.Builder().withSignIn(userB.getSignIn()).build();
            bClient.getClient(ForConsentedUsersApi.class).getActivityEvents().execute();
            Map<String, ConsentStatus> statusesB = bClient.getSessionOfClients().getConsentStatuses();
            assertNull(statusesB.get(keys.getGuid()));
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
        criteria.setAllOfStudyIds(ImmutableList.of(studyIdA, studyIdB));
        
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
            ClientManager abClient = new ClientManager.Builder().withSignIn(userAB.getSignIn()).build();
            ScheduledActivityListV4 abList = abClient.getClient(ForConsentedUsersApi.class)
                    .getScheduledActivitiesByDateRange(startOn, endOn).execute().body();
            assertFalse(someMatchActivityLabel(abList.getItems(), activityLabel));
            
            ClientManager aClient = new ClientManager.Builder().withSignIn(userA.getSignIn()).build();
            ScheduledActivityListV4 aList = aClient.getClient(ForConsentedUsersApi.class)
                    .getScheduledActivitiesByDateRange(startOn, endOn).execute().body();
            assertTrue(someMatchActivityLabel(aList.getItems(), activityLabel));
            
            ClientManager bClient = new ClientManager.Builder().withSignIn(userB.getSignIn()).build();
            ScheduledActivityListV4 bList = bClient.getClient(ForConsentedUsersApi.class)
                    .getScheduledActivitiesByDateRange(startOn, endOn).execute().body();
            assertTrue(someMatchActivityLabel(bList.getItems(), activityLabel));
        } finally {
            if (keys != null) {
                admin.getClient(ForAdminsApi.class).deleteSchedulePlan(keys.getGuid(), true).execute();
            }
        }
    }
    
    private boolean someMatchActivityLabel(List<ScheduledActivity> activities, String activityLabel) {
        return activities.stream().filter((act) -> act.getActivity()
                .getLabel().equals(activityLabel)).count() == 0;
    }
    
    private void assertListContainsAccount(List<AccountSummary> summaries, String callerStudyId, String userId) {
        for (AccountSummary summary : summaries) {
            if (summary.getId().equals(userId) &&
                summary.getStudyIds().size() == 1 &&
                summary.getStudyIds().contains(callerStudyId)) {
                return;
            }
        }
        fail("Did not find account with ID or the account contains an invalid study: " + userId);
    }
    
    private static String createStudy() throws Exception {
        String id = Tests.randomIdentifier(StudyFilteringTest.class);
        Study studyA = new Study().identifier(id).name("Study " + id);
        studiesApi.createStudy(studyA).execute();
        studyIdsToDelete.add(id);
        return id;
    }
    
    private static UserInfo createUser(Role role, String... studyIds) throws Exception {
        String email = IntegTestUtils.makeEmail(StudyFilteringTest.class);
        SignUp signUp = new SignUp().email(email).password(Tests.PASSWORD).appId(TEST_APP_ID).consent(true);
        signUp.studyIds(ImmutableList.copyOf(studyIds));
        if (role != null) {
            signUp.setRoles(ImmutableList.of(role));
        }
                
        ForAdminsApi adminApi = admin.getClient(ForAdminsApi.class);
        String userId = adminApi.createUser(signUp).execute().body().getId();
        userIdsToDelete.add(userId);
        return new UserInfo(userId, email);
    }
}
