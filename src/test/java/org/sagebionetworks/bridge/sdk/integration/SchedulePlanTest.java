package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.ClientManager;
import org.sagebionetworks.bridge.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.rest.api.SchedulesV1Api;
import org.sagebionetworks.bridge.rest.api.SurveysApi;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.exceptions.InvalidEntityException;
import org.sagebionetworks.bridge.rest.exceptions.UnauthorizedException;
import org.sagebionetworks.bridge.rest.model.Activity;
import org.sagebionetworks.bridge.rest.model.ActivityType;
import org.sagebionetworks.bridge.rest.model.ClientInfo;
import org.sagebionetworks.bridge.rest.model.Criteria;
import org.sagebionetworks.bridge.rest.model.CriteriaScheduleStrategy;
import org.sagebionetworks.bridge.rest.model.GuidCreatedOnVersionHolder;
import org.sagebionetworks.bridge.rest.model.GuidVersionHolder;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.Schedule;
import org.sagebionetworks.bridge.rest.model.ScheduleCriteria;
import org.sagebionetworks.bridge.rest.model.ScheduleList;
import org.sagebionetworks.bridge.rest.model.SchedulePlan;
import org.sagebionetworks.bridge.rest.model.SchedulePlanList;
import org.sagebionetworks.bridge.rest.model.ScheduleType;
import org.sagebionetworks.bridge.rest.model.SimpleScheduleStrategy;
import org.sagebionetworks.bridge.rest.model.Survey;
import org.sagebionetworks.bridge.rest.model.SurveyReference;
import org.sagebionetworks.bridge.rest.model.TaskReference;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;

public class SchedulePlanTest {

    private TestUser admin;
    private TestUser user;
    private TestUser developer;
    private SchedulesV1Api schedulesApi;
    private SurveysApi surveysApi;
    private ForConsentedUsersApi usersApi;

    @Before
    public void before() throws Exception {
        admin = TestUserHelper.getSignedInAdmin();
        developer = TestUserHelper.createAndSignInUser(SchedulePlanTest.class, true, Role.DEVELOPER);
        user = TestUserHelper.createAndSignInUser(SchedulePlanTest.class, true);

        schedulesApi = developer.getClient(SchedulesV1Api.class);
        surveysApi = developer.getClient(SurveysApi.class);
        usersApi = user.getClient(ForConsentedUsersApi.class);
    }

    @After
    public void after() throws Exception {
        if (developer != null) {
            developer.signOutAndDeleteUser();    
        }
        if (user != null) {
            user.signOutAndDeleteUser();    
        }
    }

    @Test
    public void canPhysicallyDeleteLogicallyDeletedSchedulePlan() throws Exception {
        SchedulePlan plan = Tests.getSimpleSchedulePlan();
        
        GuidVersionHolder keys = schedulesApi.createSchedulePlan(plan).execute().body();
        
        schedulesApi.deleteSchedulePlan(keys.getGuid(), false).execute();
        
        SchedulePlan retrieved = schedulesApi.getSchedulePlan(keys.getGuid()).execute().body();
        assertTrue(retrieved.isDeleted());
        
        admin.getClient(SchedulesV1Api.class).deleteSchedulePlan(keys.getGuid(), true).execute();
        
        try {
            schedulesApi.getSchedulePlan(keys.getGuid()).execute().body();
            fail("Should have thrown an exception.");
        } catch(EntityNotFoundException e) {
            
        }
    }
    
    @Test
    public void normalUserCannotAccess() throws Exception {
        TestUser normalUser = null;
        try {
            normalUser = TestUserHelper.createAndSignInUser(SchedulePlanTest.class, true);
            SchedulePlan plan = Tests.getABTestSchedulePlan();
            
            normalUser.getClient(SchedulesV1Api.class).createSchedulePlan(plan).execute();
            fail("Should have returned Forbidden status");
        } catch (UnauthorizedException e) {
            assertEquals("Non-researcher gets 403 forbidden", 403, e.getStatusCode());
        } finally {
            if (normalUser != null) {
                normalUser.signOutAndDeleteUser();
            }
        }
    }

    @Test
    public void crudSchedulePlan() throws Exception {
        ClientInfo clientInfo = new ClientInfo();
        clientInfo.setAppName(Tests.APP_NAME);
        clientInfo.setAppVersion(3);
        // We want a version that will return the schedule plan. Zero doesn't do it.
        ClientManager manager = new ClientManager.Builder()
                .withSignIn(developer.getSignIn())
                .withAcceptLanguage(Lists.newArrayList("en"))
                .withClientInfo(clientInfo).build();

        SchedulePlan plan = Tests.getABTestSchedulePlan();

        SchedulesV1Api newSchedulesApi = manager.getClient(SchedulesV1Api.class);
        
        // Create
        assertNull(plan.getVersion());
        GuidVersionHolder keys = newSchedulesApi.createSchedulePlan(plan).execute().body();
        assertNotNull(keys.getGuid());
        assertNotNull(keys.getVersion());

        plan = newSchedulesApi.getSchedulePlan(keys.getGuid()).execute().body();
        // Verify some fields are correct
        assertNotNull(plan.getGuid());
        assertNotNull(plan.getModifiedOn());
        assertNotNull(plan.getVersion());
        assertEquals("A/B Test Schedule Plan", plan.getLabel());

        // Update
        SchedulePlan simplePlan = Tests.getSimpleSchedulePlan();
        plan.setStrategy(simplePlan.getStrategy());

        GuidVersionHolder newKeys = newSchedulesApi.updateSchedulePlan(plan.getGuid(), plan).execute().body();
        assertNotEquals("Version should be updated", keys.getVersion(), newKeys.getVersion());
        assertTrue(newKeys.getVersion() > plan.getVersion());

        // Get
        plan = newSchedulesApi.getSchedulePlan(keys.getGuid()).execute().body();
        assertEquals("Strategy type has been changed", "SimpleScheduleStrategy",
                plan.getStrategy().getClass().getSimpleName());

        ScheduleList schedules = usersApi.getSchedulesV1().execute().body();
        assertTrue("Schedules exist", !schedules.getItems().isEmpty());

        // Logical delete
        newSchedulesApi.deleteSchedulePlan(keys.getGuid(), false).execute();
        
        SchedulePlan retrieved = newSchedulesApi.getSchedulePlan(keys.getGuid()).execute().body();
        assertTrue(retrieved.isDeleted());
        
        // Does not include retrieved, which has been logically deleted
        SchedulePlanList withoutDeleted = newSchedulesApi.getSchedulePlans(false).execute().body();
        assertTrue(withoutDeleted.getItems().stream().noneMatch(onePlan -> onePlan.getGuid().equals(retrieved.getGuid())));
        
        // Does include retrieved, which has been logically deleted
        SchedulePlanList withDeleted = newSchedulesApi.getSchedulePlans(true).execute().body();
        assertTrue(withDeleted.getItems().stream().anyMatch(onePlan -> onePlan.getGuid().equals(retrieved.getGuid())));
        
        // Physical delete
        admin.getClient(SchedulesV1Api.class).deleteSchedulePlan(keys.getGuid(), true).execute();
        
        // It is now not in the list.
        withDeleted = newSchedulesApi.getSchedulePlans(true).execute().body();
        assertFalse(withDeleted.getItems().stream().anyMatch(onePlan -> onePlan.getGuid().equals(retrieved.getGuid())));

        try {
            newSchedulesApi.getSchedulePlan(keys.getGuid()).execute();
            fail("Should have thrown an exception because plan was deleted");
        } catch (EntityNotFoundException e) {
            assertEquals("Returns 404 Not Found", 404, e.getStatusCode());
        }
    }

    @Test
    public void criteriaScheduleStrategyPlanCRUD() throws Exception {
        SchedulePlan retrievedPlan = null;
        try {
            // Create plan with a criteria strategy
            Schedule schedule1 = new Schedule();
            schedule1.setLabel("Task 1");
            schedule1.setScheduleType(ScheduleType.ONCE);
            
            TaskReference ref = new TaskReference();
            ref.setIdentifier("task:AAA");
            
            Activity activity = new Activity();
            activity.setLabel("Do task");
            activity.setTask(ref);
            schedule1.setActivities(Lists.newArrayList(activity));
            
            Schedule schedule2 = new Schedule();
            schedule2.setLabel("Task 2");
            schedule2.setScheduleType(ScheduleType.ONCE);
            
            ref = new TaskReference();
            ref.setIdentifier("task:BBB");
            
            activity = new Activity();
            activity.setLabel("Do task");
            activity.setTask(ref);
            schedule2.setActivities(Lists.newArrayList(activity));
            
            Criteria criteria1 = new Criteria();
            criteria1.setMinAppVersions(ImmutableMap.of("Android", 2));
            criteria1.setMaxAppVersions(ImmutableMap.of("Android", 5));
            criteria1.setAllOfGroups(ImmutableList.of());
            criteria1.setNoneOfGroups(ImmutableList.of());
            criteria1.setAllOfStudyIds(ImmutableList.of());
            criteria1.setNoneOfStudyIds(ImmutableList.of());
            
            Criteria criteria2 = new Criteria();
            criteria2.setMinAppVersions(ImmutableMap.of("Android", 6));
            criteria2.setMaxAppVersions(ImmutableMap.of("Android", 10));
            criteria2.setAllOfGroups(ImmutableList.of());
            criteria2.setNoneOfGroups(ImmutableList.of());
            criteria2.setAllOfStudyIds(ImmutableList.of());
            criteria2.setNoneOfStudyIds(ImmutableList.of());
            
            ScheduleCriteria scheduleCriteria1 = new ScheduleCriteria();
            scheduleCriteria1.setCriteria(criteria1);
            scheduleCriteria1.setSchedule(schedule1);
            
            ScheduleCriteria scheduleCriteria2 = new ScheduleCriteria();
            scheduleCriteria2.setCriteria(criteria2);
            scheduleCriteria2.setSchedule(schedule2);
            
            CriteriaScheduleStrategy strategy = new CriteriaScheduleStrategy();
            strategy.setScheduleCriteria(Lists.newArrayList(scheduleCriteria1, scheduleCriteria2));
            strategy.setType("CriteriaScheduleStrategy");
            
            SchedulePlan plan = new SchedulePlan();
            plan.setLabel("Criteria schedule plan");
            plan.setStrategy(strategy);

            GuidVersionHolder keys = schedulesApi.createSchedulePlan(plan).execute().body();
            retrievedPlan = schedulesApi.getSchedulePlan(keys.getGuid()).execute().body();
            
            assertTrue(retrievedPlan.getStrategy() instanceof CriteriaScheduleStrategy);
            
            CriteriaScheduleStrategy retrievedStrategy = (CriteriaScheduleStrategy)retrievedPlan.getStrategy();
            assertEquals(2, retrievedStrategy.getScheduleCriteria().size());
            updateScheduleCriteria(scheduleCriteria1, retrievedStrategy.getScheduleCriteria().get(0));
            updateScheduleCriteria(scheduleCriteria2, retrievedStrategy.getScheduleCriteria().get(1));
            
            assertEquals(scheduleCriteria1, retrievedStrategy.getScheduleCriteria().get(0));
            assertEquals(scheduleCriteria2, retrievedStrategy.getScheduleCriteria().get(1));
        } finally {
            if (retrievedPlan != null) {
                admin.getClient(SchedulesV1Api.class).deleteSchedulePlan(retrievedPlan.getGuid(), true).execute();
            }
        }
    }
    
    @Test
    public void invalidPlanReturns400Error() throws Exception {
        try {
            SchedulePlan plan = Tests.getABTestSchedulePlan();
            plan.setStrategy(null);
            schedulesApi.createSchedulePlan(plan).execute();
            fail("Plan was invalid and should have thrown an exception");
        } catch (InvalidEntityException e) {
            assertEquals("Error comes back as 400 Bad Request", 400, e.getStatusCode());
            assertTrue("There is a strategy-specific error", e.getErrors().get("strategy").size() > 0);
        }
    }

    @Test
    public void planCanPointToPublishedSurvey() throws Exception {
        GuidCreatedOnVersionHolder surveyKeys = null;
        GuidVersionHolder keys = null;
        try {
            Survey survey = TestSurvey.getSurvey(SchedulePlanTest.class);
            surveyKeys = surveysApi.createSurvey(survey).execute().body();

            // Can we point to the most recently published survey, rather than a specific version?
            SchedulePlan plan = Tests.getSimpleSchedulePlan();
            SimpleScheduleStrategy strategy = (SimpleScheduleStrategy) plan.getStrategy();

            SurveyReference ref = new SurveyReference();
            ref.setGuid(surveyKeys.getGuid());
            ref.setCreatedOn(surveyKeys.getCreatedOn());
            
            Activity activity = new Activity();
            activity.setLabel("Test");
            activity.setSurvey(ref);
            activity.setActivityType(ActivityType.SURVEY);
            assertEquals(ActivityType.SURVEY, activity.getActivityType());

            strategy.getSchedule().getActivities().clear();
            strategy.getSchedule().getActivities().add(activity);

            keys = schedulesApi.createSchedulePlan(plan).execute().body();
            SchedulePlan newPlan = schedulesApi.getSchedulePlan(keys.getGuid()).execute().body();

            // Update the fields we expect to be updated on the server
            Tests.getActivitiesFromSimpleStrategy(plan).set(0, Tests.getActivityFromSimpleStrategy(newPlan));

            Tests.setVariableValueInObject(plan, "type", "SchedulePlan");
            Tests.setVariableValueInObject(plan, "modifiedOn", newPlan.getModifiedOn());
            Tests.setVariableValueInObject(strategy.getSchedule(), "type", "Schedule");
            strategy.getSchedule().setPersistent(false);
            plan.setVersion(newPlan.getVersion());
            plan.setGuid(newPlan.getGuid());
            
            plan.setDeleted(false); // For equality, this has to be set
            assertEquals(plan, newPlan);
        } finally {
            if (keys != null) {
                admin.getClient(SchedulesV1Api.class).deleteSchedulePlan(keys.getGuid(), true).execute();
            }
            if (surveyKeys != null) {
                SurveysApi surveysApi = admin.getClient(SurveysApi.class);
                surveysApi.deleteSurvey(surveyKeys.getGuid(), surveyKeys.getCreatedOn(), true).execute();
            }
        }
    }

    /**
     * ScheduleCriteria should be equal, *except* that the server has added a number of fields to the object, 
     * including type and GUIDs. Copy these values over to the original object, using reflection where necessary. 
     */
    private void updateScheduleCriteria(ScheduleCriteria original, ScheduleCriteria updated) throws Exception {
        Tests.setVariableValueInObject(original, "type", "ScheduleCriteria");
        
        Schedule originalSchedule = original.getSchedule();
        Tests.setVariableValueInObject(originalSchedule, "type", "Schedule");
        
        originalSchedule.setPersistent(updated.getSchedule().isPersistent());
        
        Activity originalActivity = originalSchedule.getActivities().get(0);
        originalActivity.setGuid(updated.getSchedule().getActivities().get(0).getGuid());
        originalActivity.setActivityType(ActivityType.TASK);
        Tests.setVariableValueInObject(originalActivity, "type", "Activity");
        Tests.setVariableValueInObject(originalActivity.getTask(), "type", "TaskReference");
        
        Tests.setVariableValueInObject(original.getCriteria(), "type", "Criteria");
    }
}
