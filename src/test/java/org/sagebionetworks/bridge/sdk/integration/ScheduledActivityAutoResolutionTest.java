package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.api.ActivitiesApi;
import org.sagebionetworks.bridge.rest.api.CompoundActivityDefinitionsApi;
import org.sagebionetworks.bridge.rest.api.SchedulesApi;
import org.sagebionetworks.bridge.rest.api.SurveysApi;
import org.sagebionetworks.bridge.rest.api.UploadSchemasApi;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.model.Activity;
import org.sagebionetworks.bridge.rest.model.CompoundActivity;
import org.sagebionetworks.bridge.rest.model.CompoundActivityDefinition;
import org.sagebionetworks.bridge.rest.model.Constraints;
import org.sagebionetworks.bridge.rest.model.DataType;
import org.sagebionetworks.bridge.rest.model.GuidCreatedOnVersionHolder;
import org.sagebionetworks.bridge.rest.model.IntegerConstraints;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.Schedule;
import org.sagebionetworks.bridge.rest.model.SchedulePlan;
import org.sagebionetworks.bridge.rest.model.ScheduleStrategy;
import org.sagebionetworks.bridge.rest.model.ScheduleType;
import org.sagebionetworks.bridge.rest.model.ScheduledActivity;
import org.sagebionetworks.bridge.rest.model.SchemaReference;
import org.sagebionetworks.bridge.rest.model.SimpleScheduleStrategy;
import org.sagebionetworks.bridge.rest.model.Survey;
import org.sagebionetworks.bridge.rest.model.SurveyElement;
import org.sagebionetworks.bridge.rest.model.SurveyQuestion;
import org.sagebionetworks.bridge.rest.model.SurveyReference;
import org.sagebionetworks.bridge.rest.model.TaskReference;
import org.sagebionetworks.bridge.rest.model.UIHint;
import org.sagebionetworks.bridge.rest.model.UploadFieldDefinition;
import org.sagebionetworks.bridge.rest.model.UploadFieldType;
import org.sagebionetworks.bridge.rest.model.UploadSchema;
import org.sagebionetworks.bridge.rest.model.UploadSchemaType;

public class ScheduledActivityAutoResolutionTest {
    private static final String ACTIVITY_LABEL_PREFIX = "activity-";
    private static final String COMPOUND_TASK_ID_PREFIX = "compound-activity-";
    private static final String INTEG_TEST_OS_NAME = "BridgeIntegrationTests";
    private static final String SCHEMA_ID = "schedule-test-schema";
    private static final UploadFieldDefinition SIMPLE_FIELD_DEF = new UploadFieldDefinition()
            .name("record.json.test-field").type(UploadFieldType.STRING).maxLength(10);
    private static final String SURVEY_ID = "schedule-test-survey";
    private static final String TASK_ID = "task:AAA";

    private static CompoundActivityDefinitionsApi compoundActivityDefinitionsApi;
    private static SchedulesApi schedulePlanApi;
    private static SurveysApi adminSurveyApi;
    private static SurveysApi surveyApi;
    private static TestUserHelper.TestUser developer;

    private List<GuidCreatedOnVersionHolder> surveysToDelete;
    private String activityLabel;
    private String compoundTaskId;
    private String compoundTaskIdToDelete;
    private String schedulePlanGuidToDelete;
    private TestUserHelper.TestUser user;

    @BeforeClass
    public static void beforeClass() throws Exception {
        // init users and clients
        developer = TestUserHelper.createAndSignInUser(ScheduledActivityAutoResolutionTest.class, false,
                Role.DEVELOPER);
        compoundActivityDefinitionsApi = developer.getClient(CompoundActivityDefinitionsApi.class);
        schedulePlanApi = developer.getClient(SchedulesApi.class);
        adminSurveyApi = TestUserHelper.getSignedInAdmin().getClient(SurveysApi.class);
        surveyApi = developer.getClient(SurveysApi.class);

        // Make sure we have a dummy schema to resolve to. Otherwise, getScheduledActivities will fail spectacularly.
        // Create two revs of the schema. This allows us to also test the minAppVersion flag.
        UploadSchemasApi schemaApi = developer.getClient(UploadSchemasApi.class);

        // rev1 has minAppVersion=2
        UploadSchema schemaRev1 = null;
        try {
            schemaRev1 = schemaApi.getUploadSchema(SCHEMA_ID, 1L).execute().body();
        } catch (EntityNotFoundException ex) {
            // Do nothing. This just means we need to bootstrap this schema.
        }
        if (schemaRev1 == null) {
            schemaRev1 = new UploadSchema().name(SCHEMA_ID).schemaId(SCHEMA_ID).revision(1L)
                    .schemaType(UploadSchemaType.IOS_DATA).minAppVersions(ImmutableMap.of(INTEG_TEST_OS_NAME, 2))
                    .addFieldDefinitionsItem(SIMPLE_FIELD_DEF);
            schemaApi.createUploadSchema(schemaRev1).execute();
        }

        // rev2 has minAppVersion=4
        UploadSchema schemaRev2 = null;
        try {
            schemaRev2 = schemaApi.getUploadSchema(SCHEMA_ID, 2L).execute().body();
        } catch (EntityNotFoundException ex) {
            // Do nothing. This just means we need to bootstrap this schema.
        }
        if (schemaRev2 == null) {
            schemaRev2 = new UploadSchema().name(SCHEMA_ID).schemaId(SCHEMA_ID).revision(2L)
                    .schemaType(UploadSchemaType.IOS_DATA).minAppVersions(ImmutableMap.of(INTEG_TEST_OS_NAME, 4))
                    .addFieldDefinitionsItem(SIMPLE_FIELD_DEF);
            schemaApi.createUploadSchema(schemaRev2).execute();
        }
    }

    @Before
    public void before() throws Exception {
        // generate IDs
        activityLabel = ACTIVITY_LABEL_PREFIX + RandomStringUtils.randomAlphabetic(4);
        compoundTaskId = COMPOUND_TASK_ID_PREFIX + RandomStringUtils.randomAlphabetic(4);

        // init "to delete" holders
        compoundTaskIdToDelete = null;
        schedulePlanGuidToDelete = null;
        surveysToDelete = new ArrayList<>();

        // We need to create a user for each test. This is because we modify the user client info, and this changes the
        // manager, which changes the scheduled activities client.
        user = TestUserHelper.createAndSignInUser(ScheduledActivityAutoResolutionTest.class, true);
    }

    @After
    public void after() throws Exception {
        // Delete the test user.
        if (user != null) {
            user.signOutAndDeleteUser();
        }

        // Delete schedules first, or we get constraint violation exceptions.
        if (schedulePlanGuidToDelete != null) {
            schedulePlanApi.deleteSchedulePlan(schedulePlanGuidToDelete).execute();
        }

        // Delete compound activity, if any
        if (compoundTaskIdToDelete != null) {
            compoundActivityDefinitionsApi.deleteCompoundActivityDefinition(compoundTaskIdToDelete).execute();
        }

        // Deleting surveys requires an admin
        for (GuidCreatedOnVersionHolder oneSurveyKey : surveysToDelete) {
            adminSurveyApi.deleteSurvey(oneSurveyKey.getGuid(), oneSurveyKey.getCreatedOn(), true).execute();
        }
    }

    @AfterClass
    public static void deleteDeveloper() throws Exception {
        if (developer != null) {
            developer.signOutAndDeleteUser();
        }
    }

    @Test
    public void resolveSchemas() throws Exception {
        // Create simple schedule plan with a task ref with a schema ref to this schema.
        SchemaReference schemaRef = new SchemaReference().id(SCHEMA_ID);
        TaskReference taskRef = new TaskReference().identifier(TASK_ID).schema(schemaRef);
        Activity activity = new Activity().label(activityLabel).task(taskRef);
        schedulePlanGuidToDelete = createSchedulePlanWithActivity(activity);

        // User has app v3. Should get schema rev1 back.
        {
            // Note that we can't cache the scheduled activity API, because changing the client info requires us to get
            // a new client.
            user.setClientInfo(Tests.getClientInfoWithVersion(INTEG_TEST_OS_NAME, 3));
            List<ScheduledActivity> scheduledActivityList = user.getClient(ActivitiesApi.class).getScheduledActivities(
                    "+0:00", 2, null).execute().body().getItems();

            // Study may have other schedules. Find the scheduled activity for our test using the label.
            ScheduledActivity gettedScheduledActivity = findScheduledActivityByLabel(activityLabel,
                    scheduledActivityList);
            SchemaReference gettedSchemaRef = gettedScheduledActivity.getActivity().getTask().getSchema();
            assertEquals(SCHEMA_ID, gettedSchemaRef.getId());
            assertEquals(1, gettedSchemaRef.getRevision().intValue());
        }

        // Now user has app v5. Should get schema rev2 back.
        {
            user.setClientInfo(Tests.getClientInfoWithVersion(INTEG_TEST_OS_NAME, 5));
            List<ScheduledActivity> scheduledActivityList = user.getClient(ActivitiesApi.class).getScheduledActivities(
                    "+0:00", 2, null).execute().body().getItems();

            ScheduledActivity gettedScheduledActivity = findScheduledActivityByLabel(activityLabel,
                    scheduledActivityList);
            SchemaReference gettedSchemaRef = gettedScheduledActivity.getActivity().getTask().getSchema();
            assertEquals(SCHEMA_ID, gettedSchemaRef.getId());
            assertEquals(2, gettedSchemaRef.getRevision().intValue());
        }
    }

    @Test
    public void resolveSurveys() throws Exception {
        // Similarly, we need surveys to resolve.
        GuidCreatedOnVersionHolder surveyKeys = createSimpleSurvey();
        surveysToDelete.add(surveyKeys);
        surveyApi.publishSurvey(surveyKeys.getGuid(), surveyKeys.getCreatedOn(), false).execute();

        // Similarly, create a schedule plan with a "published survey" ref, or a ref without a createdOn.
        SurveyReference surveyRef = new SurveyReference().guid(surveyKeys.getGuid()).identifier(SURVEY_ID);
        Activity activity = new Activity().label(activityLabel).survey(surveyRef);
        schedulePlanGuidToDelete = createSchedulePlanWithActivity(activity);

        // User gets survey back in scheduled activities.
        {
            List<ScheduledActivity> scheduledActivityList = user.getClient(ActivitiesApi.class).getScheduledActivities(
                    "+0:00", 2, null).execute().body().getItems();

            ScheduledActivity gettedScheduledActivity = findScheduledActivityByLabel(activityLabel,
                    scheduledActivityList);
            SurveyReference gettedSurveyRef = gettedScheduledActivity.getActivity().getSurvey();
            assertEquals(surveyKeys.getGuid(), gettedSurveyRef.getGuid());
            assertEquals(surveyKeys.getCreatedOn(), gettedSurveyRef.getCreatedOn());
            assertEquals(SURVEY_ID, gettedSurveyRef.getIdentifier());
        }

        // Version and publish the survey.
        GuidCreatedOnVersionHolder surveyKeys2 = surveyApi.versionSurvey(surveyKeys.getGuid(),
                surveyKeys.getCreatedOn()).execute().body();
        surveysToDelete.add(surveyKeys2);
        surveyApi.publishSurvey(surveyKeys2.getGuid(), surveyKeys2.getCreatedOn(), false).execute();

        // User now gets the new survey createdOn back.
        {
            List<ScheduledActivity> scheduledActivityList = user.getClient(ActivitiesApi.class).getScheduledActivities(
                    "+0:00", 2, null).execute().body().getItems();

            ScheduledActivity gettedScheduledActivity = findScheduledActivityByLabel(activityLabel,
                    scheduledActivityList);
            SurveyReference gettedSurveyRef = gettedScheduledActivity.getActivity().getSurvey();
            assertEquals(surveyKeys2.getGuid(), gettedSurveyRef.getGuid());
            assertEquals(surveyKeys2.getCreatedOn(), gettedSurveyRef.getCreatedOn());
            assertEquals(SURVEY_ID, gettedSurveyRef.getIdentifier());
        }
    }

    @Test
    public void resolveCompoundActivities() throws Exception {
        // We create schemas in beforeClass(). We also need a survey. The one in resolveSurveys won't do because we
        // modify the survey as part of the test, making it unsuitable for this test.
        GuidCreatedOnVersionHolder surveyKeys = createSimpleSurvey();
        surveysToDelete.add(surveyKeys);
        surveyApi.publishSurvey(surveyKeys.getGuid(), surveyKeys.getCreatedOn(), false).execute();

        // Compound activity definition has references to both the schema and the survey.
        SchemaReference schemaRef = new SchemaReference().id(SCHEMA_ID);
        SurveyReference surveyRef = new SurveyReference().guid(surveyKeys.getGuid()).identifier(SURVEY_ID);
        CompoundActivityDefinition compoundActivityDefinition = new CompoundActivityDefinition()
                .addSchemaListItem(schemaRef).addSurveyListItem(surveyRef).taskId(compoundTaskId);
        CompoundActivityDefinitionsApi compoundActivityDefinitionsApi = developer.getClient(
                CompoundActivityDefinitionsApi.class);
        compoundActivityDefinitionsApi.createCompoundActivityDefinition(compoundActivityDefinition).execute();
        compoundTaskIdToDelete = compoundTaskId;

        // Similarly, create a schedule plan with a compound activity ref.
        CompoundActivity compoundActivity = new CompoundActivity().taskIdentifier(compoundTaskId);
        Activity activity = new Activity().label(activityLabel).compoundActivity(compoundActivity);
        schedulePlanGuidToDelete = createSchedulePlanWithActivity(activity);

        // User gets schema rev 2 and whatever survey createdOn that is.
        {
            List<ScheduledActivity> scheduledActivityList = user.getClient(ActivitiesApi.class).getScheduledActivities(
                    "+0:00", 2, null).execute().body().getItems();

            ScheduledActivity gettedScheduledActivity = findScheduledActivityByLabel(activityLabel,
                    scheduledActivityList);
            CompoundActivity gettedCompoundActivity = gettedScheduledActivity.getActivity().getCompoundActivity();

            assertEquals(1, gettedCompoundActivity.getSchemaList().size());
            SchemaReference gettedSchemaRef = gettedCompoundActivity.getSchemaList().get(0);
            assertEquals(SCHEMA_ID, gettedSchemaRef.getId());
            assertEquals(2, gettedSchemaRef.getRevision().intValue());

            assertEquals(1, gettedCompoundActivity.getSurveyList().size());
            SurveyReference gettedSurveyRef = gettedCompoundActivity.getSurveyList().get(0);
            assertEquals(surveyKeys.getGuid(), gettedSurveyRef.getGuid());
            assertEquals(surveyKeys.getCreatedOn(), gettedSurveyRef.getCreatedOn());
            assertEquals(SURVEY_ID, gettedSurveyRef.getIdentifier());
        }

        // Update the compound activity definition. Simplest update, just remove the survey and leave the schema.
        CompoundActivityDefinition defToUpdate = compoundActivityDefinitionsApi.getCompoundActivityDefinition(
                compoundTaskId).execute().body();
        defToUpdate.setSurveyList(ImmutableList.of());
        compoundActivityDefinitionsApi.updateCompoundActivityDefinition(compoundTaskId, defToUpdate).execute();

        // Get scheduled activities again. Now we should get the updated compound activity.
        {
            List<ScheduledActivity> scheduledActivityList = user.getClient(ActivitiesApi.class).getScheduledActivities(
                    "+0:00", 2, null).execute().body().getItems();

            ScheduledActivity gettedScheduledActivity = findScheduledActivityByLabel(activityLabel,
                    scheduledActivityList);
            CompoundActivity gettedCompoundActivity = gettedScheduledActivity.getActivity().getCompoundActivity();

            assertEquals(1, gettedCompoundActivity.getSchemaList().size());
            SchemaReference gettedSchemaRef = gettedCompoundActivity.getSchemaList().get(0);
            assertEquals(SCHEMA_ID, gettedSchemaRef.getId());
            assertEquals(2, gettedSchemaRef.getRevision().intValue());

            assertTrue(gettedCompoundActivity.getSurveyList().isEmpty());
        }
    }

    private static String createSchedulePlanWithActivity(Activity activity) throws Exception {
        Schedule schedule = new Schedule().scheduleType(ScheduleType.ONCE).addActivitiesItem(activity);
        ScheduleStrategy scheduleStrategy = new SimpleScheduleStrategy().schedule(schedule)
                .type("SimpleScheduleStrategy");
        SchedulePlan schedulePlan = new SchedulePlan().label("Schedule Test Schedule Plan").strategy(scheduleStrategy);
        return schedulePlanApi.createSchedulePlan(schedulePlan).execute().body().getGuid();
    }

    private static ScheduledActivity findScheduledActivityByLabel(String label,
            List<ScheduledActivity> scheduledActivityList) {
        return scheduledActivityList.stream()
                .filter(scheduledActivity -> label.equals(scheduledActivity.getActivity().getLabel()))
                .findFirst().orElse(null);
    }

    private static GuidCreatedOnVersionHolder createSimpleSurvey() throws Exception {
        Constraints constraints = new IntegerConstraints().dataType(DataType.INTEGER);
        SurveyElement surveyQuestion = new SurveyQuestion().constraints(constraints)
                .prompt("Pick a Number").uiHint(UIHint.NUMBERFIELD).identifier("test-survey-q")
                .type("SurveyQuestion");
        Survey survey = new Survey().name(SURVEY_ID).identifier(SURVEY_ID).addElementsItem(surveyQuestion);
        return surveyApi.createSurvey(survey).execute().body();
    }
}
