package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;

import java.util.List;

import org.joda.time.DateTimeZone;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.sdk.ClientInfo;
import org.sagebionetworks.bridge.sdk.ClientProvider;
import org.sagebionetworks.bridge.sdk.Roles;
import org.sagebionetworks.bridge.sdk.SchedulePlanClient;
import org.sagebionetworks.bridge.sdk.integration.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.sdk.UserClient;
import org.sagebionetworks.bridge.sdk.models.Criteria;
import org.sagebionetworks.bridge.sdk.models.ResourceList;
import org.sagebionetworks.bridge.sdk.models.schedules.Activity;
import org.sagebionetworks.bridge.sdk.models.schedules.CriteriaScheduleStrategy;
import org.sagebionetworks.bridge.sdk.models.schedules.Schedule;
import org.sagebionetworks.bridge.sdk.models.schedules.ScheduleCriteria;
import org.sagebionetworks.bridge.sdk.models.schedules.SchedulePlan;
import org.sagebionetworks.bridge.sdk.models.schedules.ScheduleType;
import org.sagebionetworks.bridge.sdk.models.schedules.ScheduledActivity;
import org.sagebionetworks.bridge.sdk.models.schedules.TaskReference;
import org.sagebionetworks.bridge.sdk.models.studies.OperatingSystem;

public class ScheduleTest {

    private String planGuid;
    
    private TestUser user;
    private TestUser developer;
    
    @Before
    public void before() {
        user = TestUserHelper.createAndSignInUser(ScheduleTest.class, true);
        developer = TestUserHelper.createAndSignInUser(ScheduleTest.class, true, Roles.DEVELOPER);
        ClientProvider.setClientInfo(new ClientInfo.Builder().withAppName(Tests.APP_NAME).withAppVersion(3).build());
    }
    
    @After
    public void after() {
        ClientProvider.setClientInfo(Tests.TEST_CLIENT_INFO);
        if (user != null) {
            user.signOutAndDeleteUser();    
        }
        if (developer != null) {
            try {
                if (planGuid != null) {
                    SchedulePlanClient schedulePlanClient = developer.getSession().getSchedulePlanClient();
                    schedulePlanClient.deleteSchedulePlan(planGuid);
                }
            } finally {
                developer.signOutAndDeleteUser();    
            }
        }
    }
    
    @Test
    public void schedulePlanIsCorrect() throws Exception {
        SchedulePlanClient schedulePlanClient = developer.getSession().getSchedulePlanClient();
        planGuid = schedulePlanClient.createSchedulePlan(Tests.getSimpleSchedulePlan()).getGuid();
        
        SchedulePlan originalPlan = Tests.getSimpleSchedulePlan();
        SchedulePlan plan = developer.getSession().getSchedulePlanClient().getSchedulePlan(planGuid);
        // Fields that are set on the server.
        originalPlan.setGuid(plan.getGuid());
        originalPlan.setModifiedOn(plan.getModifiedOn());
        originalPlan.setVersion(plan.getVersion());

        originalPlan.setGuid(plan.getGuid());
        originalPlan.setModifiedOn(plan.getModifiedOn());
        Tests.getActivitiesFromSimpleStrategy(originalPlan).set(0, Tests.getActivityFromSimpleStrategy(plan));
        
        assertEquals(originalPlan, plan);
    }

    @Test
    public void canRetrieveSchedulesForAUser() throws Exception {
        SchedulePlanClient schedulePlanClient = developer.getSession().getSchedulePlanClient();
        planGuid = schedulePlanClient.createSchedulePlan(Tests.getABTestSchedulePlan()).getGuid();

        final UserClient userClient = user.getSession().getUserClient();
        
        List<Schedule> schedules = userClient.getSchedules().getItems();
        assertEquals("There should be one schedule for this user", 1, schedules.size());
    }
    
    @Test
    @SuppressWarnings("deprecation")
    public void persistentSchedulePlanMarkedPersistent() throws Exception {
        SchedulePlan plan = Tests.getPersistentSchedulePlan();
        SchedulePlanClient schedulePlanClient = developer.getSession().getSchedulePlanClient();
        
        planGuid = schedulePlanClient.createSchedulePlan(plan).getGuid();

        plan = schedulePlanClient.getSchedulePlan(planGuid);
        Schedule schedule = Tests.getSimpleSchedule(plan);
        
        assertEquals(true, schedule.getPersistent());
        assertEquals(true, schedule.getActivities().get(0).isPersistentlyRescheduledBy(schedule));
    }
    
    @Test
    @SuppressWarnings("deprecation")
    public void simpleSchedulePlanNotMarkedPersistent() throws Exception {
        SchedulePlan plan = Tests.getSimpleSchedulePlan();
        SchedulePlanClient schedulePlanClient = developer.getSession().getSchedulePlanClient();

        planGuid = schedulePlanClient.createSchedulePlan(plan).getGuid();

        plan = schedulePlanClient.getSchedulePlan(planGuid);
        Schedule schedule = Tests.getSimpleSchedule(plan);
        
        assertEquals(false, schedule.getPersistent());
        assertEquals(false, schedule.getActivities().get(0).isPersistentlyRescheduledBy(schedule));
    }
    
    @Test
    public void criteriaBasedScheduleIsFilteredForUser() {
        SchedulePlan plan = new SchedulePlan();
        plan.setLabel("Criteria plan");

        Criteria criteria1 = new Criteria();
        criteria1.getMinAppVersions().put(OperatingSystem.ANDROID, 0);
        criteria1.getMaxAppVersions().put(OperatingSystem.ANDROID, 10);
        
        Criteria criteria2 = new Criteria();
        criteria2.getMinAppVersions().put(OperatingSystem.ANDROID, 11);
        
        Schedule schedule1 = new Schedule();
        schedule1.setLabel("Schedule 1");
        schedule1.setScheduleType(ScheduleType.ONCE);
        schedule1.addActivity(new Activity("Activity 1", "", new TaskReference("task:AAA")));
        
        Schedule schedule2 = new Schedule();
        schedule2.setLabel("Schedule 2");
        schedule2.setScheduleType(ScheduleType.ONCE);
        schedule2.addActivity(new Activity("Activity 2", "", new TaskReference("task:BBB")));
        
        ScheduleCriteria scheduleCriteria1 = new ScheduleCriteria(schedule1, criteria1);
        ScheduleCriteria scheduleCriteria2 = new ScheduleCriteria(schedule2, criteria2);
        
        CriteriaScheduleStrategy strategy = new CriteriaScheduleStrategy();
        strategy.addCriteria(scheduleCriteria1);
        strategy.addCriteria(scheduleCriteria2);
        plan.setStrategy(strategy);
        
        user.signOut();        
        planGuid = developer.getSession().getSchedulePlanClient().createSchedulePlan(plan).getGuid();
        
        // Manipulate the User-Agent string and see scheduled activity change accordingly
        ClientProvider.setClientInfo(getClientInfoWithVersion(OperatingSystem.ANDROID, 2));
        user.signInAgain();
        activitiesShouldContainTask("Activity 1");
        
        user.signOut();
        ClientProvider.setClientInfo(getClientInfoWithVersion(OperatingSystem.ANDROID, 12));
        user.signInAgain();
        activitiesShouldContainTask("Activity 2");

        // In this final test no matching occurs, but this simply means that the first schedule will match and be 
        // returned (not that all of the schedules in the plan will be returned, that's not how a plan works).
        user.signOut();
        ClientProvider.setClientInfo(getClientInfoWithVersion(OperatingSystem.IOS, 12));
        user.signInAgain();
        activitiesShouldContainTask("Activity 1");
    }

    private void activitiesShouldContainTask(String activityLabel) {
        ResourceList<ScheduledActivity> activities = user.getSession().getUserClient().getScheduledActivities(1, DateTimeZone.UTC, null);
        assertEquals(1, activities.getTotal());
        assertEquals(activityLabel, activities.getItems().get(0).getActivity().getLabel());
    }
    
    private ClientInfo getClientInfoWithVersion(OperatingSystem os, Integer version) {
        return new ClientInfo.Builder().withAppName("app").withAppVersion(version).withOsName(os.getOsName())
                .withDevice("Integration Tests").withOsVersion("2.0.0").build();
    }    
}
