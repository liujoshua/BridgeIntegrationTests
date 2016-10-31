package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;

import java.util.List;

import org.codehaus.plexus.util.ReflectionUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.sdk.integration.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.sdk.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.sdk.rest.api.SchedulesApi;
import org.sagebionetworks.bridge.sdk.rest.model.Activity;
import org.sagebionetworks.bridge.sdk.rest.model.ClientInfo;
import org.sagebionetworks.bridge.sdk.rest.model.Criteria;
import org.sagebionetworks.bridge.sdk.rest.model.CriteriaScheduleStrategy;
import org.sagebionetworks.bridge.sdk.rest.model.ResourceListScheduledActivity;
import org.sagebionetworks.bridge.sdk.rest.model.Role;
import org.sagebionetworks.bridge.sdk.rest.model.Schedule;
import org.sagebionetworks.bridge.sdk.rest.model.ScheduleCriteria;
import org.sagebionetworks.bridge.sdk.rest.model.SchedulePlan;
import org.sagebionetworks.bridge.sdk.rest.model.ScheduleType;
import org.sagebionetworks.bridge.sdk.rest.model.TaskReference;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;

public class ScheduleTest {

    private String planGuid;
    
    private TestUser user;
    private TestUser developer;
    
    @Before
    public void before() throws Exception {
        ClientInfo clientInfo = getClientInfo(Tests.APP_NAME, 3);
        
        user = new TestUserHelper.Builder(ScheduleTest.class).withClientInfo(clientInfo).withConsentUser(true)
                .createAndSignInUser();

        developer = new TestUserHelper.Builder(ScheduleTest.class).withClientInfo(clientInfo).withConsentUser(true)
                .withRoles(Role.DEVELOPER).createAndSignInUser();
    }
    
    @After
    public void after() throws Exception {
        if (user != null) {
            user.signOutAndDeleteUser();    
        }
        if (developer != null) {
            try {
                if (planGuid != null) {
                    SchedulesApi schedulesApi = developer.getClient(SchedulesApi.class);
                    schedulesApi.deleteSchedulePlan(planGuid).execute();
                }
            } finally {
                developer.signOutAndDeleteUser();    
            }
        }
    }
    
    @Test
    public void schedulePlanIsCorrect() throws Exception {
        SchedulesApi schedulesApi = developer.getClient(SchedulesApi.class);
        planGuid = schedulesApi.createSchedulePlan(Tests.getSimpleSchedulePlan()).execute().body().getGuid();
        
        SchedulePlan originalPlan = Tests.getSimpleSchedulePlan();
        SchedulePlan plan = schedulesApi.getSchedulePlan(planGuid).execute().body();

        // Fields that are set on the server.
        originalPlan.setGuid(plan.getGuid());
        ReflectionUtils.setVariableValueInObject(originalPlan, "modifiedOn", plan.getModifiedOn());
        originalPlan.setVersion(plan.getVersion());
        
        Tests.getActivitiesFromSimpleStrategy(originalPlan).set(0, Tests.getActivityFromSimpleStrategy(plan));
        
        ReflectionUtils.setVariableValueInObject(originalPlan, "type", SchedulePlan.TypeEnum.SCHEDULEPLAN);
        
        Schedule originalSchedule = Tests.getSimpleSchedule(originalPlan);
        Schedule updatedSchedule = Tests.getSimpleSchedule(plan);
        originalSchedule.setPersistent(updatedSchedule.getPersistent());
        ReflectionUtils.setVariableValueInObject(originalSchedule, "type", Schedule.TypeEnum.SCHEDULE);
        
        assertEquals(originalPlan, plan);
    }

    @Test
    public void canRetrieveSchedulesForAUser() throws Exception {
        SchedulesApi schedulesApi = developer.getClient(SchedulesApi.class);
        planGuid = schedulesApi.createSchedulePlan(Tests.getABTestSchedulePlan()).execute().body().getGuid();

        ForConsentedUsersApi usersApi = user.getClient(ForConsentedUsersApi.class);
        
        List<Schedule> schedules = usersApi.getSchedules().execute().body().getItems();
        assertEquals("There should be one schedule for this user", 1, schedules.size());
    }
    
    @Test
    @SuppressWarnings("deprecation")
    public void persistentSchedulePlanMarkedPersistent() throws Exception {
        SchedulePlan plan = Tests.getPersistentSchedulePlan();
        SchedulesApi schedulesApi = developer.getClient(SchedulesApi.class);
        
        planGuid = schedulesApi.createSchedulePlan(plan).execute().body().getGuid();

        plan = schedulesApi.getSchedulePlan(planGuid).execute().body();
        Schedule schedule = Tests.getSimpleSchedule(plan);
        
        assertEquals(true, schedule.getPersistent());
    }
    
    @Test
    @SuppressWarnings("deprecation")
    public void simpleSchedulePlanNotMarkedPersistent() throws Exception {
        SchedulePlan plan = Tests.getSimpleSchedulePlan();
        SchedulesApi schedulesApi = developer.getClient(SchedulesApi.class);

        planGuid = schedulesApi.createSchedulePlan(plan).execute().body().getGuid();

        plan = schedulesApi.getSchedulePlan(planGuid).execute().body();
        Schedule schedule = Tests.getSimpleSchedule(plan);
        
        assertEquals(false, schedule.getPersistent());
    }
    
    @Test
    public void criteriaBasedScheduleIsFilteredForUser() throws Exception {
        SchedulePlan plan = new SchedulePlan();
        plan.setLabel("Criteria plan");

        Schedule schedule1 = new Schedule();
        schedule1.setLabel("Schedule 1");
        schedule1.setScheduleType(ScheduleType.ONCE);
        schedule1.setActivities(taskActivity("Activity 1", "task:AAA"));
        
        Schedule schedule2 = new Schedule();
        schedule2.setLabel("Schedule 2");
        schedule2.setScheduleType(ScheduleType.ONCE);
        schedule2.setActivities(taskActivity("Activity 2", "task:BBB"));
        
        Criteria criteria1 = new Criteria();
        criteria1.setMinAppVersions(new ImmutableMap.Builder<String,Integer>().put("Android",0).build());
        criteria1.setMaxAppVersions(new ImmutableMap.Builder<String,Integer>().put("Android",10).build());
        
        ScheduleCriteria scheduleCriteria1 = new ScheduleCriteria();
        scheduleCriteria1.setCriteria(criteria1);
        scheduleCriteria1.setSchedule(schedule1);
        
        Criteria criteria2 = new Criteria();
        criteria2.setMinAppVersions(new ImmutableMap.Builder<String,Integer>().put("Android",11).build());
        
        ScheduleCriteria scheduleCriteria2 = new ScheduleCriteria();
        scheduleCriteria2.setCriteria(criteria2);
        scheduleCriteria2.setSchedule(schedule2);
        
        CriteriaScheduleStrategy strategy = new CriteriaScheduleStrategy();
        strategy.setScheduleCriteria(Lists.newArrayList(scheduleCriteria1, scheduleCriteria2));
        strategy.setType("CriteriaScheduleStrategy");
        plan.setStrategy(strategy);
        
        user.signOut();        
        SchedulesApi schedulesApi = developer.getClient(SchedulesApi.class);
        planGuid = schedulesApi.createSchedulePlan(plan).execute().body().getGuid();
        
        // Manipulate the User-Agent string and see scheduled activity change accordingly
        user.setClientInfo(getClientInfoWithVersion("Android", 2));
        user.signInAgain();
        activitiesShouldContainTask("Activity 1");
        
        user.signOut();
        user.setClientInfo(getClientInfoWithVersion("Android", 12));
        user.signInAgain();
        activitiesShouldContainTask("Activity 2");

        // In this final test no matching occurs, but this simply means that the first schedule will match and be 
        // returned (not that all of the schedules in the plan will be returned, that's not how a plan works).
        user.signOut();
        user.setClientInfo(getClientInfoWithVersion("iPhone OS", 12));
        user.signInAgain();
        activitiesShouldContainTask("Activity 1");
    }
    
    private ClientInfo getClientInfo(String appName, Integer appVersion) {
        ClientInfo info = new ClientInfo();
        info.setAppName(appName);
        info.setAppVersion(appVersion);
        info.setDeviceName("Integration Tests");
        return info;
    }
    
    private List<Activity> taskActivity(String label, String taskIdentifier) {
        TaskReference ref = new TaskReference();
        ref.setIdentifier(taskIdentifier);
        
        Activity activity = new Activity();
        activity.setLabel(label);
        activity.setTask(ref);
        return Lists.newArrayList(activity);
    }

    private void activitiesShouldContainTask(String activityLabel) throws Exception {
        ForConsentedUsersApi usersApi = user.getClient(ForConsentedUsersApi.class);
        ResourceListScheduledActivity activities = usersApi.getScheduledActivities("+00:00", 1, null).execute().body();
        assertEquals((Integer)1, activities.getTotal());
        assertEquals(activityLabel, activities.getItems().get(0).getActivity().getLabel());
    }
    
    private ClientInfo getClientInfoWithVersion(String osName, Integer version) {
        ClientInfo info = new ClientInfo();
        info.setAppName("app");
        info.setAppVersion(version);
        info.setOsName(osName);
        info.setDeviceName("Integrate Tests");
        info.setOsVersion("2.0.0");
        return info;
    }    
}
