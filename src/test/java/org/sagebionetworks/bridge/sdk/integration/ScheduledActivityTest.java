package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.stream.Collectors;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.sagebionetworks.bridge.sdk.ClientProvider;
import org.sagebionetworks.bridge.sdk.Roles;
import org.sagebionetworks.bridge.sdk.SchedulePlanClient;
import org.sagebionetworks.bridge.sdk.UserClient;
import org.sagebionetworks.bridge.sdk.integration.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.sdk.models.ResourceList;
import org.sagebionetworks.bridge.sdk.models.schedules.Activity;
import org.sagebionetworks.bridge.sdk.models.schedules.ActivityType;
import org.sagebionetworks.bridge.sdk.models.schedules.Schedule;
import org.sagebionetworks.bridge.sdk.models.schedules.SchedulePlan;
import org.sagebionetworks.bridge.sdk.models.schedules.ScheduleType;
import org.sagebionetworks.bridge.sdk.models.schedules.ScheduledActivity;
import org.sagebionetworks.bridge.sdk.models.schedules.TaskReference;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;

import org.sagebionetworks.bridge.sdk.models.schedules.ScheduledActivityStatus;

@Category(IntegrationSmokeTest.class)
public class ScheduledActivityTest {
    
    private TestUser user;
    private TestUser developer;
    private SchedulePlanClient schedulePlanClient;
    private UserClient userClient;

    @Before
    public void before() {
        developer = TestUserHelper.createAndSignInUser(ScheduledActivityTest.class, true, Roles.DEVELOPER);
        user = TestUserHelper.createAndSignInUser(ScheduledActivityTest.class, true);

        schedulePlanClient = developer.getSession().getSchedulePlanClient();
        userClient = user.getSession().getUserClient();
        
        Schedule schedule = new Schedule();
        schedule.setLabel("Schedule 1");
        schedule.setDelay("P3D");
        schedule.setScheduleType(ScheduleType.ONCE);
        schedule.addTimes("10:00");
        schedule.addActivity(new Activity("Activity 1", "", new TaskReference("task:AAA")));

        SchedulePlan plan = new SchedulePlan();
        plan.setLabel("Schedule plan 1");
        plan.setSchedule(schedule);
        schedulePlanClient.createSchedulePlan(plan);
        
        // Add a schedule plan in the future... this should not effect any tests, *until* we request
        // a minimum number of tasks, which will retrieve this.
        schedule = new Schedule();
        schedule.setLabel("Schedule 2");
        schedule.setDelay("P1M");
        schedule.setInterval("P1M");
        schedule.setExpires("P3W");
        schedule.setScheduleType(ScheduleType.RECURRING);
        schedule.addTimes("10:00");
        schedule.addActivity(new Activity("Activity 2", "", new TaskReference("task:BBB")));
        
        plan = new SchedulePlan();
        plan.setLabel("Schedule plan 2");
        plan.setSchedule(schedule);
        schedulePlanClient.createSchedulePlan(plan);
    }

    @After
    public void after() {
        ClientProvider.setClientInfo(Tests.TEST_CLIENT_INFO);
        try {
            for (SchedulePlan plan : schedulePlanClient.getSchedulePlans()) {
                schedulePlanClient.deleteSchedulePlan(plan.getGuid());
            }
        } finally {
            if (developer != null) {
                developer.signOutAndDeleteUser();
            }
            if (user != null) {
                user.signOutAndDeleteUser();
            }
        }
    }
    
    @Test
    public void createSchedulePlanGetScheduledActivities() {
        ResourceList<ScheduledActivity> scheduledActivities = userClient.getScheduledActivities(4, DateTimeZone.getDefault(), null);
        
        ScheduledActivity schActivity = scheduledActivities.get(0);
        assertEquals(ScheduledActivityStatus.SCHEDULED, schActivity.getStatus());
        assertNotNull(schActivity.getScheduledOn());
        assertNull(schActivity.getExpiresOn());
        
        Activity activity = schActivity.getActivity();
        assertEquals(ActivityType.TASK, activity.getActivityType());
        assertEquals("Activity 1", activity.getLabel());
        assertEquals("task:AAA", activity.getTask().getIdentifier());

        schActivity.setStartedOn(DateTime.now());
        userClient.updateScheduledActivities(scheduledActivities.getItems());
        scheduledActivities = userClient.getScheduledActivities(3, DateTimeZone.getDefault(), null);
        assertEquals(1, scheduledActivities.getTotal());
        assertEquals(ScheduledActivityStatus.STARTED, schActivity.getStatus());
        
        schActivity = scheduledActivities.get(0);
        schActivity.setFinishedOn(DateTime.now());
        userClient.updateScheduledActivities(scheduledActivities.getItems());
        scheduledActivities = userClient.getScheduledActivities(3, DateTimeZone.getDefault(), null);
        assertEquals(0, scheduledActivities.getTotal()); // no activities == finished
    }
    
    @Test
    public void getScheduledActivitiesWithMinimumActivityValue() {
        ResourceList<ScheduledActivity> scheduledActivities = userClient.getScheduledActivities(4, DateTimeZone.getDefault(), 2);
        
        Multiset<String> idCounts = getMultiset(scheduledActivities);
        assertEquals(1, idCounts.count("task:AAA"));
        assertEquals(2, idCounts.count("task:BBB"));
        
        scheduledActivities = userClient.getScheduledActivities(4, DateTimeZone.getDefault(), 0);
        idCounts = getMultiset(scheduledActivities);
        assertEquals(1, idCounts.count("task:AAA"));
        assertEquals(0, idCounts.count("task:BBB"));
        
        scheduledActivities = userClient.getScheduledActivities(4, DateTimeZone.getDefault(), 5);
        idCounts = getMultiset(scheduledActivities);
        assertEquals(1, idCounts.count("task:AAA"));
        assertEquals(5, idCounts.count("task:BBB"));
    }
    
    private Multiset<String> getMultiset(ResourceList<ScheduledActivity> scheduledActivities) {
        return HashMultiset.create(scheduledActivities.getItems().stream()
                .map((act) -> act.getActivity().getTask().getIdentifier())
                .collect(Collectors.toList()));
    }
    
}
