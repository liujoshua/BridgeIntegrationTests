package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.commons.lang3.RandomStringUtils;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.Lists;
import com.google.common.collect.Multiset;

import org.sagebionetworks.bridge.sdk.integration.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.rest.RestUtils;
import org.sagebionetworks.bridge.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.rest.api.ParticipantsApi;
import org.sagebionetworks.bridge.rest.api.SchedulesApi;
import org.sagebionetworks.bridge.rest.model.Activity;
import org.sagebionetworks.bridge.rest.model.ActivityType;
import org.sagebionetworks.bridge.rest.model.ForwardCursorScheduledActivityList;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.Schedule;
import org.sagebionetworks.bridge.rest.model.SchedulePlan;
import org.sagebionetworks.bridge.rest.model.ScheduleStatus;
import org.sagebionetworks.bridge.rest.model.ScheduleType;
import org.sagebionetworks.bridge.rest.model.ScheduledActivity;
import org.sagebionetworks.bridge.rest.model.ScheduledActivityList;
import org.sagebionetworks.bridge.rest.model.ScheduledActivityListV4;
import org.sagebionetworks.bridge.rest.model.SimpleScheduleStrategy;
import org.sagebionetworks.bridge.rest.model.TaskReference;

@Category(IntegrationSmokeTest.class)
@SuppressWarnings("ConstantConditions")
public class ScheduledActivityTest {
    
    public static class ClientData {
        final String name;
        final boolean enabled;
        final int count;
        public ClientData(String name, boolean enabled, int count) {
            this.name = name;
            this.enabled = enabled;
            this.count = count;
        }
        public String getName() {
            return name;
        }
        public boolean getEnabled() {
            return enabled;
        }
        public int getCount() {
            return count;
        }
    }

    private String monthlyActivityLabel;
    private String oneTimeActivityLabel;
    private TestUser researcher;
    private TestUser user;
    private TestUser developer;
    private SchedulesApi schedulePlansApi;
    private List<String> schedulePlanGuidList;
    private ForConsentedUsersApi usersApi;

    @Before
    public void before() throws Exception {
        schedulePlanGuidList = new ArrayList<>();

        researcher = TestUserHelper.createAndSignInUser(ScheduledActivityTest.class, true, Role.RESEARCHER);
        developer = TestUserHelper.createAndSignInUser(ScheduledActivityTest.class, true, Role.DEVELOPER);
        user = TestUserHelper.createAndSignInUser(ScheduledActivityTest.class, true);

        schedulePlansApi = developer.getClient(SchedulesApi.class);
        usersApi = user.getClient(ForConsentedUsersApi.class);
        
        String planGuid = oneTimeScheduleAfter3Days();
        schedulePlanGuidList.add(planGuid);
        
        planGuid = monthlyAfterOneMonthSchedule();
        schedulePlanGuidList.add(planGuid);
    }

    private String monthlyAfterOneMonthSchedule() throws IOException {
        monthlyActivityLabel = "monthly-activity-" + RandomStringUtils.randomAlphabetic(4);

        String planGuid;
        Schedule schedule = new Schedule();
        schedule.setLabel("Schedule 2");
        schedule.setDelay("P1M");
        schedule.setInterval("P1M");
        schedule.setExpires("P3W");
        schedule.setScheduleType(ScheduleType.RECURRING);
        schedule.setTimes(Lists.newArrayList("10:00"));
        
        TaskReference taskReference = new TaskReference();
        taskReference.setIdentifier("task:BBB");
        
        Activity activity = new Activity();
        activity.setLabel(monthlyActivityLabel);
        activity.setTask(taskReference);
        schedule.setActivities(Lists.newArrayList(activity));
        
        SimpleScheduleStrategy strategy = new SimpleScheduleStrategy();
        strategy.setSchedule(schedule);
        strategy.setType("SimpleScheduleStrategy");
        
        SchedulePlan plan = new SchedulePlan();
        plan.setLabel("Schedule plan 2");
        plan.setStrategy(strategy);
        planGuid = schedulePlansApi.createSchedulePlan(plan).execute().body().getGuid();
        return planGuid;
    }

    private String oneTimeScheduleAfter3Days() throws IOException {
        oneTimeActivityLabel = "one-time-activity-" + RandomStringUtils.randomAlphabetic(4);

        Schedule schedule = new Schedule();
        schedule.setLabel("Schedule 1");
        schedule.setDelay("P3D");
        schedule.setScheduleType(ScheduleType.ONCE);
        schedule.setTimes(Lists.newArrayList("10:00"));
        
        TaskReference taskReference = new TaskReference();
        taskReference.setIdentifier("task:AAA");
        
        Activity activity = new Activity();
        activity.setLabel(oneTimeActivityLabel);
        activity.setTask(taskReference);
        
        schedule.setActivities(Lists.newArrayList(activity));

        SimpleScheduleStrategy strategy = new SimpleScheduleStrategy();
        strategy.setSchedule(schedule);
        strategy.setType("SimpleScheduleStrategy");
        
        SchedulePlan plan = new SchedulePlan();
        plan.setLabel("Schedule plan 1");
        plan.setStrategy(strategy);
        String planGuid = schedulePlansApi.createSchedulePlan(plan).execute().body().getGuid();
        return planGuid;
    }

    @After
    public void after() throws Exception {
        try {
            for (String oneSchedulePlanGuid : schedulePlanGuidList) {
                schedulePlansApi.deleteSchedulePlan(oneSchedulePlanGuid).execute();
            }
        } finally {
            if (researcher != null) {
                researcher.signOutAndDeleteUser();
            }
            if (developer != null) {
                developer.signOutAndDeleteUser();
            }
            if (user != null) {
                user.signOutAndDeleteUser();
            }
        }
    }
    
    @Test
    public void createSchedulePlanGetScheduledActivities() throws Exception {
        // Get scheduled activities. Validate basic properties.
        ScheduledActivityList scheduledActivities = usersApi.getScheduledActivities("+00:00", 4, 0).execute().body();
        ScheduledActivity schActivity = findOneTimeActivity(scheduledActivities.getItems());
        assertNotNull(schActivity);
        assertEquals(ScheduleStatus.SCHEDULED, schActivity.getStatus());
        assertNotNull(schActivity.getScheduledOn());
        assertNull(schActivity.getExpiresOn());

        Activity activity = schActivity.getActivity();
        assertEquals(ActivityType.TASK, activity.getActivityType());
        assertEquals(oneTimeActivityLabel, activity.getLabel());
        assertEquals("task:AAA", activity.getTask().getIdentifier());

        DateTime startDateTime = DateTime.now(DateTimeZone.UTC).minusDays(10);
        DateTime endDateTime = DateTime.now(DateTimeZone.UTC).plusDays(10);

        // You can see this activity in history...
        ForwardCursorScheduledActivityList list = usersApi.getActivityHistory(activity.getGuid(),
                startDateTime, endDateTime, null, 5).execute().body();

        ScheduledActivity retrievedFromHistory = list.getItems().get(0);
        Tests.setVariableValueInObject(retrievedFromHistory, "schedulePlanGuid", schActivity.getSchedulePlanGuid());
        assertEquals(schActivity, retrievedFromHistory);
        
        // You can see this activity in the researcher API
        ParticipantsApi participantsApi = researcher.getClient(ParticipantsApi.class);
        list = participantsApi.getParticipantActivityHistory(user.getSession().getId(), activity.getGuid(),
                startDateTime, endDateTime, null, 5).execute().body();
        
        retrievedFromHistory = list.getItems().get(0);
        Tests.setVariableValueInObject(retrievedFromHistory, "schedulePlanGuid", schActivity.getSchedulePlanGuid());
        assertEquals(schActivity, retrievedFromHistory);
        assertFalse(list.getHasNext());
        assertEquals(startDateTime, list.getRequestParams().getScheduledOnStart());
        assertEquals(endDateTime, list.getRequestParams().getScheduledOnEnd());
        
        // If we ask for a date range that doesn't include it, it is not returned.
        list = participantsApi.getParticipantActivityHistory(user.getSession().getId(), activity.getGuid(),
                DateTime.now().plusDays(10), DateTime.now().plusDays(12), null, 5).execute().body();
        assertTrue(list.getItems().isEmpty());
        
        // Start the activity.
        schActivity.setStartedOn(DateTime.now());
        schActivity.setClientData(new ClientData("Test Name", true, 100));
        usersApi.updateScheduledActivities(scheduledActivities.getItems()).execute();

        // Get activities back and validate that it's started.
        scheduledActivities = usersApi.getScheduledActivities("+00:00", 3, null).execute().body();
        schActivity = findOneTimeActivity(scheduledActivities.getItems());
        assertNotNull(schActivity);
        ClientData clientData = RestUtils.toType(schActivity.getClientData(), ClientData.class);
        assertEquals("Test Name", clientData.getName());
        assertTrue(clientData.getEnabled());
        assertEquals(100, clientData.getCount());
        assertEquals(ScheduleStatus.STARTED, schActivity.getStatus());

        // Finish the activity, save some data
        schActivity.setFinishedOn(DateTime.now());
        usersApi.updateScheduledActivities(scheduledActivities.getItems()).execute();

        // Get activities back. Verify the activity is not there.
        scheduledActivities = usersApi.getScheduledActivities("+00:00", 3, null).execute().body();
        schActivity = findOneTimeActivity(scheduledActivities.getItems());
        assertNull(schActivity);
        
        // But the activities continue to be in the history APIs
        list = usersApi.getActivityHistory(activity.getGuid(),
                startDateTime, endDateTime, null, 5).execute().body();
        assertFalse(list.getItems().isEmpty());
        assertNotNull(list.getItems().get(0).getFinishedOn());
        
        list = participantsApi.getParticipantActivityHistory(user.getSession().getId(), activity.getGuid(),
                startDateTime, endDateTime, null, 5).execute().body();
        assertFalse(list.getItems().isEmpty());
        assertNotNull(list.getItems().get(0).getFinishedOn());
    }

    @Test
    public void createSchedulePlanGetScheduledActivitiesV4() throws Exception {
        // Get scheduled activities. Validate basic properties.
        DateTimeZone zone = DateTimeZone.forOffsetHours(4);
        DateTime startsOn = DateTime.now(zone);
        DateTime endsOn = startsOn.plusDays(4);
        
        ScheduledActivityListV4 scheduledActivities = usersApi.getScheduledActivitiesByDateRange(startsOn, endsOn).execute().body();
        ScheduledActivity schActivity = findOneTimeActivity(scheduledActivities.getItems());
        assertNotNull(schActivity);
        assertEquals(ScheduleStatus.SCHEDULED, schActivity.getStatus());
        assertNotNull(schActivity.getScheduledOn());
        assertNull(schActivity.getExpiresOn());

        Activity activity = schActivity.getActivity();
        assertEquals(ActivityType.TASK, activity.getActivityType());
        assertEquals(oneTimeActivityLabel, activity.getLabel());
        assertEquals("task:AAA", activity.getTask().getIdentifier());

        // Historical items should come back with the same time zone as get() method, and so activities should
        // be strictly equal.
        DateTime startDateTime = startsOn.minusDays(10);
        DateTime endDateTime = startsOn.plusDays(10);

        // You can see this activity in history...
        ForwardCursorScheduledActivityList list = usersApi.getActivityHistory(activity.getGuid(),
                startDateTime, endDateTime, null, 5).execute().body();

        ScheduledActivity retrievedFromHistory = list.getItems().get(0);
        Tests.setVariableValueInObject(retrievedFromHistory, "schedulePlanGuid", schActivity.getSchedulePlanGuid());
        assertEquals(schActivity, retrievedFromHistory);
        
        // You can see this activity in the researcher API
        ParticipantsApi participantsApi = researcher.getClient(ParticipantsApi.class);
        list = participantsApi.getParticipantActivityHistory(user.getSession().getId(), activity.getGuid(),
                startDateTime, endDateTime, null, 5).execute().body();
        
        retrievedFromHistory = list.getItems().get(0);
        Tests.setVariableValueInObject(retrievedFromHistory, "schedulePlanGuid", schActivity.getSchedulePlanGuid());
        assertEquals(schActivity, retrievedFromHistory);
        assertFalse(list.getHasNext());
        assertEquals(startDateTime, list.getRequestParams().getScheduledOnStart());
        assertEquals(endDateTime, list.getRequestParams().getScheduledOnEnd());
        
        // If we ask for a date range that doesn't include it, it is not returned.
        list = participantsApi.getParticipantActivityHistory(user.getSession().getId(), activity.getGuid(),
                DateTime.now().plusDays(10), DateTime.now().plusDays(12), null, 5).execute().body();
        assertTrue(list.getItems().isEmpty());
        
        // Start the activity.
        schActivity.setStartedOn(DateTime.now());
        schActivity.setClientData(new ClientData("Test Name", true, 100));
        usersApi.updateScheduledActivities(scheduledActivities.getItems()).execute();

        // Get activities back and validate that it's started.
        // It's delayed by three days, so ask for four
        scheduledActivities = usersApi.getScheduledActivitiesByDateRange(startsOn, endsOn).execute().body();
        schActivity = findOneTimeActivity(scheduledActivities.getItems());
        assertNotNull(schActivity);
        ClientData clientData = RestUtils.toType(schActivity.getClientData(), ClientData.class);
        assertEquals("Test Name", clientData.getName());
        assertTrue(clientData.getEnabled());
        assertEquals(100, clientData.getCount());
        assertEquals(ScheduleStatus.STARTED, schActivity.getStatus());

        // Finish the activity, save some data
        schActivity.setFinishedOn(DateTime.now());
        usersApi.updateScheduledActivities(scheduledActivities.getItems()).execute();

        // Get activities back. Verify the activity is there and it is finished
        scheduledActivities = usersApi.getScheduledActivitiesByDateRange(startsOn, endsOn).execute().body();
        schActivity = findOneTimeActivity(scheduledActivities.getItems());
        assertNotNull(schActivity.getStartedOn());
        assertNotNull(schActivity.getFinishedOn());
        
        // The activities also continue to be in the history APIs
        list = usersApi.getActivityHistory(activity.getGuid(), startDateTime, endDateTime, null, 5).execute().body();
        assertFalse(list.getItems().isEmpty());
        assertNotNull(list.getItems().get(0).getFinishedOn());
        
        list = participantsApi.getParticipantActivityHistory(user.getSession().getId(), activity.getGuid(),
                startDateTime, endDateTime, null, 5).execute().body();
        assertFalse(list.getItems().isEmpty());
        assertNotNull(list.getItems().get(0).getFinishedOn());
    }
    
    @Test
    public void getScheduledActivitiesWithMinimumActivityValue() throws Exception {
        ScheduledActivityList scheduledActivities = usersApi.getScheduledActivities("+00:00", 4, 2).execute().body();
        
        Multiset<String> idCounts = getActivityLabels(scheduledActivities);
        assertEquals(1, idCounts.count(oneTimeActivityLabel));
        assertEquals(2, idCounts.count(monthlyActivityLabel));
        
        scheduledActivities = usersApi.getScheduledActivities("+00:00", 4, 0).execute().body();
        idCounts = getActivityLabels(scheduledActivities);
        assertEquals(1, idCounts.count(oneTimeActivityLabel));
        assertEquals(0, idCounts.count(monthlyActivityLabel));
        
        scheduledActivities = usersApi.getScheduledActivities("+00:00", 4, 5).execute().body();
        idCounts = getActivityLabels(scheduledActivities);
        assertEquals(1, idCounts.count(oneTimeActivityLabel));
        assertEquals(5, idCounts.count(monthlyActivityLabel));
    }

    // api study may have other schedule plans in it with other scheduled activities. To prevent tests from
    // conflicting, look for the activity with oneTimeActivityLabel.
    private ScheduledActivity findOneTimeActivity(List<ScheduledActivity> scheduledActivityList) {
        for (ScheduledActivity oneScheduledActivity : scheduledActivityList) {
            if (oneTimeActivityLabel.equals(oneScheduledActivity.getActivity().getLabel())) {
                return oneScheduledActivity;
            }
        }
        // It doesn't exist. Return null and let the test deal with it.
        return null;
    }
    
    private Multiset<String> getActivityLabels(ScheduledActivityList scheduledActivities) {
        return HashMultiset.create(scheduledActivities.getItems().stream()
                .map((act) -> act.getActivity().getLabel())
                .collect(Collectors.toList()));
    }
    
}
