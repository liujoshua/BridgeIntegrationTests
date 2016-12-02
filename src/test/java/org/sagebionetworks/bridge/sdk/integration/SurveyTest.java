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
import java.util.Objects;
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

import org.sagebionetworks.bridge.sdk.integration.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.rest.api.SurveysApi;
import org.sagebionetworks.bridge.rest.exceptions.PublishedSurveyException;
import org.sagebionetworks.bridge.rest.exceptions.UnauthorizedException;
import org.sagebionetworks.bridge.rest.model.Constraints;
import org.sagebionetworks.bridge.rest.model.DataType;
import org.sagebionetworks.bridge.rest.model.DateTimeConstraints;
import org.sagebionetworks.bridge.rest.model.GuidCreatedOnVersionHolder;
import org.sagebionetworks.bridge.rest.model.Image;
import org.sagebionetworks.bridge.rest.model.MultiValueConstraints;
import org.sagebionetworks.bridge.rest.model.Operator;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.StringConstraints;
import org.sagebionetworks.bridge.rest.model.Survey;
import org.sagebionetworks.bridge.rest.model.SurveyElement;
import org.sagebionetworks.bridge.rest.model.SurveyInfoScreen;
import org.sagebionetworks.bridge.rest.model.SurveyList;
import org.sagebionetworks.bridge.rest.model.SurveyQuestion;
import org.sagebionetworks.bridge.rest.model.SurveyQuestionOption;
import org.sagebionetworks.bridge.rest.model.SurveyRule;
import org.sagebionetworks.bridge.rest.model.UIHint;

public class SurveyTest {
    private static final Logger LOG = LoggerFactory.getLogger(SurveyTest.class);
    
    private static TestUser developer;
    private static TestUser user;
    private static TestUser worker;

    // We use SimpleGuidCreatedOnVersionHolder, because we need to use an immutable version holder, to ensure we're
    // deleting the correct surveys.
    private Set<GuidCreatedOnVersionHolder> surveysToDelete;

    @BeforeClass
    public static void beforeClass() throws Exception {
        developer = TestUserHelper.createAndSignInUser(SurveyTest.class, false, Role.DEVELOPER);
        user = TestUserHelper.createAndSignInUser(SurveyTest.class, true);
        worker = TestUserHelper.createAndSignInUser(SurveyTest.class, false, Role.WORKER);
    }

    @Before
    public void before() {
        // Init surveys to delete set. It's not clear whether JUnit will re-init member vars between each method, so we
        // do it here just to be clear.
        surveysToDelete = new HashSet<>();
    }

    @After
    public void after() throws Exception {
        // cleanup surveys
        TestUser admin = TestUserHelper.getSignedInAdmin();
        
        SurveysApi surveysApi = admin.getClient(SurveysApi.class);
        for (GuidCreatedOnVersionHolder oneSurvey : surveysToDelete) {
            try {
                surveysApi.deleteSurvey(oneSurvey.getGuid(), oneSurvey.getCreatedOn(), true).execute();
            } catch (RuntimeException ex) {
                LOG.error("Error deleting survey=" + oneSurvey + ": " + ex.getMessage(), ex);
            }
        }
    }

    @AfterClass
    public static void deleteDeveloper() throws Exception {
        if (developer != null) {
            developer.signOutAndDeleteUser();
        }
    }

    @AfterClass
    public static void deleteUser() throws Exception {
        if (user != null) {
            user.signOutAndDeleteUser();
        }
    }

    @AfterClass
    public static void deleteWorker() throws Exception {
        if (worker != null) {
            worker.signOutAndDeleteUser();
        }
    }

    @Test(expected=UnauthorizedException.class)
    public void cannotSubmitAsNormalUser() throws Exception {
        user.getClient(SurveysApi.class).getMostRecentSurveys().execute().body();
    }

    @Test
    public void saveAndRetrieveSurvey() throws Exception {
        SurveysApi surveysApi = developer.getClient(SurveysApi.class);
        GuidCreatedOnVersionHolder key = createSurvey(surveysApi, TestSurvey.getSurvey(SurveyTest.class));
        Survey survey = surveysApi.getSurvey(key.getGuid(), key.getCreatedOn()).execute().body();

        List<SurveyElement> questions = survey.getElements();
        String prompt = ((SurveyQuestion)questions.get(1)).getPrompt();
        assertEquals("Prompt is correct.", "When did you last have a medical check-up?", prompt);
        surveysApi.publishSurvey(key.getGuid(), key.getCreatedOn(), false).execute();
        
        ForConsentedUsersApi usersApi = user.getClient(ForConsentedUsersApi.class);
        survey = usersApi.getPublishedSurveyVersion(key.getGuid()).execute().body();
        // And again, correct
        questions = survey.getElements();
        prompt = ((SurveyQuestion)questions.get(1)).getPrompt();
        assertEquals("Prompt is correct.", "When did you last have a medical check-up?", prompt);
    }

    @Test
    public void createVersionPublish() throws Exception {
        SurveysApi surveysApi = developer.getClient(SurveysApi.class);

        Survey survey = TestSurvey.getSurvey(SurveyTest.class);
        assertNull(survey.getGuid());
        assertNull(survey.getVersion());
        assertNull(survey.getCreatedOn());
        GuidCreatedOnVersionHolder key = createSurvey(surveysApi, survey);
        assertNotNull(key.getGuid());
        assertNotNull(key.getVersion());
        assertNotNull(key.getCreatedOn());
        
        GuidCreatedOnVersionHolder laterKey = versionSurvey(surveysApi, key);
        assertNotEquals("Version has been updated.", key.getCreatedOn(), laterKey.getCreatedOn());

        survey = surveysApi.getSurvey(laterKey.getGuid(), laterKey.getCreatedOn()).execute().body();
        assertFalse("survey is not published.", survey.getPublished());

        surveysApi.publishSurvey(survey.getGuid(), survey.getCreatedOn(), false).execute();
        survey = surveysApi.getSurvey(survey.getGuid(), survey.getCreatedOn()).execute().body();
        assertTrue("survey is now published.", survey.getPublished());
    }

    @Test
    public void getAllVersionsOfASurvey() throws Exception {
        SurveysApi surveysApi = developer.getClient(SurveysApi.class);

        GuidCreatedOnVersionHolder key = createSurvey(surveysApi, TestSurvey.getSurvey(SurveyTest.class));
        key = versionSurvey(surveysApi, key);
        
        int count = surveysApi.getAllVersionsOfSurvey(key.getGuid()).execute().body().getTotal();
        assertEquals("Two versions for this survey.", 2, count);
    }

    @Test
    public void canGetMostRecentOrRecentlyPublishedSurvey() throws Exception {
        SurveysApi surveysApi = developer.getClient(SurveysApi.class);

        GuidCreatedOnVersionHolder key = createSurvey(surveysApi, TestSurvey.getSurvey(SurveyTest.class));
        key = versionSurvey(surveysApi, key);
        key = versionSurvey(surveysApi, key);

        GuidCreatedOnVersionHolder key1 = createSurvey(surveysApi, TestSurvey.getSurvey(SurveyTest.class));
        key1 = versionSurvey(surveysApi, key1);
        key1 = versionSurvey(surveysApi, key1);

        GuidCreatedOnVersionHolder key2 = createSurvey(surveysApi, TestSurvey.getSurvey(SurveyTest.class));
        key2 = versionSurvey(surveysApi, key2);
        key2 = versionSurvey(surveysApi, key2);

        // Sleep to clear eventual consistency problems.
        Thread.sleep(2000);
        SurveyList recentSurveys = surveysApi.getMostRecentSurveys().execute().body();
        containsAll(recentSurveys.getItems(), key, key1, key2);

        key = surveysApi.publishSurvey(key.getGuid(), key.getCreatedOn(), false).execute().body();
        key2 = surveysApi.publishSurvey(key2.getGuid(), key2.getCreatedOn(), false).execute().body();

        Thread.sleep(2000);
        SurveyList publishedSurveys = surveysApi.getPublishedSurveys().execute().body();
        containsAll(publishedSurveys.getItems(), key, key2);
    }

    @Test
    public void canUpdateASurveyAndTypesAreCorrect() throws Exception {
        SurveysApi surveysApi = developer.getClient(SurveysApi.class);
        
        GuidCreatedOnVersionHolder key = createSurvey(surveysApi, TestSurvey.getSurvey(SurveyTest.class));
        Survey survey = surveysApi.getSurvey(key.getGuid(), key.getCreatedOn()).execute().body();
        assertEquals("Type is Survey.", survey.getClass(), Survey.class);

        List<SurveyElement> questions = survey.getElements();
        assertEquals("Type is SurveyQuestion.", SurveyQuestion.class, questions.get(0).getClass());

        assertEquals("Type is BooleanConstraints.", DataType.BOOLEAN, getConstraints(survey, BOOLEAN_ID).getDataType());
        assertEquals("Type is DateConstraints", DataType.DATE, getConstraints(survey, DATE_ID).getDataType());
        assertEquals("Type is DateTimeConstraints", DataType.DATETIME, getConstraints(survey, DATETIME_ID).getDataType());
        assertEquals("Type is DecimalConstraints", DataType.DECIMAL, getConstraints(survey, DECIMAL_ID).getDataType());
        Constraints intCon = getConstraints(survey, INTEGER_ID);
        assertEquals("Type is IntegerConstraints", DataType.INTEGER, intCon.getDataType());
        assertEquals("Has a rule of type SurveyRule", SurveyRule.class, intCon.getRules().get(0).getClass());
        assertEquals("Type is DurationConstraints", DataType.DURATION, getConstraints(survey, DURATION_ID).getDataType());
        assertEquals("Type is TimeConstraints", DataType.TIME, getConstraints(survey, TIME_ID).getDataType());
        MultiValueConstraints multiCon = (MultiValueConstraints)getConstraints(survey, MULTIVALUE_ID);
        assertTrue("Type is MultiValueConstraints", multiCon.getAllowMultiple());
        assertEquals("Type is SurveyQuestionOption", SurveyQuestionOption.class, multiCon.getEnumeration().get(0).getClass());

        survey.setName("New name");
        GuidCreatedOnVersionHolder holder = surveysApi.updateSurvey(survey.getGuid(), survey.getCreatedOn(), survey).execute().body();
        // Should be incremented.
        assertTrue(holder.getVersion() > survey.getVersion());
        
        survey = surveysApi.getSurvey(survey.getGuid(), survey.getCreatedOn()).execute().body();
        assertEquals("Name should have changed.", survey.getName(), "New name");
    }
    
    @Test
    public void dateBasedConstraintsPersistedCorrectly() throws Exception {
        SurveysApi surveysApi = developer.getClient(SurveysApi.class);

        GuidCreatedOnVersionHolder key = createSurvey(surveysApi, TestSurvey.getSurvey(SurveyTest.class));
        Survey survey = surveysApi.getSurvey(key.getGuid(), key.getCreatedOn()).execute().body();

        DateTimeConstraints dateCon = (DateTimeConstraints)getConstraints(survey, DATETIME_ID);
        DateTime earliest = dateCon.getEarliestValue();
        DateTime latest = dateCon.getLatestValue();
        assertNotNull("Earliest has been set", earliest);
        assertEquals("Date is correct", DateTime.parse("2000-01-01").withZone(DateTimeZone.UTC), earliest);
        assertNotNull("Latest has been set", latest);
        assertEquals("Date is correct", DateTime.parse("2020-12-31").withZone(DateTimeZone.UTC), latest);
    }

    @Test
    public void researcherCannotUpdatePublishedSurvey() throws Exception {
        SurveysApi surveysApi = developer.getClient(SurveysApi.class);
        
        Survey survey = TestSurvey.getSurvey(SurveyTest.class);
        GuidCreatedOnVersionHolder keys = createSurvey(surveysApi, survey);
        keys = surveysApi.publishSurvey(keys.getGuid(), keys.getCreatedOn(), false).execute().body();
        
        survey.setName("This is a new name");
        survey.setVersion(keys.getVersion());
        try {
            surveysApi.updateSurvey(keys.getGuid(), keys.getCreatedOn(), survey).execute();
            fail("attempting to update a published survey should throw an exception.");
        } catch(PublishedSurveyException e) {
            // expected exception
        }
    }

    @Test
    public void canGetMostRecentlyPublishedSurveyWithoutTimestamp() throws Exception {
        SurveysApi surveysApi = developer.getClient(SurveysApi.class);
        
        Survey survey = TestSurvey.getSurvey(SurveyTest.class);

        GuidCreatedOnVersionHolder key = createSurvey(surveysApi, survey);

        GuidCreatedOnVersionHolder key1 = versionSurvey(surveysApi, key);
        GuidCreatedOnVersionHolder key2 = versionSurvey(surveysApi, key1);
        surveysApi.publishSurvey(key2.getGuid(), key2.getCreatedOn(), false).execute();
        versionSurvey(surveysApi, key2);

        Survey found = surveysApi.getPublishedSurveyVersion(key2.getGuid()).execute().body();
        assertEquals("This returns the right version", key2.getCreatedOn(), found.getCreatedOn());
        assertNotEquals("And these are really different versions", key.getCreatedOn(), found.getCreatedOn());
    }

    @Test
    public void canCallMultiOperationMethodToMakeSurveyUpdate() throws Exception {
        SurveysApi surveysApi = developer.getClient(SurveysApi.class);
        Survey survey = TestSurvey.getSurvey(SurveyTest.class);

        GuidCreatedOnVersionHolder keys = createSurvey(surveysApi, survey);

        Survey existingSurvey = surveysApi.getSurvey(keys.getGuid(), keys.getCreatedOn()).execute().body();
        existingSurvey.setName("This is an update test");

        keys = surveysApi.versionSurvey(existingSurvey.getGuid(), existingSurvey.getCreatedOn()).execute().body();
        
        existingSurvey.setVersion(keys.getVersion());
        keys = surveysApi.updateSurvey(keys.getGuid(), keys.getCreatedOn(), existingSurvey).execute().body();
        
        keys = surveysApi.publishSurvey(keys.getGuid(), keys.getCreatedOn(), false).execute().body();
        
        surveysToDelete.add(new MutableHolder(keys));

        SurveyList allRevisions = surveysApi.getAllVersionsOfSurvey(keys.getGuid()).execute().body();
        assertEquals("There are now two versions", (Integer)2, allRevisions.getTotal());

        Survey mostRecent = surveysApi.getPublishedSurveyVersion(existingSurvey.getGuid()).execute().body();
        assertEquals(mostRecent.getGuid(), keys.getGuid());
        assertEquals(mostRecent.getCreatedOn(), keys.getCreatedOn());
        assertEquals(mostRecent.getVersion(), keys.getVersion());
        assertEquals("The latest has a new title", "This is an update test", allRevisions.getItems().get(0).getName());
    }
    
    @Test
    public void canSaveAndRetrieveInfoScreen() throws Exception {
        SurveysApi surveysApi = developer.getClient(SurveysApi.class);
        
        Survey survey = new Survey();
        survey.setIdentifier("test-survey");
        survey.setName("Test study");
        
        SurveyInfoScreen screen = new SurveyInfoScreen();
        screen.setIdentifier("foo");
        screen.setTitle("Title");
        screen.setPrompt("Prompt");
        screen.setPromptDetail("Prompt detail");
        screen.setType("SurveyInfoScreen");
        
        Image image = new Image();
        image.setSource("https://pbs.twimg.com/profile_images/1642204340/ReferencePear_400x400.PNG");
        image.setHeight(400);
        image.setWidth(400);
        screen.setImage(image);
        survey.getElements().add(screen);
        
        // Add a question too just to verify that's okay
        SurveyQuestion question = new SurveyQuestion();
        question.setIdentifier("bar");
        question.setPrompt("Prompt");
        question.setUiHint(UIHint.TEXTFIELD);
        StringConstraints sc = new StringConstraints();
        sc.setDataType(DataType.STRING);
        question.setConstraints(sc);
        question.setType("SurveyQuestion");
        survey.getElements().add(question);
        
        GuidCreatedOnVersionHolder keys = createSurvey(surveysApi, survey);
        
        Survey newSurvey = surveysApi.getSurvey(keys.getGuid(), keys.getCreatedOn()).execute().body();
        assertEquals(2, newSurvey.getElements().size());
        
        SurveyInfoScreen newScreen = (SurveyInfoScreen)newSurvey.getElements().get(0);
        
        assertEquals(SurveyInfoScreen.class, newScreen.getClass());
        assertNotNull(newScreen.getGuid());
        assertEquals("foo", newScreen.getIdentifier());
        assertEquals("Title", newScreen.getTitle());
        assertEquals("Prompt", newScreen.getPrompt());
        assertEquals("Prompt detail", newScreen.getPromptDetail());
        assertEquals("https://pbs.twimg.com/profile_images/1642204340/ReferencePear_400x400.PNG", newScreen.getImage().getSource());
        assertEquals((Integer)400, newScreen.getImage().getWidth());
        assertEquals((Integer)400, newScreen.getImage().getHeight());
        
        SurveyQuestion newQuestion = (SurveyQuestion)newSurvey.getElements().get(1);
        assertEquals(SurveyQuestion.class, newQuestion.getClass());
        assertNotNull(newQuestion.getGuid());
        assertEquals("bar", newQuestion.getIdentifier());
        assertEquals("Prompt", newQuestion.getPrompt());
        assertEquals(UIHint.TEXTFIELD, newQuestion.getUiHint());
    }

    @Test
    public void workerCanGetSurveys() throws Exception {
        // One of the key functionalities of worker accounts is that they can get surveys across studies.
        // Unfortunately, integration tests are set up so it's difficult to test across studies. As such, we do all our
        // testing in the API study to test basic functionality.

        // Create two surveys with two published versions.
        SurveysApi surveysApi = developer.getClient(SurveysApi.class);

        GuidCreatedOnVersionHolder survey1aKeys = createSurvey(surveysApi, TestSurvey.getSurvey(SurveyTest.class));
        surveysApi.publishSurvey(survey1aKeys.getGuid(), survey1aKeys.getCreatedOn(), false).execute();
        GuidCreatedOnVersionHolder survey1bKeys = versionSurvey(surveysApi, survey1aKeys);
        surveysApi.publishSurvey(survey1bKeys.getGuid(), survey1bKeys.getCreatedOn(), false).execute();

        GuidCreatedOnVersionHolder survey2aKeys = createSurvey(surveysApi, TestSurvey.getSurvey(SurveyTest.class));
        surveysApi.publishSurvey(survey2aKeys.getGuid(), survey2aKeys.getCreatedOn(), false).execute();
        GuidCreatedOnVersionHolder survey2bKeys = versionSurvey(surveysApi, survey2aKeys);
        surveysApi.publishSurvey(survey2bKeys.getGuid(), survey2bKeys.getCreatedOn(), false).execute();

        // Sleep to clear eventual consistency problems.
        SurveysApi workerSurveyClient = worker.getClient(SurveysApi.class);
        Thread.sleep(2000);

        // The surveys we created were just dummies. Just check that the surveys are not null and that the keys match.
        Survey survey1a = workerSurveyClient.getSurvey(survey1aKeys.getGuid(), survey1aKeys.getCreatedOn()).execute().body();
        Survey survey1b = workerSurveyClient.getSurvey(survey1bKeys.getGuid(), survey1bKeys.getCreatedOn()).execute().body();
        Survey survey2a = workerSurveyClient.getSurvey(survey2aKeys.getGuid(), survey2aKeys.getCreatedOn()).execute().body();
        Survey survey2b = workerSurveyClient.getSurvey(survey2bKeys.getGuid(), survey2bKeys.getCreatedOn()).execute().body();

        assertKeysEqual(survey1aKeys, survey1a);
        assertKeysEqual(survey1bKeys, survey1b);
        assertKeysEqual(survey2aKeys, survey2a);
        assertKeysEqual(survey2bKeys, survey2b);

        // Get using the guid+createdOn API for completeness.
        Survey survey1aAgain = workerSurveyClient.getSurvey(survey1aKeys.getGuid(), survey1aKeys.getCreatedOn()).execute().body();
        assertKeysEqual(survey1aKeys, survey1aAgain);

        // We only expect the most recently published versions, namely 1b and 2b.
        SurveyList surveyResourceList = workerSurveyClient.getAllPublishedSurveysInStudy(Tests.TEST_KEY).execute().body();
        containsAll(surveyResourceList.getItems(), new MutableHolder(survey1b), new MutableHolder(survey2b));
    }

    @Test
    public void verifyEndSurveyRule() throws Exception {
        SurveysApi surveysApi = developer.getClient(SurveysApi.class);
        
        Survey survey = new Survey();
        survey.setIdentifier("test-survey");
        survey.setName("Test study");

        SurveyRule rule = new SurveyRule();
        rule.setOperator(Operator.EQ);
        rule.setValue("true");
        rule.setEndSurvey(Boolean.TRUE);
        
        StringConstraints constraints = new StringConstraints();
        constraints.setDataType(DataType.STRING);
        constraints.getRules().add(rule); // end survey

        SurveyQuestion question = new SurveyQuestion();
        question.setIdentifier("bar");
        question.setPrompt("Prompt");
        question.setUiHint(UIHint.TEXTFIELD);
        question.setConstraints(constraints);
        question.setType("SurveyQuestion");
        survey.getElements().add(question);
        
        GuidCreatedOnVersionHolder keys = createSurvey(surveysApi, survey);
        
        Survey retrieved = surveysApi.getSurvey(keys.getGuid(), keys.getCreatedOn()).execute().body();
        SurveyRule retrievedRule = getConstraints(retrieved, "bar").getRules().get(0);
        
        assertEquals(Boolean.TRUE, retrievedRule.getEndSurvey());
        assertEquals("true", retrievedRule.getValue());
        assertEquals(Operator.EQ, retrievedRule.getOperator());
        assertNull(retrievedRule.getSkipTo());
    }
    
    private Constraints getConstraints(Survey survey, String id) {
        for (SurveyElement element : survey.getElements()) {
            if (element.getIdentifier().equals(id)) {
                return ((SurveyQuestion)element).getConstraints();
            }
        }
        return null;
    }

    private void containsAll(List<Survey> surveys, GuidCreatedOnVersionHolder... keys) {
        // The server may have more surveys than the ones we created, if more than one person is running tests
        // (unit or integration), or if there are persistent tests unrelated to this test.
        assertTrue("Server returned enough items", surveys.size() >= keys.length);

        // Check that we can find all of our expected surveys. Use the MutableHolder class, so we can use a
        // set and contains().
        Set<GuidCreatedOnVersionHolder> surveyKeySet = new HashSet<>();
        for (Survey survey : surveys) {
            surveyKeySet.add(new MutableHolder(survey));
        }
        for (GuidCreatedOnVersionHolder key : keys) {
            MutableHolder adjKey = new MutableHolder(key);
            assertTrue("Survey contains expected key: " + adjKey, surveyKeySet.contains(adjKey));
        }
    }

    private class MutableHolder extends GuidCreatedOnVersionHolder {
        private final String guid;
        private final DateTime createdOn;
        private final Long version;
        MutableHolder(GuidCreatedOnVersionHolder keys) {
            this.guid = keys.getGuid();
            this.createdOn = keys.getCreatedOn();
            this.version = keys.getVersion();
        }
        MutableHolder(Survey keys) {
            this.guid = keys.getGuid();
            this.createdOn = keys.getCreatedOn();
            this.version = keys.getVersion();
        }
        @Override
        public String getGuid() {
            return guid;
        }
        @Override
        public DateTime getCreatedOn() {
            return createdOn;
        }
        @Override
        public Long getVersion() {
            return version;
        }
        @Override
        public int hashCode() {
            return Objects.hash(guid, createdOn, version);
        }
        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (!super.equals(obj) || getClass() != obj.getClass())
                return false;
            MutableHolder other = (MutableHolder) obj;
            return Objects.equals(createdOn, other.createdOn) &&
                Objects.equals(guid, other.guid) &&
                Objects.equals(version, other.version);
        }
    }
    
    // Helper methods to ensure we always record these calls for cleanup

    private GuidCreatedOnVersionHolder createSurvey(SurveysApi surveysApi, Survey survey) throws Exception {
        GuidCreatedOnVersionHolder keys = surveysApi.createSurvey(survey).execute().body();
        surveysToDelete.add(keys);
        return keys;
    }

    private GuidCreatedOnVersionHolder versionSurvey(SurveysApi surveysApi, GuidCreatedOnVersionHolder survey) throws Exception {
        GuidCreatedOnVersionHolder versionHolder = surveysApi
                .versionSurvey(survey.getGuid(), survey.getCreatedOn()).execute().body();
        surveysToDelete.add(versionHolder);
        return versionHolder;
    }

    private static void assertKeysEqual(GuidCreatedOnVersionHolder keys, Survey survey) {
        assertEquals(keys.getGuid(), survey.getGuid());
        assertEquals(keys.getCreatedOn(), survey.getCreatedOn());
    }
}
