package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.sagebionetworks.bridge.sdk.integration.TestSurvey.BOOLEAN_ID;
import static org.sagebionetworks.bridge.sdk.integration.TestSurvey.DATETIME_ID;
import static org.sagebionetworks.bridge.sdk.integration.TestSurvey.DATE_ID;
import static org.sagebionetworks.bridge.sdk.integration.TestSurvey.DECIMAL_ID;
import static org.sagebionetworks.bridge.sdk.integration.TestSurvey.DURATION_ID;
import static org.sagebionetworks.bridge.sdk.integration.TestSurvey.INTEGER_ID;
import static org.sagebionetworks.bridge.sdk.integration.TestSurvey.MULTIVALUE_ID;
import static org.sagebionetworks.bridge.sdk.integration.TestSurvey.TIME_ID;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.sagebionetworks.bridge.sdk.Roles;
import org.sagebionetworks.bridge.sdk.Session;
import org.sagebionetworks.bridge.sdk.SurveyClient;
import org.sagebionetworks.bridge.sdk.integration.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.sdk.UserClient;
import org.sagebionetworks.bridge.sdk.exceptions.PublishedSurveyException;
import org.sagebionetworks.bridge.sdk.exceptions.UnauthorizedException;
import org.sagebionetworks.bridge.sdk.models.ResourceList;
import org.sagebionetworks.bridge.sdk.models.holders.GuidCreatedOnVersionHolder;
import org.sagebionetworks.bridge.sdk.models.holders.SimpleGuidCreatedOnVersionHolder;
import org.sagebionetworks.bridge.sdk.models.surveys.Constraints;
import org.sagebionetworks.bridge.sdk.models.surveys.DataType;
import org.sagebionetworks.bridge.sdk.models.surveys.DateTimeConstraints;
import org.sagebionetworks.bridge.sdk.models.surveys.Image;
import org.sagebionetworks.bridge.sdk.models.surveys.StringConstraints;
import org.sagebionetworks.bridge.sdk.models.surveys.Survey;
import org.sagebionetworks.bridge.sdk.models.surveys.SurveyElement;
import org.sagebionetworks.bridge.sdk.models.surveys.SurveyInfoScreen;
import org.sagebionetworks.bridge.sdk.models.surveys.SurveyQuestion;
import org.sagebionetworks.bridge.sdk.models.surveys.SurveyQuestionOption;
import org.sagebionetworks.bridge.sdk.models.surveys.SurveyRule;
import org.sagebionetworks.bridge.sdk.models.surveys.UiHint;

@SuppressWarnings("Convert2streamapi")
public class SurveyTest {
    private static final Logger LOG = LoggerFactory.getLogger(SurveyTest.class);

    private static TestUser developer;
    private static TestUser user;
    private static TestUser worker;

    // We use SimpleGuidCreatedOnVersionHolder, because we need to use an immutable version holder, to ensure we're
    // deleting the correct surveys.
    private Set<SimpleGuidCreatedOnVersionHolder> surveysToDelete;

    @BeforeClass
    public static void beforeClass() {
        developer = TestUserHelper.createAndSignInUser(SurveyTest.class, false, Roles.DEVELOPER);
        user = TestUserHelper.createAndSignInUser(SurveyTest.class, true);
        worker = TestUserHelper.createAndSignInUser(SurveyTest.class, false, Roles.WORKER);
    }

    @Before
    public void before() {
        // Init surveys to delete set. It's not clear whether JUnit will re-init member vars between each method, so we
        // do it here just to be clear.
        surveysToDelete = new HashSet<>();
    }

    @After
    public void after() {
        // cleanup surveys
        Session session = TestUserHelper.getSignedInAdmin().getSession();
        SurveyClient adminClient = session.getSurveyClient();
        for (SimpleGuidCreatedOnVersionHolder oneSurvey : surveysToDelete) {
            try {
                adminClient.deleteSurveyPermanently(oneSurvey);
            } catch (RuntimeException ex) {
                LOG.error("Error deleting survey=" + oneSurvey + ": " + ex.getMessage(), ex);
            }
        }
    }

    @AfterClass
    public static void deleteDeveloper() {
        if (developer != null) {
            developer.signOutAndDeleteUser();
        }
    }

    @AfterClass
    public static void deleteUser() {
        if (user != null) {
            user.signOutAndDeleteUser();
        }
    }

    @AfterClass
    public static void deleteWorker() {
        if (worker != null) {
            worker.signOutAndDeleteUser();
        }
    }

    @Test(expected=UnauthorizedException.class)
    public void cannotSubmitAsNormalUser() {
        user.getSession().getSurveyClient().getAllSurveysMostRecent();
    }

    @Test
    public void saveAndRetrieveSurvey() {
        SurveyClient surveyClient = developer.getSession().getSurveyClient();
        GuidCreatedOnVersionHolder key = createSurvey(surveyClient, TestSurvey.getSurvey(SurveyTest.class));
        Survey survey = surveyClient.getSurvey(key);

        List<SurveyElement> questions = survey.getElements();
        String prompt = ((SurveyQuestion)questions.get(1)).getPrompt();
        assertEquals("Prompt is correct.", "When did you last have a medical check-up?", prompt);
        surveyClient.publishSurvey(key);
        
        UserClient userClient = user.getSession().getUserClient();
        survey = userClient.getSurveyMostRecentlyPublished(key.getGuid());
        // And again, correct
        questions = survey.getElements();
        prompt = ((SurveyQuestion)questions.get(1)).getPrompt();
        assertEquals("Prompt is correct.", "When did you last have a medical check-up?", prompt);
    }

    @Test
    public void createVersionPublish() {
        SurveyClient surveyClient = developer.getSession().getSurveyClient();

        Survey survey = TestSurvey.getSurvey(SurveyTest.class);
        assertNull(survey.getGuid());
        assertNull(survey.getVersion());
        assertNull(survey.getCreatedOn());
        GuidCreatedOnVersionHolder key = createSurvey(surveyClient, survey);
        assertNotNull(survey.getGuid());
        assertNotNull(survey.getVersion());
        assertNotNull(survey.getCreatedOn());
        
        GuidCreatedOnVersionHolder laterKey = versionSurvey(surveyClient, key);
        assertNotEquals("Version has been updated.", key.getCreatedOn(), laterKey.getCreatedOn());

        survey = surveyClient.getSurvey(laterKey.getGuid(), laterKey.getCreatedOn());
        assertFalse("survey is not published.", survey.isPublished());

        surveyClient.publishSurvey(survey);
        survey = surveyClient.getSurvey(survey.getGuid(), survey.getCreatedOn());
        assertTrue("survey is now published.", survey.isPublished());
    }

    @Test
    public void getAllVersionsOfASurvey() {
        SurveyClient surveyClient = developer.getSession().getSurveyClient();

        GuidCreatedOnVersionHolder key = createSurvey(surveyClient, TestSurvey.getSurvey(SurveyTest.class));
        key = versionSurvey(surveyClient, key);

        int count = surveyClient.getSurveyAllRevisions(key.getGuid()).getTotal();
        assertEquals("Two versions for this survey.", 2, count);
    }

    @Test
    public void canGetMostRecentOrRecentlyPublishedSurvey() throws InterruptedException {
        SurveyClient surveyClient = developer.getSession().getSurveyClient();

        GuidCreatedOnVersionHolder key = createSurvey(surveyClient, TestSurvey.getSurvey(SurveyTest.class));
        key = versionSurvey(surveyClient, key);
        key = versionSurvey(surveyClient, key);

        GuidCreatedOnVersionHolder key1 = createSurvey(surveyClient, TestSurvey.getSurvey(SurveyTest.class));
        key1 = versionSurvey(surveyClient, key1);
        key1 = versionSurvey(surveyClient, key1);

        GuidCreatedOnVersionHolder key2 = createSurvey(surveyClient, TestSurvey.getSurvey(SurveyTest.class));
        key2 = versionSurvey(surveyClient, key2);
        key2 = versionSurvey(surveyClient, key2);

        // Sleep to clear eventual consistency problems.
        Thread.sleep(2000);
        ResourceList<Survey> recentSurveys = surveyClient.getAllSurveysMostRecent();
        containsAll(recentSurveys.getItems(), key, key1, key2);

        key = surveyClient.publishSurvey(key);
        key2 = surveyClient.publishSurvey(key2);

        Thread.sleep(2000);
        ResourceList<Survey> publishedSurveys = surveyClient.getAllSurveysMostRecentlyPublished();
        containsAll(publishedSurveys.getItems(), key, key2);
    }

    @Test
    public void canUpdateASurveyAndTypesAreCorrect() {
        SurveyClient surveyClient = developer.getSession().getSurveyClient();
        
        GuidCreatedOnVersionHolder key = createSurvey(surveyClient, TestSurvey.getSurvey(SurveyTest.class));
        Survey survey = surveyClient.getSurvey(key.getGuid(), key.getCreatedOn());
        assertEquals("Type is Survey.", survey.getClass(), Survey.class);

        List<SurveyElement> questions = survey.getElements();
        assertEquals("Type is SurveyQuestion.", questions.get(0).getClass(), SurveyQuestion.class);

        assertEquals("Type is BooleanConstraints.", DataType.BOOLEAN, getConstraints(survey, BOOLEAN_ID).getDataType());
        assertEquals("Type is DateConstraints", DataType.DATE, getConstraints(survey, DATE_ID).getDataType());
        assertEquals("Type is DateTimeConstraints", DataType.DATETIME, getConstraints(survey, DATETIME_ID).getDataType());
        assertEquals("Type is DecimalConstraints", DataType.DECIMAL, getConstraints(survey, DECIMAL_ID).getDataType());
        Constraints intCon = getConstraints(survey, INTEGER_ID);
        assertEquals("Type is IntegerConstraints", DataType.INTEGER, intCon.getDataType());
        assertEquals("Has a rule of type SurveyRule", SurveyRule.class, intCon.getRules().get(0).getClass());
        assertEquals("Type is DurationConstraints", DataType.DURATION, getConstraints(survey, DURATION_ID).getDataType());
        assertEquals("Type is TimeConstraints", DataType.TIME, getConstraints(survey, TIME_ID).getDataType());
        Constraints multiCon = getConstraints(survey, MULTIVALUE_ID);
        assertTrue("Type is MultiValueConstraints", multiCon.getAllowMultiple());
        assertEquals("Type is SurveyQuestionOption", SurveyQuestionOption.class, multiCon.getEnumeration().get(0).getClass());

        survey.setName("New name");
        GuidCreatedOnVersionHolder holder = surveyClient.updateSurvey(survey);
        // These should be updated.
        assertEquals(holder.getVersion(), survey.getVersion());
        
        survey = surveyClient.getSurvey(survey.getGuid(), survey.getCreatedOn());
        assertEquals("Name should have changed.", survey.getName(), "New name");
    }

    @Test
    public void dateBasedConstraintsPersistedCorrectly() {
        SurveyClient surveyClient = developer.getSession().getSurveyClient();

        GuidCreatedOnVersionHolder key = createSurvey(surveyClient, TestSurvey.getSurvey(SurveyTest.class));
        Survey survey = surveyClient.getSurvey(key);

        DateTimeConstraints dateCon = (DateTimeConstraints)getConstraints(survey, DATETIME_ID);
        DateTime earliest = dateCon.getEarliestValue();
        DateTime latest = dateCon.getLatestValue();
        assertNotNull("Earliest has been set", earliest);
        assertEquals("Date is correct", DateTime.parse("2000-01-01").withZone(DateTimeZone.UTC), earliest);
        assertNotNull("Latest has been set", latest);
        assertEquals("Date is correct", DateTime.parse("2020-12-31").withZone(DateTimeZone.UTC), latest);
    }

    @Test
    public void researcherCannotUpdatePublishedSurvey() {
        SurveyClient surveyClient = developer.getSession().getSurveyClient();
        Survey survey = TestSurvey.getSurvey(SurveyTest.class);
        GuidCreatedOnVersionHolder keys = createSurvey(surveyClient, survey);
        keys = surveyClient.publishSurvey(keys);
        survey.setGuidCreatedOnVersionHolder(keys);
        
        survey.setName("This is a new name");

        try {
            surveyClient.updateSurvey(survey);
            fail("attempting to update a published survey should throw an exception.");
        } catch(PublishedSurveyException e) {
            // expected exception
        }
    }

    @Test
    public void canGetMostRecentlyPublishedSurveyWithoutTimestamp() {
        SurveyClient surveyClient = developer.getSession().getSurveyClient();
        Survey survey = TestSurvey.getSurvey(SurveyTest.class);

        GuidCreatedOnVersionHolder key = createSurvey(surveyClient, survey);

        GuidCreatedOnVersionHolder key1 = versionSurvey(surveyClient, key);
        GuidCreatedOnVersionHolder key2 = versionSurvey(surveyClient, key1);
        surveyClient.publishSurvey(key2);
        versionSurvey(surveyClient, key2);

        Survey found = surveyClient.getSurveyMostRecentlyPublished(key2.getGuid());
        assertEquals("This returns the right version", key2.getCreatedOn(), found.getCreatedOn());
        assertNotEquals("And these are really different versions", key.getCreatedOn(), found.getCreatedOn());
    }

    @Test
    public void canCallMultiOperationMethodToMakeSurveyUpdate() {
        SurveyClient surveyClient = developer.getSession().getSurveyClient();
        Survey survey = TestSurvey.getSurvey(SurveyTest.class);

        GuidCreatedOnVersionHolder keys = createSurvey(surveyClient, survey);

        Survey existingSurvey = surveyClient.getSurvey(keys);
        existingSurvey.setName("This is an update test");

        GuidCreatedOnVersionHolder holder = surveyClient.versionUpdateAndPublishSurvey(existingSurvey, true);
        surveysToDelete.add(new SimpleGuidCreatedOnVersionHolder(holder));

        ResourceList<Survey> allRevisions = surveyClient.getSurveyAllRevisions(keys.getGuid());
        assertEquals("There are now two versions", 2, allRevisions.getTotal());

        Survey mostRecent = surveyClient.getSurveyMostRecentlyPublished(existingSurvey.getGuid());
        assertEquals(mostRecent.getGuid(), holder.getGuid());
        assertEquals(mostRecent.getCreatedOn(), holder.getCreatedOn());
        assertEquals(mostRecent.getVersion(), holder.getVersion());
        assertEquals("The latest has a new title", "This is an update test", allRevisions.get(0).getName());
    }
    
    @Test
    public void canSaveAndRetrieveInfoScreen() {
        SurveyClient surveyClient = developer.getSession().getSurveyClient();
        
        Survey survey = new Survey();
        survey.setIdentifier("test-survey");
        survey.setName("Test study");
        
        SurveyInfoScreen screen = new SurveyInfoScreen();
        screen.setIdentifier("foo");
        screen.setTitle("Title");
        screen.setPrompt("Prompt");
        screen.setPromptDetail("Prompt detail");
        Image image = new Image("https://pbs.twimg.com/profile_images/1642204340/ReferencePear_400x400.PNG", 400, 400);
        screen.setImage(image);
        survey.getElements().add(screen);
        
        // Add a question too just to verify that's okay
        SurveyQuestion question = new SurveyQuestion();
        question.setIdentifier("bar");
        question.setPrompt("Prompt");
        question.setFireEvent(true);
        question.setUiHint(UiHint.TEXTFIELD);
        question.setConstraints(new StringConstraints());
        survey.getElements().add(question);
        
        GuidCreatedOnVersionHolder keys = createSurvey(surveyClient, survey);
        
        Survey newSurvey = surveyClient.getSurvey(keys);
        assertEquals(2, newSurvey.getElements().size());
        
        SurveyInfoScreen newScreen = (SurveyInfoScreen)newSurvey.getElements().get(0);
        
        assertEquals(SurveyInfoScreen.class, newScreen.getClass());
        assertNotNull(newScreen.getGuid());
        assertEquals("foo", newScreen.getIdentifier());
        assertEquals("Title", newScreen.getTitle());
        assertEquals("Prompt", newScreen.getPrompt());
        assertEquals("Prompt detail", newScreen.getPromptDetail());
        assertEquals("https://pbs.twimg.com/profile_images/1642204340/ReferencePear_400x400.PNG", newScreen.getImage().getSource());
        assertEquals(400, newScreen.getImage().getWidth());
        assertEquals(400, newScreen.getImage().getHeight());
        
        SurveyQuestion newQuestion = (SurveyQuestion)newSurvey.getElements().get(1);
        assertEquals(SurveyQuestion.class, newQuestion.getClass());
        assertNotNull(newQuestion.getGuid());
        assertEquals("bar", newQuestion.getIdentifier());
        assertEquals("Prompt", newQuestion.getPrompt());
        assertEquals(true, newQuestion.getFireEvent());
        assertEquals(UiHint.TEXTFIELD, newQuestion.getUIHint());
    }

    @Test
    public void workerCanGetSurveys() throws Exception {
        // One of the key functionalities of worker accounts is that they can get surveys across studies.
        // Unfortunately, integration tests are set up so it's difficult to test across studies. As such, we do all our
        // testing in the API study to test basic functionality.

        // Create two surveys with two published versions.
        SurveyClient surveyClient = developer.getSession().getSurveyClient();

        GuidCreatedOnVersionHolder survey1aKeys = createSurvey(surveyClient, TestSurvey.getSurvey(SurveyTest.class));
        surveyClient.publishSurvey(survey1aKeys);
        GuidCreatedOnVersionHolder survey1bKeys = versionSurvey(surveyClient, survey1aKeys);
        surveyClient.publishSurvey(survey1bKeys);

        GuidCreatedOnVersionHolder survey2aKeys = createSurvey(surveyClient, TestSurvey.getSurvey(SurveyTest.class));
        surveyClient.publishSurvey(survey2aKeys);
        GuidCreatedOnVersionHolder survey2bKeys = versionSurvey(surveyClient, survey2aKeys);
        surveyClient.publishSurvey(survey2bKeys);

        // Sleep to clear eventual consistency problems.
        SurveyClient workerSurveyClient = worker.getSession().getSurveyClient();
        Thread.sleep(2000);

        // The surveys we created were just dummies. Just check that the surveys are not null and that the keys match.
        Survey survey1a = workerSurveyClient.getSurvey(survey1aKeys);
        Survey survey1b = workerSurveyClient.getSurvey(survey1bKeys);
        Survey survey2a = workerSurveyClient.getSurvey(survey2aKeys);
        Survey survey2b = workerSurveyClient.getSurvey(survey2bKeys);

        assertKeysEqual(survey1aKeys, survey1a);
        assertKeysEqual(survey1bKeys, survey1b);
        assertKeysEqual(survey2aKeys, survey2a);
        assertKeysEqual(survey2bKeys, survey2b);

        // Get using the guid+createdOn API for completeness.
        Survey survey1aAgain = workerSurveyClient.getSurvey(survey1aKeys.getGuid(), survey1aKeys.getCreatedOn());
        assertKeysEqual(survey1aKeys, survey1aAgain);

        // We only expect the most recently published versions, namely 1b and 2b.
        ResourceList<Survey> surveyResourceList = workerSurveyClient.getAllSurveysMostRecentlyPublished(Tests.TEST_KEY);
        containsAll(surveyResourceList.getItems(), survey1b, survey2b);
    }

    @Test
    public void verifyEndSurveyRule() throws Exception {
        SurveyClient surveyClient = developer.getSession().getSurveyClient();
        
        Survey survey = new Survey();
        survey.setIdentifier("test-survey");
        survey.setName("Test study");

        SurveyRule rule = new SurveyRule.Builder().withOperator(SurveyRule.Operator.EQ).withValue("true")
                .withEndSurvey(Boolean.TRUE).build();
        
        StringConstraints constraints = new StringConstraints();
        constraints.getRules().add(rule); // end survey

        SurveyQuestion question = new SurveyQuestion();
        question.setIdentifier("bar");
        question.setPrompt("Prompt");
        question.setFireEvent(true);
        question.setUiHint(UiHint.TEXTFIELD);
        question.setConstraints(constraints);
        survey.getElements().add(question);
        
        GuidCreatedOnVersionHolder keys = createSurvey(surveyClient, survey);
        
        Survey retrieved = surveyClient.getSurvey(keys);
        SurveyRule retrievedRule = getConstraints(retrieved, "bar").getRules().get(0);
        
        assertEquals(Boolean.TRUE, retrievedRule.getEndSurvey());
        assertEquals("true", retrievedRule.getValue());
        assertEquals(SurveyRule.Operator.EQ, retrievedRule.getOperator());
        assertNull(retrievedRule.getSkipToTarget());
    }
    
    private Constraints getConstraints(Survey survey, String id) {
        return ((SurveyQuestion)survey.getElementByIdentifier(id)).getConstraints();
    }

    private void containsAll(List<Survey> surveys, GuidCreatedOnVersionHolder... keys) {
        // The server may have more surveys than the ones we created, if more than one person is running tests
        // (unit or integration), or if there are persistent tests unrelated to this test.
        assertTrue("Server returned enough items", surveys.size() >= keys.length);

        // Check that we can find all of our expected surveys. Use the immutable Simple holder, so we can use a
        // set and contains().
        Set<SimpleGuidCreatedOnVersionHolder> surveyKeySet = new HashSet<>();
        for (Survey survey : surveys) {
            surveyKeySet.add(new SimpleGuidCreatedOnVersionHolder(survey));
        }
        for (GuidCreatedOnVersionHolder key : keys) {
            SimpleGuidCreatedOnVersionHolder simpleKey = new SimpleGuidCreatedOnVersionHolder(key);
            assertTrue("Survey contains expected key: " + simpleKey, surveyKeySet.contains(simpleKey));
        }
    }

    // Helper methods to ensure we always record these calls for cleanup

    private GuidCreatedOnVersionHolder createSurvey(SurveyClient surveyClient, Survey survey) {
        GuidCreatedOnVersionHolder versionHolder = surveyClient.createSurvey(survey);
        surveysToDelete.add(new SimpleGuidCreatedOnVersionHolder(versionHolder));
        return versionHolder;
    }

    private GuidCreatedOnVersionHolder versionSurvey(SurveyClient surveyClient, GuidCreatedOnVersionHolder survey) {
        GuidCreatedOnVersionHolder versionHolder = surveyClient.versionSurvey(survey);
        surveysToDelete.add(new SimpleGuidCreatedOnVersionHolder(versionHolder));
        return versionHolder;
    }

    private static void assertKeysEqual(GuidCreatedOnVersionHolder keys, Survey survey) {
        assertEquals(keys.getGuid(), survey.getGuid());
        assertEquals(keys.getCreatedOn(), survey.getCreatedOn());
    }
}
