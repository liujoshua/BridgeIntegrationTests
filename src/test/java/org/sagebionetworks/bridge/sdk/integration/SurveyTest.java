package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.sagebionetworks.bridge.sdk.integration.TestSurvey.BLOODPRESSURE_ID;
import static org.sagebionetworks.bridge.sdk.integration.TestSurvey.BOOLEAN_ID;
import static org.sagebionetworks.bridge.sdk.integration.TestSurvey.DATETIME_ID;
import static org.sagebionetworks.bridge.sdk.integration.TestSurvey.DATE_ID;
import static org.sagebionetworks.bridge.sdk.integration.TestSurvey.DECIMAL_ID;
import static org.sagebionetworks.bridge.sdk.integration.TestSurvey.DURATION_ID;
import static org.sagebionetworks.bridge.sdk.integration.TestSurvey.HEIGHT_ID;
import static org.sagebionetworks.bridge.sdk.integration.TestSurvey.INTEGER_ID;
import static org.sagebionetworks.bridge.sdk.integration.TestSurvey.MULTIVALUE_ID;
import static org.sagebionetworks.bridge.sdk.integration.TestSurvey.STRING_ID;
import static org.sagebionetworks.bridge.sdk.integration.TestSurvey.TIME_ID;
import static org.sagebionetworks.bridge.sdk.integration.TestSurvey.WEIGHT_ID;
import static org.sagebionetworks.bridge.sdk.integration.TestSurvey.YEARMONTH_ID;
import static org.sagebionetworks.bridge.sdk.integration.TestSurvey.POSTALCODE_ID;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import retrofit2.Call;

import org.apache.commons.lang3.RandomStringUtils;
import org.joda.time.DateTime;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.rest.api.ForWorkersApi;
import org.sagebionetworks.bridge.rest.api.SchedulesApi;
import org.sagebionetworks.bridge.rest.api.SharedModulesApi;
import org.sagebionetworks.bridge.rest.api.SurveysApi;
import org.sagebionetworks.bridge.rest.exceptions.BadRequestException;
import org.sagebionetworks.bridge.rest.exceptions.ConstraintViolationException;
import org.sagebionetworks.bridge.rest.exceptions.EntityAlreadyExistsException;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.exceptions.InvalidEntityException;
import org.sagebionetworks.bridge.rest.exceptions.PublishedSurveyException;
import org.sagebionetworks.bridge.rest.exceptions.UnauthorizedException;
import org.sagebionetworks.bridge.rest.model.Activity;
import org.sagebionetworks.bridge.rest.model.ActivityType;
import org.sagebionetworks.bridge.rest.model.BloodPressureConstraints;
import org.sagebionetworks.bridge.rest.model.BooleanConstraints;
import org.sagebionetworks.bridge.rest.model.CountryCode;
import org.sagebionetworks.bridge.rest.model.DataType;
import org.sagebionetworks.bridge.rest.model.DateConstraints;
import org.sagebionetworks.bridge.rest.model.DateTimeConstraints;
import org.sagebionetworks.bridge.rest.model.DecimalConstraints;
import org.sagebionetworks.bridge.rest.model.DurationConstraints;
import org.sagebionetworks.bridge.rest.model.GuidCreatedOnVersionHolder;
import org.sagebionetworks.bridge.rest.model.GuidVersionHolder;
import org.sagebionetworks.bridge.rest.model.HeightConstraints;
import org.sagebionetworks.bridge.rest.model.Image;
import org.sagebionetworks.bridge.rest.model.IntegerConstraints;
import org.sagebionetworks.bridge.rest.model.MultiValueConstraints;
import org.sagebionetworks.bridge.rest.model.Operator;
import org.sagebionetworks.bridge.rest.model.PostalCodeConstraints;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.Schedule;
import org.sagebionetworks.bridge.rest.model.SchedulePlan;
import org.sagebionetworks.bridge.rest.model.ScheduleType;
import org.sagebionetworks.bridge.rest.model.SharedModuleMetadata;
import org.sagebionetworks.bridge.rest.model.SimpleScheduleStrategy;
import org.sagebionetworks.bridge.rest.model.StringConstraints;
import org.sagebionetworks.bridge.rest.model.Study;
import org.sagebionetworks.bridge.rest.model.Survey;
import org.sagebionetworks.bridge.rest.model.SurveyElement;
import org.sagebionetworks.bridge.rest.model.SurveyInfoScreen;
import org.sagebionetworks.bridge.rest.model.SurveyList;
import org.sagebionetworks.bridge.rest.model.SurveyQuestion;
import org.sagebionetworks.bridge.rest.model.SurveyQuestionOption;
import org.sagebionetworks.bridge.rest.model.SurveyReference;
import org.sagebionetworks.bridge.rest.model.SurveyRule;
import org.sagebionetworks.bridge.rest.model.TimeConstraints;
import org.sagebionetworks.bridge.rest.model.UIHint;
import org.sagebionetworks.bridge.rest.model.Unit;
import org.sagebionetworks.bridge.rest.model.WeightConstraints;
import org.sagebionetworks.bridge.rest.model.YearMonthConstraints;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.util.IntegTestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("ConstantConditions")
public class SurveyTest {
    private static final Logger LOG = LoggerFactory.getLogger(SurveyTest.class);
    
    private static final String SURVEY_NAME = "dummy-survey-name";

    //private static TestUser admin;
    private static TestUser developer;
    private static TestUser user;
    private static TestUser worker;
    private static SharedModulesApi sharedDeveloperModulesApi;
    private static SurveysApi sharedSurveysApi;
    private static ForAdminsApi adminsApi;

    private String surveyId;

    // We use SimpleGuidCreatedOnVersionHolder, because we need to use an immutable version holder, to ensure we're
    // deleting the correct surveys.
    private Set<GuidCreatedOnVersionHolder> surveysToDelete;

    @BeforeClass
    public static void beforeClass() throws Exception {
        TestUser admin = TestUserHelper.getSignedInAdmin();
        adminsApi = admin.getClient(ForAdminsApi.class);
        developer = TestUserHelper.createAndSignInUser(SurveyTest.class, false, Role.DEVELOPER);
        user = TestUserHelper.createAndSignInUser(SurveyTest.class, true);
        worker = TestUserHelper.createAndSignInUser(SurveyTest.class, false, Role.WORKER);

        TestUserHelper.TestUser sharedDeveloper = TestUserHelper.getSignedInSharedDeveloper();
        sharedDeveloperModulesApi = sharedDeveloper.getClient(SharedModulesApi.class);
        sharedSurveysApi = sharedDeveloper.getClient(SurveysApi.class);
    }

    @Before
    public void before() {
        surveyId = Tests.randomIdentifier(this.getClass());
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
    
    // One test to verify that all the fields of the test survey can be persisted and retrieved.
    // Info screen is still tested separately. 
    @Test
    public void canRountripSurvey() throws Exception {
        SurveysApi surveysApi = developer.getClient(SurveysApi.class);
        
        GuidCreatedOnVersionHolder key = createSurvey(surveysApi, TestSurvey.getSurvey(SurveyTest.class));
        Survey survey = surveysApi.getSurvey(key.getGuid(), key.getCreatedOn()).execute().body();
        
        // Boolean question
        SurveyQuestion booleanQuestion = getQuestion(survey, BOOLEAN_ID);
        assertEquals("SurveyQuestion", booleanQuestion.getType());
        BooleanConstraints booleanConstraints = (BooleanConstraints)booleanQuestion.getConstraints();
        assertEquals("BooleanConstraints", booleanConstraints.getType());
        assertEquals(DataType.BOOLEAN, booleanConstraints.getDataType());

        // Date question
        SurveyQuestion dateQuestion = getQuestion(survey, DATE_ID);
        DateConstraints dateConstraints = (DateConstraints)dateQuestion.getConstraints();
        assertEquals(DataType.DATE, dateConstraints.getDataType());
        assertEquals("DateConstraints", dateConstraints.getType());
        assertEquals(TestSurvey.DATE_QUESTION_EARLIEST_VALUE, dateConstraints.getEarliestValue());
        assertEquals(TestSurvey.DATE_QUESTION_LATEST_VALUE, dateConstraints.getLatestValue());
        
        // DateTime question
        SurveyQuestion dateTimeQuestion = getQuestion(survey, DATETIME_ID);
        DateTimeConstraints dateTimeConstraints = (DateTimeConstraints)dateTimeQuestion.getConstraints();
        assertEquals(DataType.DATETIME, dateTimeConstraints.getDataType());
        assertEquals("DateTimeConstraints", dateTimeConstraints.getType());
        assertEquals(TestSurvey.DATETIME_EARLIEST_VALUE.toString(), dateTimeConstraints.getEarliestValue().toString());
        assertEquals(TestSurvey.DATETIME_LATEST_VALUE.toString(), dateTimeConstraints.getLatestValue().toString());
        
        // Decimal question
        SurveyQuestion decimalQuestion = getQuestion(survey, DECIMAL_ID);
        DecimalConstraints decimalConstraints = (DecimalConstraints)decimalQuestion.getConstraints();
        assertEquals(DataType.DECIMAL, decimalConstraints.getDataType());
        assertEquals("DecimalConstraints", decimalConstraints.getType());
        assertEquals(TestSurvey.DECIMAL_QUESTION_MIN_VALUE, decimalConstraints.getMinValue());
        assertEquals(TestSurvey.DECIMAL_QUESTION_MAX_VALUE, decimalConstraints.getMaxValue());
        assertEquals(TestSurvey.DECIMAL_QUESTION_STEP, decimalConstraints.getStep());
        assertEquals(Unit.GRAMS, decimalConstraints.getUnit());
        
        // Integer question
        SurveyQuestion intQuestion = getQuestion(survey, INTEGER_ID);
        IntegerConstraints intConstraints = (IntegerConstraints)intQuestion.getConstraints();
        assertEquals(DataType.INTEGER, intConstraints.getDataType());
        assertEquals("IntegerConstraints", intConstraints.getType());
        assertEquals(TestSurvey.INT_QUESTION_MIN_VALUE, intConstraints.getMinValue());
        assertEquals(TestSurvey.INT_QUESTION_MAX_VALUE, intConstraints.getMaxValue());
        assertEquals(TestSurvey.INT_QUESTION_STEP, intConstraints.getStep());
        assertEquals(TestSurvey.INT_QUESTION_UNIT, intConstraints.getUnit());
        
        // Duration question
        SurveyQuestion durationQuestion = getQuestion(survey, DURATION_ID);
        DurationConstraints durationConstraints = (DurationConstraints)durationQuestion.getConstraints();
        assertEquals(DataType.DURATION, durationConstraints.getDataType());
        assertEquals("DurationConstraints", durationConstraints.getType());
        assertEquals(TestSurvey.DURATION_QUESTION_MIN_VALUE, durationConstraints.getMinValue());
        assertEquals(TestSurvey.DURATION_QUESTION_MAX_VALUE, durationConstraints.getMaxValue());
        assertEquals(TestSurvey.DURATION_QUESTION_STEP, durationConstraints.getStep());
        assertEquals(TestSurvey.DURATION_QUESTION_UNIT, durationConstraints.getUnit());
        
        // Time question
        SurveyQuestion timeQuestion = getQuestion(survey, TIME_ID);
        TimeConstraints timeConstraints = (TimeConstraints)timeQuestion.getConstraints();
        assertEquals(DataType.TIME, timeConstraints.getDataType());
        assertEquals("TimeConstraints", timeConstraints.getType());
        
        // MultiValue question
        SurveyQuestion multiValueQuestion = getQuestion(survey, MULTIVALUE_ID);
        MultiValueConstraints multiValueConstraints = (MultiValueConstraints)multiValueQuestion.getConstraints();
        assertEquals(DataType.STRING, multiValueConstraints.getDataType());
        assertEquals("MultiValueConstraints", multiValueConstraints.getType());
        List<SurveyQuestionOption> options = multiValueConstraints.getEnumeration();
        assertEquals(5, options.size());
        SurveyQuestionOption option = options.get(0);
        assertEquals("Terrible", option.getLabel());
        assertEquals("Terrible Detail", option.getDetail());
        assertEquals("1", option.getValue());
        assertEquals("http://terrible.svg", option.getImage().getSource());
        assertEquals(new Integer(600), option.getImage().getWidth());
        assertEquals(new Integer(300), option.getImage().getHeight());
        
        // String question
        SurveyQuestion stringQuestion = getQuestion(survey, STRING_ID);
        StringConstraints stringConstraints = (StringConstraints)stringQuestion.getConstraints();
        assertEquals(DataType.STRING, stringConstraints.getDataType());
        assertEquals("StringConstraints", stringConstraints.getType());
        assertEquals(TestSurvey.STRING_QUESTION_MIN_LENGTH, stringConstraints.getMinLength());
        assertEquals(TestSurvey.STRING_QUESTION_MAX_LENGTH, stringConstraints.getMaxLength());
        assertEquals(TestSurvey.STRING_QUESTION_PATTERN, stringConstraints.getPattern());
        assertEquals(TestSurvey.STRING_QUESTION_ERROR_MESSAGE, stringConstraints.getPatternErrorMessage());
        assertEquals(TestSurvey.STRING_QUESTION_PLACEHOLDER, stringConstraints.getPatternPlaceholder());
        
        // Blood pressure question
        SurveyQuestion bloodPressureQuestion = getQuestion(survey, BLOODPRESSURE_ID);
        BloodPressureConstraints bloodPressureConstraints = (BloodPressureConstraints)bloodPressureQuestion.getConstraints();
        assertEquals(DataType.BLOODPRESSURE, bloodPressureConstraints.getDataType());
        assertEquals("BloodPressureConstraints", bloodPressureConstraints.getType());
        assertEquals(Unit.CUBIC_CENTIMETERS, bloodPressureConstraints.getUnit());
        
        // Height question
        SurveyQuestion heightQuestion = getQuestion(survey, HEIGHT_ID);
        HeightConstraints heightConstraints = (HeightConstraints)heightQuestion.getConstraints();
        assertEquals(DataType.HEIGHT, heightConstraints.getDataType());
        assertEquals("HeightConstraints", heightConstraints.getType());
        assertEquals(Unit.CENTIMETERS, heightConstraints.getUnit());
        assertEquals(true, heightConstraints.isForInfant());
        
        // Weight question
        SurveyQuestion weightQuestion = getQuestion(survey, WEIGHT_ID);
        WeightConstraints weightConstraints = (WeightConstraints)weightQuestion.getConstraints();
        assertEquals(DataType.WEIGHT, weightConstraints.getDataType());
        assertEquals("WeightConstraints", weightConstraints.getType());
        assertEquals(Unit.KILOGRAMS, weightConstraints.getUnit());
        assertEquals(true, weightConstraints.isForInfant());
        
        // YearMonth question
        SurveyQuestion yearMonthQuestion = getQuestion(survey, YEARMONTH_ID);
        YearMonthConstraints yearMonthConstraints = (YearMonthConstraints)yearMonthQuestion.getConstraints();
        assertEquals(DataType.YEARMONTH, yearMonthConstraints.getDataType());
        assertEquals("YearMonthConstraints", yearMonthConstraints.getType());
        assertEquals(TestSurvey.YEARMONTH_QUESTION_ALLOW_FUTURE, yearMonthConstraints.isAllowFuture());
        assertEquals(TestSurvey.YEARMONTH_QUESTION_EARLIEST_VALUE, yearMonthConstraints.getEarliestValue());
        assertEquals(TestSurvey.YEARMONTH_QUESTION_LATEST_VALUE, yearMonthConstraints.getLatestValue());
        
        // Postal Code question
        SurveyQuestion postalCodeQuestion = getQuestion(survey, POSTALCODE_ID);
        PostalCodeConstraints postalCodeConstraints = (PostalCodeConstraints)postalCodeQuestion.getConstraints();
        assertEquals(DataType.POSTALCODE, postalCodeConstraints.getDataType());
        assertEquals("PostalCodeConstraints", postalCodeConstraints.getType());
        assertEquals(CountryCode.US, postalCodeConstraints.getCountryCode());
        assertEquals(17, postalCodeConstraints.getSparseZipCodePrefixes().size());
        assertEquals("036", postalCodeConstraints.getSparseZipCodePrefixes().get(0));
    }
    
    private SurveyQuestion getQuestion(Survey survey, String questionId) {
        for (SurveyElement element : survey.getElements()) {
            if (element.getIdentifier().equals(questionId)) {
                return (SurveyQuestion)element;
            }
        }
        return null; 
    }

    @Test
    public void testPermanentDeleteWithSharedModule() throws Exception {
        // create test survey and test shared module
        String moduleId = "integ-test-module-delete" + RandomStringUtils.randomAlphabetic(4);;

        Survey survey = new Survey().name(SURVEY_NAME).identifier(surveyId);
        GuidCreatedOnVersionHolder retSurvey = sharedSurveysApi.createSurvey(survey).execute().body();

        SharedModuleMetadata metadataToCreate = new SharedModuleMetadata().id(moduleId).version(0)
                .name("Integ Test Schema").surveyCreatedOn(retSurvey.getCreatedOn().toString()).surveyGuid(retSurvey.getGuid());
        sharedDeveloperModulesApi.createMetadata(metadataToCreate).execute()
                .body();

        // execute delete
        Exception thrownEx = null;
        try {
            adminsApi.adminChangeStudy(Tests.SHARED_SIGNIN).execute();
            adminsApi.deleteSurvey(retSurvey.getGuid(), retSurvey.getCreatedOn(), true).execute();
            fail("expected exception");
        } catch (BadRequestException e) {
            thrownEx = e;
        } finally {
            // finally delete shared module and uploaded schema
            adminsApi.deleteMetadataByIdAllVersions(moduleId, true).execute();
            adminsApi.deleteSurvey(retSurvey.getGuid(), retSurvey.getCreatedOn(), true).execute();
            adminsApi.adminChangeStudy(Tests.API_SIGNIN).execute();
        }
        assertNotNull(thrownEx);
    }

    @Test
    public void testVirtualDeleteWithSharedModule() throws Exception {
        // create test survey and test shared module
        String moduleId = "integ-test-module-delete" + RandomStringUtils.randomAlphabetic(4);;

        Survey survey = new Survey().name(SURVEY_NAME).identifier(surveyId);
        GuidCreatedOnVersionHolder retSurvey = sharedSurveysApi.createSurvey(survey).execute().body();

        SharedModuleMetadata metadataToCreate = new SharedModuleMetadata().id(moduleId).version(0)
                .name("Integ Test Schema").surveyCreatedOn(retSurvey.getCreatedOn().toString()).surveyGuid(retSurvey.getGuid());
        sharedDeveloperModulesApi.createMetadata(metadataToCreate).execute()
                .body();

        // execute delete
        Exception thrownEx = null;
        try {
            sharedSurveysApi.deleteSurvey(retSurvey.getGuid(), retSurvey.getCreatedOn(), false).execute();
            fail("expected exception");
        } catch (BadRequestException e) {
            thrownEx = e;
        } finally {
            // finally delete shared module and uploaded schema
            adminsApi.adminChangeStudy(Tests.SHARED_SIGNIN).execute();
            adminsApi.deleteMetadataByIdAllVersions(moduleId, true).execute();
            adminsApi.deleteSurvey(retSurvey.getGuid(), retSurvey.getCreatedOn(), true).execute();
            adminsApi.adminChangeStudy(Tests.API_SIGNIN).execute();
        }
        assertNotNull(thrownEx);
    }

    @Test
    public void cannotCreateSurveyWithDuplicateId() throws Exception {
        SurveysApi surveysApi = developer.getClient(SurveysApi.class);

        // Create survey. This succeeds.
        Survey survey = new Survey().name(SURVEY_NAME).identifier(surveyId);
        createSurvey(surveysApi, survey);

        // createSurvey() uses a global secondary index to check for duplicate IDs. Sleep 2 seconds to ensure the index
        // is consistent.
        Thread.sleep(2000);

        // Create another identical survey with the same ID. This fails.
        Survey survey2 = new Survey().name(SURVEY_NAME).identifier(surveyId);
        try {
            createSurvey(surveysApi, survey2);
            fail("expected exception");
        } catch (EntityAlreadyExistsException ex) {
            assertTrue(ex.getMessage().contains("Survey identifier " + surveyId + " is already used by survey"));
        }
    }

    @Test(expected=UnauthorizedException.class)
    public void cannotSubmitAsNormalUser() throws Exception {
        user.getClient(SurveysApi.class).getMostRecentSurveys(false).execute().body();
    }

    @Test
    public void cannotUpdateSurveyId() throws Exception {
        SurveysApi surveysApi = developer.getClient(SurveysApi.class);

        // Create survey.
        Survey survey = new Survey().name(SURVEY_NAME).identifier(surveyId);
        GuidCreatedOnVersionHolder keys = createSurvey(surveysApi, survey);

        // Attempt to update the survey ID.
        survey = surveysApi.getSurvey(keys.getGuid(), keys.getCreatedOn()).execute().body();
        survey.setIdentifier(surveyId + "-2");
        surveysApi.updateSurvey(keys.getGuid(), keys.getCreatedOn(), survey).execute();

        // Survey ID remains unchanged.
        survey = surveysApi.getSurvey(keys.getGuid(), keys.getCreatedOn()).execute().body();
        assertEquals(surveyId, survey.getIdentifier());
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

        // Check optional parameters.
        assertEquals(TestSurvey.COPYRIGHT_NOTICE, survey.getCopyrightNotice());
        assertEquals(TestSurvey.MODULE_ID, survey.getModuleId());
        assertEquals(TestSurvey.MODULE_VERSION, survey.getModuleVersion().intValue());
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
        assertFalse("survey is not published.", survey.isPublished());

        surveysApi.publishSurvey(survey.getGuid(), survey.getCreatedOn(), false).execute();
        survey = surveysApi.getSurvey(survey.getGuid(), survey.getCreatedOn()).execute().body();
        assertTrue("survey is now published.", survey.isPublished());
    }

    @Test(expected = InvalidEntityException.class)
    public void createInvalidSurveyReturns400() throws Exception {
        // This should seem obvious. However, there was a previous bug in BridgePF where the 400 invalid survey
        // exception would be masked by an obscure 500 NullPointerException. The only codepath where this happens is
        // is creating surveys with invalid survey elements, which goes through the SurveyElementFactory.
        //
        // The easiest way to repro this bug is to create a survey question with a constraint without a DataType.
        // The bug would cause a 500 NPE. When fixed, it will produce a 400 InvalidEntityException with all the
        // validation messages.

        Survey survey = new Survey().addElementsItem(new SurveyQuestion().constraints(new IntegerConstraints()));
        SurveysApi surveysApi = developer.getClient(SurveysApi.class);
        surveysApi.createSurvey(survey).execute();
    }

    @Test
    public void getAllVersionsOfASurvey() throws Exception {
        SurveysApi surveysApi = developer.getClient(SurveysApi.class);

        GuidCreatedOnVersionHolder key = createSurvey(surveysApi, TestSurvey.getSurvey(SurveyTest.class));
        key = versionSurvey(surveysApi, key);
        
        SurveyList surveyList = surveysApi.getAllVersionsOfSurvey(key.getGuid(), false).execute().body();
        int count = surveyList.getItems().size();
        assertEquals("Two versions for this survey.", 2, count);
        
        // verify includeDeleted
        Survey oneVersion = surveyList.getItems().get(0); 
        surveysApi.deleteSurvey(oneVersion.getGuid(), oneVersion.getCreatedOn(), false).execute();
        
        anyDeleted(surveysApi.getAllVersionsOfSurvey(key.getGuid(), true));
        noneDeleted(surveysApi.getAllVersionsOfSurvey(key.getGuid(), false));
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
        SurveyList recentSurveys = surveysApi.getMostRecentSurveys(false).execute().body();
        containsAll(recentSurveys.getItems(), key, key1, key2);

        key = surveysApi.publishSurvey(key.getGuid(), key.getCreatedOn(), false).execute().body();
        key2 = surveysApi.publishSurvey(key2.getGuid(), key2.getCreatedOn(), false).execute().body();

        Thread.sleep(2000);
        SurveyList publishedSurveys = surveysApi.getPublishedSurveys(false).execute().body();
        containsAll(publishedSurveys.getItems(), key, key2);
        
        // verify logical deletion
        surveysApi.deleteSurvey(key2.getGuid(), key2.getCreatedOn(), false).execute();

        Thread.sleep(2000);
        anyDeleted(surveysApi.getMostRecentSurveys(true));
        noneDeleted(surveysApi.getMostRecentSurveys(false));
    }

    @Test
    public void canUpdateASurvey() throws Exception {
        SurveysApi surveysApi = developer.getClient(SurveysApi.class);
        
        GuidCreatedOnVersionHolder key = createSurvey(surveysApi, TestSurvey.getSurvey(SurveyTest.class));
        Survey survey = surveysApi.getSurvey(key.getGuid(), key.getCreatedOn()).execute().body();
        assertEquals("Type is Survey.", survey.getClass(), Survey.class);

        survey.setName("New name");
        GuidCreatedOnVersionHolder holder = surveysApi.updateSurvey(survey.getGuid(), survey.getCreatedOn(), survey).execute().body();
        // Should be incremented.
        assertTrue(holder.getVersion() > survey.getVersion());
        
        survey = surveysApi.getSurvey(survey.getGuid(), survey.getCreatedOn()).execute().body();
        assertEquals("Name should have changed.", survey.getName(), "New name");
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

        SurveyList allRevisions = surveysApi.getAllVersionsOfSurvey(keys.getGuid(), false).execute().body();
        assertEquals("There are now two versions", 2, allRevisions.getItems().size());

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
        Tests.setVariableValueInObject(screen, "type", "SurveyInfoScreen");
        
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
        Tests.setVariableValueInObject(question, "type", "SurveyQuestion");
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
        ForWorkersApi workerApi = worker.getClient(ForWorkersApi.class);
        Thread.sleep(2000);

        // The surveys we created were just dummies. Just check that the surveys are not null and that the keys match.
        Survey survey1a = workerApi.getSurvey(survey1aKeys.getGuid(), survey1aKeys.getCreatedOn()).execute().body();
        Survey survey1b = workerApi.getSurvey(survey1bKeys.getGuid(), survey1bKeys.getCreatedOn()).execute().body();
        Survey survey2a = workerApi.getSurvey(survey2aKeys.getGuid(), survey2aKeys.getCreatedOn()).execute().body();
        Survey survey2b = workerApi.getSurvey(survey2bKeys.getGuid(), survey2bKeys.getCreatedOn()).execute().body();

        assertKeysEqual(survey1aKeys, survey1a);
        assertKeysEqual(survey1bKeys, survey1b);
        assertKeysEqual(survey2aKeys, survey2a);
        assertKeysEqual(survey2bKeys, survey2b);

        // Get using the guid+createdOn API for completeness.
        Survey survey1aAgain = workerApi.getSurvey(survey1aKeys.getGuid(), survey1aKeys.getCreatedOn()).execute().body();
        assertKeysEqual(survey1aKeys, survey1aAgain);

        // We only expect the most recently published versions, namely 1b and 2b.
        SurveyList surveyResourceList = workerApi.getAllPublishedSurveys(IntegTestUtils.STUDY_ID, false).execute().body();
        containsAll(surveyResourceList.getItems(), new MutableHolder(survey1b), new MutableHolder(survey2b));
        
        // Delete 2b.
        developer.getClient(SurveysApi.class).deleteSurvey(survey2b.getGuid(), survey2b.getCreatedOn(), false).execute();

        // Sleep to clear eventual consistency problems.
        Thread.sleep(2000);

        // Verify includeDeleted works
        noneDeleted(workerApi.getAllPublishedSurveys(IntegTestUtils.STUDY_ID, false));
        anyDeleted(workerApi.getAllPublishedSurveys(IntegTestUtils.STUDY_ID, true));
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

        SurveyQuestion question = new SurveyQuestion();
        question.setIdentifier("bar");
        question.setPrompt("Prompt");
        question.setUiHint(UIHint.TEXTFIELD);
        question.setConstraints(constraints);
        Tests.setVariableValueInObject(question, "type", "SurveyQuestion");
        question.setAfterRules(ImmutableList.of(rule)); // end survey
        survey.getElements().add(question);
        
        GuidCreatedOnVersionHolder keys = createSurvey(surveysApi, survey);
        
        Survey retrieved = surveysApi.getSurvey(keys.getGuid(), keys.getCreatedOn()).execute().body();
        SurveyRule retrievedRule = getSurveyElement(retrieved, "bar").getAfterRules().get(0);
        
        assertEquals(Boolean.TRUE, retrievedRule.isEndSurvey());
        assertEquals("true", retrievedRule.getValue());
        assertEquals(Operator.EQ, retrievedRule.getOperator());
        assertNull(retrievedRule.getSkipTo());
    }
    
    @Test
    public void canLogicallyDeletePublishedSurvey() throws Exception {
        SurveysApi surveysApi = developer.getClient(SurveysApi.class);

        GuidCreatedOnVersionHolder keys = createSurvey(surveysApi, TestSurvey.getSurvey(SurveyTest.class));
        
        surveysApi.publishSurvey(keys.getGuid(), keys.getCreatedOn(), false).execute();
        
        surveysApi.deleteSurvey(keys.getGuid(), keys.getCreatedOn(), false).execute();

        // getPublishedSurveys() uses a secondary global index. Sleep for 2 seconds to help make sure the index is consistent.
        Thread.sleep(2000);

        // no longer in the list
        SurveyList list = surveysApi.getPublishedSurveys(false).execute().body();
        assertFalse(list.getItems().stream().anyMatch(survey -> survey.getGuid().equals(keys.getGuid())));

        // you can still retrieve the logically deleted survey in the list
        list = surveysApi.getPublishedSurveys(true).execute().body();
        assertTrue(list.getItems().stream().anyMatch(survey -> survey.getGuid().equals(keys.getGuid())));
    }
    
    @Test
    public void cannotLogicallyDeleteSurveyTwice() throws Exception {
        SurveysApi surveysApi = developer.getClient(SurveysApi.class);

        GuidCreatedOnVersionHolder keys = createSurvey(surveysApi, TestSurvey.getSurvey(SurveyTest.class));
        
        surveysApi.deleteSurvey(keys.getGuid(), keys.getCreatedOn(), false).execute();
        try {
            surveysApi.deleteSurvey(keys.getGuid(), keys.getCreatedOn(), false).execute();
            fail("Should have thrown exception");
        } catch(EntityNotFoundException e) {
        }
        try {
            surveysApi.getAllVersionsOfSurvey(keys.getGuid(), false).execute().body();    
            fail("Should have thrown exception");
        } catch(EntityNotFoundException e) {
        }
    }
    
    @Test
    public void cannotPhysicallyDeleteSurveyInSchedule() throws Exception {
        SurveysApi surveysApi = developer.getClient(SurveysApi.class);
        SchedulesApi schedulesApi = developer.getClient(SchedulesApi.class);

        GuidCreatedOnVersionHolder keys = createSurvey(surveysApi, TestSurvey.getSurvey(SurveyTest.class));
        
        keys = surveysApi.publishSurvey(keys.getGuid(), keys.getCreatedOn(), false).execute().body();
        
        SchedulePlan plan = createSchedulePlanTo(keys);
        GuidVersionHolder holder = schedulesApi.createSchedulePlan(plan).execute().body();
        
        // Should not be able to physically delete this survey
        try {
            adminsApi.deleteSurvey(keys.getGuid(), keys.getCreatedOn(), true).execute();
            fail("Should have thrown an exception.");
        } catch(ConstraintViolationException e) {
            
        } finally {
            adminsApi.deleteSchedulePlan(holder.getGuid(), true).execute();
        }
    }
    
    @Test
    public void canPhysicallyDeleteLogicallyDeletedSurvey() throws Exception {
        SurveysApi surveysApi = developer.getClient(SurveysApi.class);

        GuidCreatedOnVersionHolder keys = createSurvey(surveysApi, TestSurvey.getSurvey(SurveyTest.class));
        
        surveysApi.deleteSurvey(keys.getGuid(), keys.getCreatedOn(), false).execute();
        
        Survey retrieved = surveysApi.getSurvey(keys.getGuid(), keys.getCreatedOn()).execute().body();
        assertTrue(retrieved.isDeleted());
        
        adminsApi.deleteSurvey(keys.getGuid(), keys.getCreatedOn(), true).execute();
        surveysToDelete.remove(keys);
        
        try {
            surveysApi.getSurvey(keys.getGuid(), keys.getCreatedOn()).execute().body();
            fail("Should have thrown an exception.");
        } catch(EntityNotFoundException e) {
            
        }
    }
    
    @Test
    public void canCreateAndSaveVariousKindsOfBeforeRules() throws Exception {
        Study study = adminsApi.getUsersStudy().execute().body();
        String dataGroup = study.getDataGroups().get(0);

        Survey survey = TestSurvey.getSurvey(SurveyTest.class);
        SurveyElement element = survey.getElements().get(0);
        SurveyElement skipToTarget = survey.getElements().get(1);
        // Trim the survey to one element
        survey.setElements(Lists.newArrayList(element,skipToTarget));
        
        SurveyRule endSurvey = new SurveyRule().endSurvey(true).operator(Operator.ALWAYS);
        SurveyRule skipTo = new SurveyRule().skipTo(skipToTarget.getIdentifier()).operator(Operator.EQ).value("2010-10-10");
        SurveyRule assignGroup = new SurveyRule().assignDataGroup(dataGroup).operator(Operator.DE);
        SurveyRule displayIf = new SurveyRule().displayIf(true).operator(Operator.ANY).addDataGroupsItem(dataGroup);
        SurveyRule displayUnless = new SurveyRule().displayUnless(true).operator(Operator.ALL)
                .addDataGroupsItem(dataGroup);
        
        element.setBeforeRules(Lists.newArrayList(endSurvey, skipTo, assignGroup, displayIf, displayUnless));
        
        SurveysApi devSurveysApi = developer.getClient(SurveysApi.class);
        
        GuidCreatedOnVersionHolder keys = createSurvey(devSurveysApi, survey);
        Survey created = devSurveysApi.getSurvey(keys.getGuid(), keys.getCreatedOn()).execute().body();
        
        List<SurveyRule> createdRules = created.getElements().get(0).getBeforeRules();
        // These aren't set locally and will cause equality to fail. Set them.
        Tests.setVariableValueInObject(endSurvey, "type", "SurveyRule");
        Tests.setVariableValueInObject(skipTo, "type", "SurveyRule");
        Tests.setVariableValueInObject(assignGroup, "type", "SurveyRule");
        Tests.setVariableValueInObject(displayIf, "type", "SurveyRule");
        Tests.setVariableValueInObject(displayUnless, "type", "SurveyRule");
        
        assertEquals(endSurvey, createdRules.get(0));
        assertEquals(skipTo, createdRules.get(1));
        assertEquals(assignGroup, createdRules.get(2));
        assertEquals(displayIf, createdRules.get(3));
        assertEquals(displayUnless, createdRules.get(4));
        
        // Verify they can all be deleted as well.
        created.getElements().get(0).setBeforeRules(Lists.newArrayList());
        
        keys = devSurveysApi.updateSurvey(created.getGuid(), created.getCreatedOn(), created).execute().body();
        Survey updated = devSurveysApi.getSurvey(keys.getGuid(), keys.getCreatedOn()).execute().body();
        
        assertTrue(updated.getElements().get(0).getBeforeRules().isEmpty());
    }
    
    @Test
    public void canCreateAndSaveVariousKindsOfAfterRules() throws Exception {
        Study study = adminsApi.getUsersStudy().execute().body();
        String dataGroup = study.getDataGroups().get(0);

        Survey survey = TestSurvey.getSurvey(SurveyTest.class);
        SurveyElement element = survey.getElements().get(0);
        SurveyElement skipToTarget = survey.getElements().get(1);
        // Trim the survey to one element
        survey.setElements(Lists.newArrayList(element,skipToTarget));
        
        SurveyRule endSurvey = new SurveyRule().endSurvey(true).operator(Operator.ALWAYS);
        SurveyRule skipTo = new SurveyRule().skipTo(skipToTarget.getIdentifier()).operator(Operator.EQ)
                .value("2010-10-10");
        SurveyRule assignGroup = new SurveyRule().assignDataGroup(dataGroup).operator(Operator.DE);
        
        element.setAfterRules(Lists.newArrayList(endSurvey, skipTo, assignGroup));
        
        SurveysApi devSurveysApi = developer.getClient(SurveysApi.class);
        
        GuidCreatedOnVersionHolder keys = createSurvey(devSurveysApi, survey);
        Survey created = devSurveysApi.getSurvey(keys.getGuid(), keys.getCreatedOn()).execute().body();
        
        List<SurveyRule> createdRules = created.getElements().get(0).getAfterRules();
        // These aren't set locally and will cause equality to fail. Set them.
        Tests.setVariableValueInObject(endSurvey, "type", "SurveyRule");
        Tests.setVariableValueInObject(skipTo, "type", "SurveyRule");
        Tests.setVariableValueInObject(assignGroup, "type", "SurveyRule");
        
        assertEquals(endSurvey, createdRules.get(0));
        assertEquals(skipTo, createdRules.get(1));
        assertEquals(assignGroup, createdRules.get(2));
        
        // Verify they can all be deleted as well.
        created.getElements().get(0).setAfterRules(Lists.newArrayList());
        
        keys = devSurveysApi.updateSurvey(created.getGuid(), created.getCreatedOn(), created).execute().body();
        Survey updated = devSurveysApi.getSurvey(keys.getGuid(), keys.getCreatedOn()).execute().body();
        
        assertTrue(updated.getElements().get(0).getAfterRules().isEmpty());
    }
    
    @Test
    public void displayActionsInAfterRulesValidated() throws Exception {
        Study study = adminsApi.getUsersStudy().execute().body();
        String dataGroup = study.getDataGroups().get(0);

        Survey survey = TestSurvey.getSurvey(SurveyTest.class);
        SurveyElement element = survey.getElements().get(0);
        SurveyElement skipToTarget = survey.getElements().get(1);
        // Trim the survey to one element
        survey.setElements(Lists.newArrayList(element,skipToTarget));
        
        SurveyRule displayIf = new SurveyRule().displayIf(true).operator(Operator.ANY).addDataGroupsItem(dataGroup);
        SurveyRule displayUnless = new SurveyRule().displayUnless(true).operator(Operator.ALL)
                .addDataGroupsItem(dataGroup);
        Tests.setVariableValueInObject(displayIf, "type", "SurveyRule");
        Tests.setVariableValueInObject(displayUnless, "type", "SurveyRule");
        
        element.setAfterRules(Lists.newArrayList(displayIf, displayUnless));
        
        SurveysApi devSurveysApi = developer.getClient(SurveysApi.class);
        
        try {
            devSurveysApi.createSurvey(survey).execute().body();
            fail("Should have thrown exception");
        } catch(InvalidEntityException e) {
            assertEquals("elements[0].afterRules[0].displayIf specifies display after screen has been shown",
                    e.getErrors().get("elements[0].afterRules[0].displayIf").get(0));
            assertEquals("elements[0].afterRules[1].displayUnless specifies display after screen has been shown",
                    e.getErrors().get("elements[0].afterRules[1].displayUnless").get(0));
        }
    }
    
    @Test
    public void canRetrieveDeletedSurveys() throws Exception {
        SurveysApi surveysApi = developer.getClient(SurveysApi.class);
        
        Survey survey = TestSurvey.getSurvey(SurveyTest.class);
        
        GuidCreatedOnVersionHolder keys1 = createSurvey(surveysApi, survey);
        keys1 = surveysApi.publishSurvey(keys1.getGuid(), keys1.getCreatedOn(), true).execute().body();
        
        GuidCreatedOnVersionHolder keys2 = versionSurvey(surveysApi, keys1);
        
        survey.setCopyrightNotice("This is a change");
        survey.setCreatedOn(keys2.getCreatedOn());
        survey.setVersion(keys2.getVersion());
        surveysApi.updateSurvey(keys2.getGuid(), keys2.getCreatedOn(), survey).execute().body();
        
        // These two are the same because there are no deleted surveys
        noneDeleted(surveysApi.getAllVersionsOfSurvey(keys1.getGuid(), false));
        noneDeleted(surveysApi.getAllVersionsOfSurvey(keys1.getGuid(), true));

        // Logically delete the second version of the survey
        surveysApi.deleteSurvey(keys2.getGuid(), keys2.getCreatedOn(), false).execute();
        
        // These now differ.
        noneDeleted(surveysApi.getAllVersionsOfSurvey(keys1.getGuid(), false));
        anyDeleted(surveysApi.getAllVersionsOfSurvey(keys1.getGuid(), true));
        
        // You can get the logically deleted survey
        Survey deletedSurvey = surveysApi.getSurvey(keys2.getGuid(), keys2.getCreatedOn()).execute().body();
        assertTrue(deletedSurvey.isDeleted());
        
        // Really delete it
        adminsApi.deleteSurvey(keys2.getGuid(), keys2.getCreatedOn(), true).execute();
        surveysToDelete.remove(keys2);
        
        try {
            surveysApi.getSurvey(keys2.getGuid(), keys2.getCreatedOn()).execute().body();
            fail("Should have thrown exception");
        } catch(EntityNotFoundException e) {
            
        }
        // End result is that it no longer appears in the list of versions
        noneDeleted(surveysApi.getAllVersionsOfSurvey(keys1.getGuid(), true));
    }
    
    @Test
    public void getPublishedSurveyVersionAndDelete() throws Exception {
        // Test the interaction of publication and the two kinds of deletion
        SurveysApi surveysApi = developer.getClient(SurveysApi.class);
        
        Survey survey = TestSurvey.getSurvey(SurveyTest.class);
        GuidCreatedOnVersionHolder keys1 = createSurvey(surveysApi, survey);
        GuidCreatedOnVersionHolder keys2 = versionSurvey(surveysApi, keys1);
        
        // You cannot publish a (logically) deleted survey
        surveysApi.deleteSurvey(keys1.getGuid(), keys1.getCreatedOn(), false).execute();
        try {
            surveysApi.publishSurvey(keys1.getGuid(), keys1.getCreatedOn(), false).execute();
            fail("Should have thrown an exception");
        } catch(EntityNotFoundException e) {
            
        }
        surveysApi.publishSurvey(keys2.getGuid(), keys2.getCreatedOn(), false).execute();
        surveysApi.deleteSurvey(keys2.getGuid(), keys2.getCreatedOn(), false).execute();
        
        Thread.sleep(1000);
        anyDeleted(surveysApi.getPublishedSurveys(true));
        noneDeleted(surveysApi.getPublishedSurveys(false));
    }
    
    @Test
    public void getMostRecentSurveyVersionAndDelete() throws Exception {
        // Test the interaction of publication and the two kinds of deletion
        SurveysApi surveysApi = developer.getClient(SurveysApi.class);
        
        Survey survey = TestSurvey.getSurvey(SurveyTest.class);
        GuidCreatedOnVersionHolder keys1 = createSurvey(surveysApi, survey);
        GuidCreatedOnVersionHolder keys2 = versionSurvey(surveysApi, keys1);
        String guid = keys1.getGuid();
        
        // You cannot publish a (logically) deleted survey
        surveysApi.deleteSurvey(keys1.getGuid(), keys1.getCreatedOn(), false).execute();
        try {
            surveysApi.publishSurvey(keys1.getGuid(), keys1.getCreatedOn(), false).execute();
            fail("Should have thrown an exception");
        } catch(EntityNotFoundException e) {
            
        }
        surveysApi.publishSurvey(keys2.getGuid(), keys2.getCreatedOn(), false).execute();
        surveysApi.deleteSurvey(keys2.getGuid(), keys2.getCreatedOn(), false).execute();
        Thread.sleep(1000);
        
        try {
            surveysApi.getMostRecentSurveyVersion(guid).execute().body();
            fail("Should have thrown exception");
        } catch(EntityNotFoundException e) {
        }
    }
    
    private void anyDeleted(Call<SurveyList> call) throws IOException {
        assertTrue(call.execute().body().getItems().stream().anyMatch(Survey::isDeleted));
    }
    
    private void noneDeleted(Call<SurveyList> call) throws IOException {
        assertTrue(call.execute().body().getItems().stream().noneMatch(Survey::isDeleted));
    }
    
    private SchedulePlan createSchedulePlanTo(GuidCreatedOnVersionHolder keys) {
        SurveyReference surveyReference = new SurveyReference().guid(keys.getGuid()).createdOn(keys.getCreatedOn());
        
        Activity activity = new Activity().label("Do this survey").activityType(ActivityType.SURVEY)
                .survey(surveyReference);
        Schedule schedule = new Schedule().activities(Lists.newArrayList(activity));
        schedule.setScheduleType(ScheduleType.ONCE);
        
        SimpleScheduleStrategy strategy = new SimpleScheduleStrategy().schedule(schedule);
        
        return new SchedulePlan().label("Plan").strategy(strategy);
    }
    
    private SurveyElement getSurveyElement(Survey survey, String id) {
        for (SurveyElement element : survey.getElements()) {
            if (element.getIdentifier().equals(id)) {
                return element;
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
