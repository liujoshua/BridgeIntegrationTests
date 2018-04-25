package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;

import java.util.List;
import java.util.stream.Collectors;

import org.joda.time.DateTime;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.rest.api.SchedulesApi;
import org.sagebionetworks.bridge.rest.model.Activity;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.Schedule;
import org.sagebionetworks.bridge.rest.model.SchedulePlan;
import org.sagebionetworks.bridge.rest.model.ScheduleType;
import org.sagebionetworks.bridge.rest.model.ScheduledActivity;
import org.sagebionetworks.bridge.rest.model.ScheduledActivityList;
import org.sagebionetworks.bridge.rest.model.SimpleScheduleStrategy;
import org.sagebionetworks.bridge.rest.model.TaskReference;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;

import com.google.common.collect.Lists;

public class PersistentActivityTest {

    private String planGuid;
    
    private TestUser user;
    private TestUser developer;
    
    @Before
    public void before() throws Exception {
        user = new TestUserHelper.Builder(ScheduleTest.class).withConsentUser(true)
                .createAndSignInUser();

        developer = new TestUserHelper.Builder(ScheduleTest.class).withConsentUser(true)
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
    public void persistentActivityCanBeFinishedMultipleTimesInDay() throws Exception {
        String activityLabel1 = Tests.randomIdentifier(this.getClass());
        Schedule schedule = new Schedule();
        schedule.setLabel("Schedule 1");
        schedule.setScheduleType(ScheduleType.PERSISTENT);
        schedule.setActivities(taskActivity(activityLabel1, "task:AAA"));
        
        SimpleScheduleStrategy strategy = new SimpleScheduleStrategy();
        strategy.setSchedule(schedule);
        
        SchedulePlan plan = new SchedulePlan();
        plan.setLabel("Criteria plan");
        plan.setStrategy(strategy);
        
        SchedulesApi schedulesApi = developer.getClient(SchedulesApi.class);
        planGuid = schedulesApi.createSchedulePlan(plan).execute().body().getGuid();
        
        ForConsentedUsersApi usersApi = user.getClient(ForConsentedUsersApi.class);
        ScheduledActivityList activities = usersApi.getScheduledActivities("-07:00", 2, null).execute().body();
        List<ScheduledActivity> filteredActivityList = findActivities(activities, activityLabel1);
        assertEquals(1, filteredActivityList.size());
        
        ScheduledActivity activity = filteredActivityList.get(0);
        activity.setStartedOn(DateTime.now());
        activity.setFinishedOn(DateTime.now());
        
        usersApi.updateScheduledActivities(filteredActivityList).execute();
        activities = usersApi.getScheduledActivities("-07:00", 2, null).execute().body();
        filteredActivityList = findActivities(activities, activityLabel1);
        assertEquals(1, filteredActivityList.size());
    }
    
    private List<Activity> taskActivity(String label, String taskIdentifier) {
        TaskReference ref = new TaskReference();
        ref.setIdentifier(taskIdentifier);
        
        Activity activity = new Activity();
        activity.setLabel(label);
        activity.setTask(ref);
        return Lists.newArrayList(activity);
    }

    private List<ScheduledActivity> findActivities(ScheduledActivityList allActivityList, String label) {
        return allActivityList.getItems().stream()
                .filter(oneActivity -> label.equals(oneActivity.getActivity().getLabel()))
                .collect(Collectors.toList());
    }
}
