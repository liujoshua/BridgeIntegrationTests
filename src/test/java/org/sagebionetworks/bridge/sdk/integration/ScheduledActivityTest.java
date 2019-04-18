package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.sagebionetworks.bridge.sdk.integration.Tests.assertDatesWithTimeZoneEqual;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.google.common.collect.ImmutableList;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.Lists;
import com.google.common.collect.Multiset;

import org.sagebionetworks.bridge.rest.RestUtils;
import org.sagebionetworks.bridge.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.rest.api.ParticipantsApi;
import org.sagebionetworks.bridge.rest.api.SchedulesApi;
import org.sagebionetworks.bridge.rest.api.StudiesApi;
import org.sagebionetworks.bridge.rest.api.SurveysApi;
import org.sagebionetworks.bridge.rest.api.UploadSchemasApi;
import org.sagebionetworks.bridge.rest.exceptions.BadRequestException;
import org.sagebionetworks.bridge.rest.model.Activity;
import org.sagebionetworks.bridge.rest.model.ActivityType;
import org.sagebionetworks.bridge.rest.model.CompoundActivity;
import org.sagebionetworks.bridge.rest.model.ForwardCursorScheduledActivityList;
import org.sagebionetworks.bridge.rest.model.GuidCreatedOnVersionHolder;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.Schedule;
import org.sagebionetworks.bridge.rest.model.SchedulePlan;
import org.sagebionetworks.bridge.rest.model.ScheduleStatus;
import org.sagebionetworks.bridge.rest.model.ScheduleType;
import org.sagebionetworks.bridge.rest.model.ScheduledActivity;
import org.sagebionetworks.bridge.rest.model.ScheduledActivityList;
import org.sagebionetworks.bridge.rest.model.ScheduledActivityListV4;
import org.sagebionetworks.bridge.rest.model.SchemaReference;
import org.sagebionetworks.bridge.rest.model.SimpleScheduleStrategy;
import org.sagebionetworks.bridge.rest.model.Study;
import org.sagebionetworks.bridge.rest.model.Survey;
import org.sagebionetworks.bridge.rest.model.SurveyReference;
import org.sagebionetworks.bridge.rest.model.TaskReference;
import org.sagebionetworks.bridge.rest.model.UploadFieldDefinition;
import org.sagebionetworks.bridge.rest.model.UploadFieldType;
import org.sagebionetworks.bridge.rest.model.UploadSchema;
import org.sagebionetworks.bridge.rest.model.UploadSchemaType;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;

@Category(IntegrationSmokeTest.class)
@SuppressWarnings("ConstantConditions")
public class ScheduledActivityTest {
    
    private static final String TASK_ID = "task:AAA";
    private static final DateTimeZone EST = DateTimeZone.forOffsetHours(-5);
    // Ensure (using withHourOfDay) that we get four days of tasks despite the time of the test.
    private static final DateTime STARTS_ON = DateTime.now(EST).withHourOfDay(2);
    private static final DateTime ENDS_ON = STARTS_ON.plusDays(3).withHourOfDay(22);
    
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

    private String runId;
    private String monthlyActivityLabel;
    private String oneTimeActivityLabel;
    private TestUser admin;
    private TestUser researcher;
    private TestUser user;
    private TestUser developer;
    private SchedulesApi schedulePlansApi;
    private List<String> schedulePlanGuidList;
    private ForConsentedUsersApi usersApi;
    private UploadSchema schemaKeys;
    private GuidCreatedOnVersionHolder surveyKeys;

    @Before
    public void before() throws Exception {
        schedulePlanGuidList = new ArrayList<>();

        admin = TestUserHelper.getSignedInAdmin();
        researcher = TestUserHelper.createAndSignInUser(ScheduledActivityTest.class, true, Role.RESEARCHER);
        developer = TestUserHelper.createAndSignInUser(ScheduledActivityTest.class, true, Role.DEVELOPER);
        
        StudiesApi studiesApi = developer.getClient(StudiesApi.class);
        Study study = studiesApi.getUsersStudy().execute().body();
        if (!study.getAutomaticCustomEvents().containsKey("two_weeks_before_enrollment")) {
            study.getAutomaticCustomEvents().put("two_weeks_before_enrollment", "enrollment:P-14D");
            studiesApi.updateUsersStudy(study).execute().body();
        }
        
        user = TestUserHelper.createAndSignInUser(ScheduledActivityTest.class, true);

        schedulePlansApi = developer.getClient(SchedulesApi.class);
        usersApi = user.getClient(ForConsentedUsersApi.class);

        // Run ID is a random string, used to uniquely identify schedules for this test.
        runId = Tests.randomIdentifier(ScheduledActivityTest.class);
    }

    private void monthlyAfterOneMonthSchedule() throws IOException {
        monthlyActivityLabel = "monthly-activity-" + runId;

        String planGuid;
        Schedule schedule = new Schedule();
        schedule.setLabel(monthlyActivityLabel);
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
        schedulePlanGuidList.add(planGuid);
    }

    private void oneTimeScheduleAfter3Days() throws IOException {
        oneTimeActivityLabel = "one-time-activity-" + runId;

        Schedule schedule = new Schedule();
        schedule.setLabel(oneTimeActivityLabel);
        schedule.setDelay("P3D");
        schedule.setScheduleType(ScheduleType.ONCE);
        schedule.setTimes(Lists.newArrayList("10:00"));
        
        TaskReference taskReference = new TaskReference();
        taskReference.setIdentifier(TASK_ID);
        
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
        schedulePlanGuidList.add(planGuid);
    }
    
    private void dailyTaskAt4Times() throws IOException {
        Schedule schedule = new Schedule();
        schedule.setLabel("Daily Task at 4 times");
        schedule.setExpires("P1D");
        schedule.setInterval("P1D");
        schedule.setScheduleType(ScheduleType.RECURRING);
        schedule.setTimes(Lists.newArrayList("06:00", "10:00", "14:00", "18:00"));
        
        TaskReference taskReference = new TaskReference();
        taskReference.setIdentifier(TASK_ID);
        
        Activity activity = new Activity();
        activity.setLabel(runId);
        activity.setTask(taskReference);
        
        schedule.setActivities(Lists.newArrayList(activity));

        SimpleScheduleStrategy strategy = new SimpleScheduleStrategy();
        strategy.setSchedule(schedule);
        strategy.setType("SimpleScheduleStrategy");
        
        SchedulePlan plan = new SchedulePlan();
        plan.setLabel("Daily task schedule plan");
        plan.setStrategy(strategy);
        String planGuid = schedulePlansApi.createSchedulePlan(plan).execute().body().getGuid();
        schedulePlanGuidList.add(planGuid);
    }
    
    private void compoundDailyTask() throws IOException {
        UploadFieldDefinition fieldDef = new UploadFieldDefinition();
        fieldDef.setName("field");
        fieldDef.setType(UploadFieldType.STRING);
        UploadSchema schema = new UploadSchema();
        schema.setName("Schema");
        schema.setSchemaId(Tests.randomIdentifier(ScheduledActivityTest.class));
        schema.setSchemaType(UploadSchemaType.IOS_DATA);
        schema.setFieldDefinitions(Lists.newArrayList(fieldDef));
        
        // create it
        UploadSchemasApi schemasApi = developer.getClient(UploadSchemasApi.class);
        schemaKeys = schemasApi.createUploadSchema(schema).execute().body();
        
        Schedule schedule = new Schedule();
        schedule.setLabel("Once Daily Task");
        schedule.setExpires("P1D");
        schedule.setInterval("P1D");
        schedule.setScheduleType(ScheduleType.RECURRING);
        schedule.setTimes(ImmutableList.of("08:00", "12:00", "16:00", "20:00"));
        
        SchemaReference schemaRef = new SchemaReference();
        schemaRef.setId(schemaKeys.getSchemaId());
        schemaRef.setRevision(schemaKeys.getRevision());
        
        CompoundActivity compoundActivity = new CompoundActivity();
        compoundActivity.setTaskIdentifier(TASK_ID);
        compoundActivity.setSchemaList(ImmutableList.of(schemaRef));
        
        Activity activity = new Activity();
        activity.setLabel(runId);
        activity.setCompoundActivity(compoundActivity);
        
        schedule.setActivities(Lists.newArrayList(activity));

        SimpleScheduleStrategy strategy = new SimpleScheduleStrategy();
        strategy.setSchedule(schedule);
        strategy.setType("SimpleScheduleStrategy");
        
        SchedulePlan plan = new SchedulePlan();
        plan.setLabel("Daily task schedule plan");
        plan.setStrategy(strategy);
        String planGuid = schedulePlansApi.createSchedulePlan(plan).execute().body().getGuid();
        schedulePlanGuidList.add(planGuid);
    }
    
    private void dailySurvey() throws Exception {
        Survey survey = TestSurvey.getSurvey(ScheduledActivityTest.class);
        
        // create it
        SurveysApi surveysApi = developer.getClient(SurveysApi.class);
        surveyKeys = surveysApi.createSurvey(survey).execute().body();
        surveyKeys = surveysApi.publishSurvey(surveyKeys.getGuid(), surveyKeys.getCreatedOn(), false).execute().body();
        
        Schedule schedule = new Schedule();
        schedule.setLabel("Once Daily Task");
        schedule.setExpires("P1D");
        schedule.setInterval("P1D");
        schedule.setScheduleType(ScheduleType.RECURRING);
        schedule.setTimes(ImmutableList.of("08:00", "12:00", "16:00", "20:00"));
        
        SurveyReference surveyRef = new SurveyReference();
        surveyRef.setCreatedOn(surveyKeys.getCreatedOn());
        surveyRef.setGuid(surveyKeys.getGuid());
        surveyRef.setIdentifier(survey.getIdentifier());
        
        Activity activity = new Activity();
        activity.setLabel(runId);
        activity.setSurvey(surveyRef);
        
        schedule.setActivities(Lists.newArrayList(activity));

        SimpleScheduleStrategy strategy = new SimpleScheduleStrategy();
        strategy.setSchedule(schedule);
        strategy.setType("SimpleScheduleStrategy");
        
        SchedulePlan plan = new SchedulePlan();
        plan.setLabel("Daily survey schedule plan");
        plan.setStrategy(strategy);
        String planGuid = schedulePlansApi.createSchedulePlan(plan).execute().body().getGuid();
        schedulePlanGuidList.add(planGuid);
    }

    private void miniStudyBurstSchedule() throws Exception {
        Schedule schedule = new Schedule();
        schedule.setLabel("Mini Study Burst");
        schedule.setEventId("custom:two_weeks_before_enrollment,enrollment");
        schedule.setExpires("P1M");
        schedule.setInterval("P1D");
        schedule.setScheduleType(ScheduleType.RECURRING);
        schedule.setSequencePeriod("P3D");
        schedule.setTimes(ImmutableList.of("06:00"));

        TaskReference taskReference = new TaskReference();
        taskReference.setIdentifier(TASK_ID);

        Activity activity = new Activity();
        activity.setLabel("mini-study-burst-" + runId);
        activity.setTask(taskReference);

        schedule.setActivities(ImmutableList.of(activity));

        SimpleScheduleStrategy strategy = new SimpleScheduleStrategy();
        strategy.setSchedule(schedule);
        strategy.setType("SimpleScheduleStrategy");

        SchedulePlan plan = new SchedulePlan();
        plan.setLabel("Mini Study Burst");
        plan.setStrategy(strategy);
        String planGuid = schedulePlansApi.createSchedulePlan(plan).execute().body().getGuid();
        schedulePlanGuidList.add(planGuid);
    }

    @After
    public void after() throws Exception {
        if (schemaKeys != null) {
            admin.getClient(UploadSchemasApi.class)
                    .deleteUploadSchema(schemaKeys.getSchemaId(), schemaKeys.getRevision(), true).execute();
        }
        try {
            SchedulesApi schedulesApi = admin.getClient(SchedulesApi.class);
            for (String oneSchedulePlanGuid : schedulePlanGuidList) {
                schedulesApi.deleteSchedulePlan(oneSchedulePlanGuid, true).execute();
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
        if (surveyKeys != null) {
            admin.getClient(SurveysApi.class)
                    .deleteSurvey(surveyKeys.getGuid(), surveyKeys.getCreatedOn(), true).execute();
        }
    }
    
    @Test
    public void getScheduledActivityHistoryV4() throws Exception {
        dailyTaskAt4Times();
        
        usersApi.getScheduledActivitiesByDateRange(STARTS_ON, ENDS_ON).execute();

        // getTaskHistory() uses a secondary global index. Sleep for 2 seconds to help make sure the index is consistent.
        Thread.sleep(2000);

        // Now we should see those in the latest API:
        ForwardCursorScheduledActivityList list = filterPages(false, (offsetKey) ->
            usersApi.getTaskHistory(TASK_ID, STARTS_ON, ENDS_ON, offsetKey, 10).execute().body());
        
        // Joda DateTime equality is only object instance equality, use strings to compare
        assertNotNull(list.getNextPageOffsetKey()); 
        assertEquals(STARTS_ON.toString(), list.getRequestParams().getScheduledOnStart().toString());
        assertEquals(ENDS_ON.toString(), list.getRequestParams().getScheduledOnEnd().toString());
        assertEquals(10, list.getRequestParams().getPageSize().intValue());
        assertEquals(16, list.getItems().size());
    }
    
    @Test
    public void createSchedulePlanGetScheduledActivities() throws Exception {
        oneTimeScheduleAfter3Days();
        monthlyAfterOneMonthSchedule();
        
        // Get scheduled activities. Validate basic properties.
        ScheduledActivityList scheduledActivities = usersApi.getScheduledActivities("-05:00", 4, 0).execute().body();
        ScheduledActivity schActivity = findOneTimeActivity(scheduledActivities.getItems());
        assertNotNull(schActivity);
        assertEquals(ScheduleStatus.SCHEDULED, schActivity.getStatus());
        assertNotNull(schActivity.getScheduledOn());
        assertNull(schActivity.getExpiresOn());

        Activity activity = schActivity.getActivity();
        assertEquals(ActivityType.TASK, activity.getActivityType());
        assertEquals(oneTimeActivityLabel, activity.getLabel());
        assertEquals(TASK_ID, activity.getTask().getIdentifier());

        DateTime startDateTime = DateTime.now(EST).minusDays(10);
        DateTime endDateTime = DateTime.now(EST).plusDays(10);

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
        assertFalse(list.isHasNext());
        assertEquals(startDateTime, list.getRequestParams().getScheduledOnStart());
        assertEquals(endDateTime, list.getRequestParams().getScheduledOnEnd());
        
        // If we ask for a date range that doesn't include it, it is not returned.
        list = participantsApi.getParticipantActivityHistory(user.getSession().getId(), activity.getGuid(),
                DateTime.now(EST).plusDays(10), DateTime.now(EST).plusDays(12), null, 5).execute().body();
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
        oneTimeScheduleAfter3Days();
        monthlyAfterOneMonthSchedule();
        
        // Get scheduled activities. Validate basic properties.
        
        ScheduledActivityListV4 scheduledActivities = usersApi.getScheduledActivitiesByDateRange(STARTS_ON, ENDS_ON)
                .execute().body();
        ScheduledActivity schActivity = findOneTimeActivity(scheduledActivities.getItems());
        assertNotNull(schActivity);
        assertEquals(ScheduleStatus.SCHEDULED, schActivity.getStatus());
        assertNotNull(schActivity.getScheduledOn());
        assertNull(schActivity.getExpiresOn());

        Activity activity = schActivity.getActivity();
        assertEquals(ActivityType.TASK, activity.getActivityType());
        assertEquals(oneTimeActivityLabel, activity.getLabel());
        assertEquals(TASK_ID, activity.getTask().getIdentifier());

        // Historical items should come back with the same time zone as get() method, and so activities should
        // be strictly equal.
        DateTime startDateTime = STARTS_ON.minusDays(10);
        DateTime endDateTime = STARTS_ON.plusDays(10);

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
        assertFalse(list.isHasNext());
        assertEquals(startDateTime, list.getRequestParams().getScheduledOnStart());
        assertEquals(endDateTime, list.getRequestParams().getScheduledOnEnd());
        
        // If we ask for a date range that doesn't include it, it is not returned.
        // This started failing around the time zone shift, we have to fix the time zone or we'll get a server error.
        list = participantsApi.getParticipantActivityHistory(user.getSession().getId(), activity.getGuid(),
                DateTime.now(DateTimeZone.UTC).plusDays(10), DateTime.now(DateTimeZone.UTC).plusDays(12), null, 5)
                .execute().body();
        assertTrue(list.getItems().isEmpty());
        
        // Start the activity.
        schActivity.setStartedOn(DateTime.now());
        schActivity.setClientData(new ClientData("Test Name", true, 100));
        usersApi.updateScheduledActivities(scheduledActivities.getItems()).execute();

        // Get activities back and validate that it's started.
        // It's delayed by three days, so ask for four
        scheduledActivities = usersApi.getScheduledActivitiesByDateRange(STARTS_ON, ENDS_ON).execute().body();
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
        scheduledActivities = usersApi.getScheduledActivitiesByDateRange(STARTS_ON, ENDS_ON).execute().body();
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
        oneTimeScheduleAfter3Days();
        monthlyAfterOneMonthSchedule();
        
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
        
        // Because this endpoint was deprecated before we addedRequestParams, the parameters are not
        // returned. /v3/activities is still in heavy use so maybe we should add them.
    }

    @Test
    public void scheduleWithMultipleEventIds() throws Exception {
        DateTime now = DateTime.now(DateTimeZone.forOffsetHours(-8));
        
        // Set up schedule
        miniStudyBurstSchedule();

        // Get activities, and filter on activity label.
        List<ScheduledActivity> scheduledActivityList = usersApi.getScheduledActivities("-08:00", 7,
                null).execute().body().getItems();
        List<ScheduledActivity> filteredActivityList = scheduledActivityList.stream()
                .filter(act -> act.getActivity().getLabel().equals("mini-study-burst-" + runId))
                .collect(Collectors.toList());

        // Activities should be generated and returned in order, starting from 6am 2 weeks before enrollment.
        assertEquals(6, filteredActivityList.size());
        assertDatesWithTimeZoneEqual(now.minusDays(14).withTime(6, 0, 0, 0), filteredActivityList.get(0)
                .getScheduledOn());
        assertDatesWithTimeZoneEqual(now.minusDays(13).withTime(6, 0, 0, 0), filteredActivityList.get(1)
                .getScheduledOn());
        assertDatesWithTimeZoneEqual(now.minusDays(12).withTime(6, 0, 0, 0), filteredActivityList.get(2)
                .getScheduledOn());
        assertDatesWithTimeZoneEqual(now.withTime(6, 0, 0, 0), filteredActivityList.get(3)
                .getScheduledOn());
        assertDatesWithTimeZoneEqual(now.plusDays(1).withTime(6, 0, 0, 0), filteredActivityList.get(4)
                .getScheduledOn());
        assertDatesWithTimeZoneEqual(now.plusDays(2).withTime(6, 0, 0, 0), filteredActivityList.get(5)
                .getScheduledOn());
    }
    
    @Test
    public void getCompoundActivityHistory() throws Exception {
        compoundDailyTask();
        
        ForConsentedUsersApi userApi = user.getClient(ForConsentedUsersApi.class);
        
        usersApi.getScheduledActivitiesByDateRange(STARTS_ON, ENDS_ON).execute();
        
        ForwardCursorScheduledActivityList list = filterPages(true, (offsetKey) ->
            userApi.getCompoundActivityHistory(TASK_ID, STARTS_ON, ENDS_ON, offsetKey, 10).execute().body());

        for (ScheduledActivity act : list.getItems()) {
            assertEquals(TASK_ID, act.getActivity().getCompoundActivity().getTaskIdentifier());
            assertEquals(EST, act.getScheduledOn().getZone());
        }
        
        assertEquals(16, list.getItems().size());
        assertEquals(STARTS_ON, list.getRequestParams().getScheduledOnStart());
        assertEquals(ENDS_ON, list.getRequestParams().getScheduledOnEnd());
        assertNotNull(list.getNextPageOffsetKey());
        
        // And these tasks don't show up through any of the other methods
        ForwardCursorScheduledActivityList list2 = filterPages(false, (key) ->
            userApi.getCompoundActivityHistory("not the right ID", STARTS_ON, ENDS_ON, null, null).execute().body());
        assertTrue(list2.getItems().isEmpty());
        
        ForwardCursorScheduledActivityList list3 = filterPages(false, (key) -> 
            userApi.getSurveyHistory(TASK_ID, STARTS_ON, ENDS_ON, null, null).execute().body());
        assertTrue(list3.getItems().isEmpty());

        ForwardCursorScheduledActivityList list4 = filterPages(false, (key) -> 
            userApi.getTaskHistory(TASK_ID, STARTS_ON, ENDS_ON, null, null).execute().body());
        assertTrue(list4.getItems().isEmpty());
    }
    
    @Test
    public void getSurveyHistory() throws Exception {
        dailySurvey();
        
        ForConsentedUsersApi userApi = user.getClient(ForConsentedUsersApi.class);
        
        usersApi.getScheduledActivitiesByDateRange(STARTS_ON, ENDS_ON).execute();
        
        ForwardCursorScheduledActivityList list = filterPages(true, (offsetKey) ->
            userApi.getSurveyHistory(surveyKeys.getGuid(), STARTS_ON, ENDS_ON, offsetKey, 10).execute().body());

        for (ScheduledActivity act : list.getItems()) {
            assertEquals(surveyKeys.getGuid(), act.getActivity().getSurvey().getGuid());
            assertEquals(surveyKeys.getCreatedOn(), act.getActivity().getSurvey().getCreatedOn());
            assertEquals(EST, act.getScheduledOn().getZone());
        }
        assertEquals(16, list.getItems().size());
        assertEquals(STARTS_ON, list.getRequestParams().getScheduledOnStart());
        assertEquals(ENDS_ON, list.getRequestParams().getScheduledOnEnd());
        assertNotNull(list.getNextPageOffsetKey());
        
        // And these tasks don't show up through any of the other methods
        ForwardCursorScheduledActivityList list2 = userApi.getSurveyHistory(
                "not the right ID", STARTS_ON, ENDS_ON, null, null).execute().body();
        assertTrue(list2.getItems().isEmpty());
        
        ForwardCursorScheduledActivityList list3 = userApi
                .getCompoundActivityHistory(surveyKeys.getGuid(), STARTS_ON, ENDS_ON, null, null).execute().body();
        assertTrue(list3.getItems().isEmpty());

        ForwardCursorScheduledActivityList list4 = userApi.getTaskHistory(surveyKeys.getGuid(), STARTS_ON, ENDS_ON, null, null)
                .execute().body();
        assertTrue(list4.getItems().isEmpty());
    }
    
    @Test
    public void getTaskHistory() throws Exception {
        dailyTaskAt4Times();
        
        ForConsentedUsersApi userApi = user.getClient(ForConsentedUsersApi.class);
        
        usersApi.getScheduledActivitiesByDateRange(STARTS_ON, ENDS_ON).execute();
        
        ForwardCursorScheduledActivityList list = filterPages(true, (offsetKey) ->
            userApi.getTaskHistory(TASK_ID, STARTS_ON, ENDS_ON, offsetKey, 10).execute().body());
        
        for (ScheduledActivity act : list.getItems()) {
            assertEquals(TASK_ID, act.getActivity().getTask().getIdentifier());
            assertEquals(EST, act.getScheduledOn().getZone());
        }
        
        assertEquals(16, list.getItems().size());
        
        // And these tasks don't show up through any of the other methods
        ForwardCursorScheduledActivityList list2 = filterPages(false, (key) ->
            userApi.getTaskHistory("not the right ID", STARTS_ON, ENDS_ON, null, null).execute().body());
        assertTrue(list2.getItems().isEmpty());
        
        ForwardCursorScheduledActivityList list3 = filterPages(false, (key) ->
            userApi.getCompoundActivityHistory(TASK_ID, STARTS_ON, ENDS_ON, null, null).execute().body());
        assertTrue(list3.getItems().isEmpty());

        ForwardCursorScheduledActivityList list4 = filterPages(false, (key) ->
            userApi.getSurveyHistory(TASK_ID, STARTS_ON, ENDS_ON, null, null).execute().body());
        assertTrue(list4.getItems().isEmpty());
    }
    
    @Test(expected = BadRequestException.class)
    public void getScheduledActivityHistoryV3InvalidOffsetKey() throws Exception {
        dailyTaskAt4Times();
        
        ForConsentedUsersApi userApi = user.getClient(ForConsentedUsersApi.class);
        
        userApi.getTaskHistory(TASK_ID, STARTS_ON, ENDS_ON, "bad-key:key", 10).execute();
    }
    
    @Test
    public void getScheduledActivityHistoryV3NormalPaging() throws Exception {
        dailyTaskAt4Times();
        
        ForConsentedUsersApi userApi = user.getClient(ForConsentedUsersApi.class);
        
        ScheduledActivityListV4 actList = filterPagesV4((offsetKey) -> 
            userApi.getScheduledActivitiesByDateRange(STARTS_ON, ENDS_ON).execute().body());
        
        final String activityGuid = actList.getItems().get(0).getGuid().split(":")[0];
        
        ForwardCursorScheduledActivityList list = filterPages(true, (nextPageOffset) -> 
            userApi.getActivityHistory(activityGuid, STARTS_ON, ENDS_ON, nextPageOffset, 10).execute().body());
        
        for (ScheduledActivity act : list.getItems()) {
            assertEquals(EST, act.getScheduledOn().getZone());
        }
        assertEquals(16, list.getItems().size());
        assertEquals(STARTS_ON, list.getRequestParams().getScheduledOnStart());
        assertEquals(ENDS_ON, list.getRequestParams().getScheduledOnEnd());
        assertNotNull(list.getNextPageOffsetKey());
    }
    
    @Test(expected = BadRequestException.class)
    public void getScheduledActivityHistoryV3PathologicalPaging() throws Exception {
        ForConsentedUsersApi userApi = user.getClient(ForConsentedUsersApi.class);
        userApi.getTaskHistory(TASK_ID, STARTS_ON, ENDS_ON, null, -10).execute();
    }
    
    @FunctionalInterface
    public static interface ExceptionThrowingFunction<T, R> {
       R apply(T t) throws Exception;
    }
    
    /**
     * Iterate through all pages and return an activity list with only the current tests activities and the FIRST
     * request params object returned from the server. If pagesExpected is true, we also assert that we receive at 
     * least two pages. This method will also fail after 100 pages in case of defects.
     */
    private ForwardCursorScheduledActivityList filterPages(boolean pagesExpected,
            ExceptionThrowingFunction<String, ForwardCursorScheduledActivityList> caller) throws Exception {
        ForwardCursorScheduledActivityList filteredList = new ForwardCursorScheduledActivityList();
        
        List<ScheduledActivity> activities = new ArrayList<>();
        String nextPageOffsetKey = null;
        int tries = 1;
        do {
            
            ForwardCursorScheduledActivityList results = caller.apply(nextPageOffsetKey);
            for (ScheduledActivity act : results.getItems()) {
                if (act.getActivity().getLabel().equals(runId)) {
                    activities.add(act);    
                }
            }
            // What was sent is returned back.
            assertEquals(nextPageOffsetKey, results.getRequestParams().getOffsetKey());
            
            nextPageOffsetKey = results.getNextPageOffsetKey();
            tries++;
            // Take the first request values to examine in origin test
            if (filteredList.getNextPageOffsetKey() == null) {
                filteredList.nextPageOffsetKey(nextPageOffsetKey);        
            }
            if (filteredList.getRequestParams() == null) {
                filteredList.requestParams(results.getRequestParams());    
            }
            
        } while(nextPageOffsetKey != null && tries < 100);
        if (tries >= 100) {
            fail("Test failed due to a pathological number of scheduled activity pages");
        } else if (pagesExpected && tries == 1) {
            fail("Expected more than one page of results, but only one page was returned");
        }
        
        filteredList.items(activities);
        return filteredList;
    }
    
    private ScheduledActivityListV4 filterPagesV4(ExceptionThrowingFunction<String, ScheduledActivityListV4> caller)
            throws Exception {
        ScheduledActivityListV4 filteredList = new ScheduledActivityListV4();
        
        List<ScheduledActivity> activities = new ArrayList<>();
        ScheduledActivityListV4 results = caller.apply(null);
        for (ScheduledActivity act : results.getItems()) {
            if (act.getActivity().getLabel().equals(runId)) {
                activities.add(act);    
            }
        }
        // Take the first request values to examine in origin test
        if (filteredList.getRequestParams() == null) {
            filteredList.requestParams(results.getRequestParams());    
        }
        filteredList.items(activities);
        return filteredList;
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
