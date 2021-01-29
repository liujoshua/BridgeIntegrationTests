package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.sagebionetworks.bridge.rest.model.Role.DEVELOPER;
import static org.sagebionetworks.bridge.rest.model.ScheduleType.RECURRING;

import java.util.List;
import java.util.stream.Collectors;

import com.google.common.collect.ImmutableList;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.api.AppsApi;
import org.sagebionetworks.bridge.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.rest.api.ForSuperadminsApi;
import org.sagebionetworks.bridge.rest.api.SchedulesApi;
import org.sagebionetworks.bridge.rest.model.Activity;
import org.sagebionetworks.bridge.rest.model.App;
import org.sagebionetworks.bridge.rest.model.Criteria;
import org.sagebionetworks.bridge.rest.model.CriteriaScheduleStrategy;
import org.sagebionetworks.bridge.rest.model.CustomActivityEventRequest;
import org.sagebionetworks.bridge.rest.model.GuidVersionHolder;
import org.sagebionetworks.bridge.rest.model.Schedule;
import org.sagebionetworks.bridge.rest.model.ScheduleCriteria;
import org.sagebionetworks.bridge.rest.model.SchedulePlan;
import org.sagebionetworks.bridge.rest.model.ScheduledActivity;
import org.sagebionetworks.bridge.rest.model.ScheduledActivityList;
import org.sagebionetworks.bridge.rest.model.ScheduledActivityListV4;
import org.sagebionetworks.bridge.rest.model.SignUp;
import org.sagebionetworks.bridge.rest.model.TaskReference;
import org.sagebionetworks.bridge.rest.model.VersionHolder;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;

public class ScheduledActivityRecurringTest {
    private static final String FILTERED_LABEL = "ScheduledActivityRecurringTest";
    private static final String M_TIME_OF_DAY = "T00:00:00.000+12:00"; // Gilbert Islands, +12:00, offset M
    private static final String Y_TIME_OF_DAY = "T00:00:00.000-12:00"; // Baker Island, -12:00, offset Y
    private static final DateTimeZone MTZ = DateTimeZone.forOffsetHours(12);
    private static final DateTimeZone YTZ = DateTimeZone.forOffsetHours(-12);
    private static final String CUSTOM_EVENT = "ScheduledActivityRecurringTest";

    private SchedulePlan schedulePlan;
    
    private TestUser admin;
    
    private TestUser developer;
    
    private TestUser user;
    
    @Before
    public void before() throws Exception {
        admin = TestUserHelper.getSignedInAdmin();
        developer = TestUserHelper.createAndSignInUser(ScheduledActivityRecurringTest.class, true, DEVELOPER);
        SignUp signUp = new SignUp().dataGroups(ImmutableList.of("sdk-int-1"));
        user = new TestUserHelper.Builder(ScheduledActivityRecurringTest.class).withConsentUser(true).withSignUp(signUp)
                .createAndSignInUser();
        
        App app = admin.getClient(AppsApi.class).getUsersApp().execute().body();
        if (app.isExternalIdRequiredOnSignup() || !app.getActivityEventKeys().contains(CUSTOM_EVENT)) {
            app.setExternalIdRequiredOnSignup(false);
            app.getActivityEventKeys().add(CUSTOM_EVENT);
            
            VersionHolder version = admin.getClient(ForSuperadminsApi.class).updateApp(app.getIdentifier(), app).execute().body();
            app.setVersion(version.getVersion());
        }
        Schedule schedule = new Schedule();
        schedule.setEventId("custom:"+CUSTOM_EVENT);
        schedule.setLabel("Schedule Label");
        schedule.setScheduleType(RECURRING);
        schedule.setInterval("P1D");
        schedule.setExpires("P1D");
        // This turns out to be important since we can't control the time-of-day that this test runs. This aligns the 
        // time of day change with the date change, so no matter the time of day, the days will remain the same in the 
        // test. In the real world the way our system works, tasks that start at say 10:00am and last for a day, will 
        // expire at 10am the next day (and that's what we want but a repeatable test that deals with that gets very 
        // complicated).
        schedule.getTimes().add("00:00");
        schedule.getActivities().add(new Activity().label("label").task(new TaskReference().identifier("CCC")));
        
        ScheduleCriteria schCriteria = new ScheduleCriteria();
        schCriteria.setSchedule(schedule);
        schCriteria.setCriteria(new Criteria().addAllOfGroupsItem("sdk-int-1"));
        CriteriaScheduleStrategy strategy = new CriteriaScheduleStrategy();
        strategy.addScheduleCriteriaItem(schCriteria);
        
        schedulePlan = new SchedulePlan();
        schedulePlan.setLabel(FILTERED_LABEL);
        schedulePlan.setStrategy(strategy);
        
        GuidVersionHolder keys = developer.getClient(SchedulesApi.class).createSchedulePlan(schedulePlan).execute().body();
        schedulePlan.setGuid(keys.getGuid());
        schedulePlan.setVersion(keys.getVersion());
    }

    @After
    public void after() throws Exception {
        if (developer != null) {
            developer.signOutAndDeleteUser();
        }
        if (user != null) {
            user.signOutAndDeleteUser();
        }
        if (schedulePlan != null) {
            admin.getClient(SchedulesApi.class).deleteSchedulePlan(schedulePlan.getGuid(), true).execute();
        }
    }
    
    // Verifies that time zone changes, even across the date line, behave in a known and predicable way that 
    // should make sense to the end user.
    @Test
    public void retrievalActivitiesAcrossTimeAndTimeZones() throws Exception {
        ForConsentedUsersApi usersApi = user.getClient(ForConsentedUsersApi.class);
        
        DateTime now = DateTime.now();
        
        // Create an event two days in the past to schedule against
        usersApi.createCustomActivityEvent(
                new CustomActivityEventRequest().eventId(CUSTOM_EVENT).timestamp(now.minusDays(2))).execute();

        // Gilbert Islands, +12:00 hours.
        String mtz1 = now.withZone(MTZ).toLocalDate().toString()+M_TIME_OF_DAY;
        String mtz2 = now.withZone(MTZ).plusDays(1).toLocalDate().toString()+M_TIME_OF_DAY;
        String mtz3 = now.withZone(MTZ).plusDays(2).toLocalDate().toString()+M_TIME_OF_DAY;
        String mtz4 = now.withZone(MTZ).plusDays(3).toLocalDate().toString()+M_TIME_OF_DAY;
        
        // Baker Island, -12:00 hours. These sequences will always start in different days.
        String ytz1 = now.withZone(YTZ).toLocalDate().toString()+Y_TIME_OF_DAY;
        String ytz2 = now.withZone(YTZ).plusDays(1).toLocalDate().toString()+Y_TIME_OF_DAY;
        String ytz3 = now.withZone(YTZ).plusDays(2).toLocalDate().toString()+Y_TIME_OF_DAY;
        String ytz4 = now.withZone(YTZ).plusDays(3).toLocalDate().toString()+Y_TIME_OF_DAY;
        
        // Get three tasks in the Gilbert Island for today and next 2 days
        ScheduledActivityList activities = filterList(
                usersApi.getScheduledActivities("+12:00", 2, null).execute().body(), schedulePlan.getGuid());
        assertEquals(3, activities.getItems().size());
        assertEquals(mtz1, activities.getItems().get(0).getScheduledOn().toString());
        assertEquals(mtz2, activities.getItems().get(1).getScheduledOn().toString());
        assertEquals(mtz3, activities.getItems().get(2).getScheduledOn().toString());
        
        // Cross the dateline to the prior day. You get 4 activities (yesterday, today, tomorrow and the next day). 
        // One activity was created beyond the window, in +12:00 land.... that is not returned because although it 
        // exists, we filter it out from the persisted activities retrieved from the db.
        activities = filterList(usersApi.getScheduledActivities("-12:00", 2, null).execute().body(),
                schedulePlan.getGuid());
        assertEquals(4, activities.getItems().size());
        assertEquals(ytz1, activities.getItems().get(0).getScheduledOn().toString());
        assertEquals(ytz2, activities.getItems().get(1).getScheduledOn().toString());
        assertEquals(ytz3, activities.getItems().get(2).getScheduledOn().toString());
        assertEquals(ytz4, activities.getItems().get(3).getScheduledOn().toString());
        
        // Return to +12:00 land and ask for activites for three days, but one day in the future
        ScheduledActivityListV4 activitiesV4 = filterList(usersApi
                .getScheduledActivitiesByDateRange(now.plusDays(1).withZone(MTZ), now.plusDays(3).plusMinutes(1).withZone(MTZ))
                .execute().body(), schedulePlan.getGuid());
        
        // Back to 3 activities, starting with tomorrow's activity
        assertEquals(3, activitiesV4.getItems().size());
        assertEquals(mtz2, activitiesV4.getItems().get(0).getScheduledOn().toString());
        assertEquals(mtz3, activitiesV4.getItems().get(1).getScheduledOn().toString());
        assertEquals(mtz4, activitiesV4.getItems().get(2).getScheduledOn().toString());
        
        // Finish tomorrow and the next day
        activitiesV4.getItems().get(0).startedOn(now).finishedOn(now);
        activitiesV4.getItems().get(1).startedOn(now).finishedOn(now);
        usersApi.updateScheduledActivities(activitiesV4.getItems()).execute();
        
        // Now retrieve activities for today and the next three days
        activities = filterList(usersApi.getScheduledActivities("+12:00", 3, null).execute().body(),
                schedulePlan.getGuid());
        
        // Today and three days from now are not finished and are returned
        assertEquals(2, activities.getItems().size());
        assertEquals(mtz1, activities.getItems().get(0).getScheduledOn().toString());
        assertEquals(mtz4, activities.getItems().get(1).getScheduledOn().toString());
        
        // Neither of the first two tasks in -12 land (yesterday, today) are finished.
        activities = filterList(usersApi.getScheduledActivities("-12:00", 2, null).execute().body(),
                schedulePlan.getGuid());
        assertEquals(2, activities.getItems().size());
        assertEquals(ytz1, activities.getItems().get(0).getScheduledOn().toString());
        assertEquals(ytz2, activities.getItems().get(1).getScheduledOn().toString());
    }
    
    private ScheduledActivityList filterList(ScheduledActivityList list, String guid) throws Exception {
        List<ScheduledActivity> activities = list.getItems().stream()
                .filter((activity) -> guid.equals(activity.getSchedulePlanGuid())).collect(Collectors.toList());
        Tests.setVariableValueInObject(list, "items", activities);
        return list;
    }

    private ScheduledActivityListV4 filterList(ScheduledActivityListV4 list, String guid) throws Exception {
        List<ScheduledActivity> activities = list.getItems().stream()
                .filter((activity) -> guid.equals(activity.getSchedulePlanGuid())).collect(Collectors.toList());
        Tests.setVariableValueInObject(list, "items", activities);
        return list;
    }
}
