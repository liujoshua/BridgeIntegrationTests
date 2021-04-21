package org.sagebionetworks.bridge.sdk.integration;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.sagebionetworks.bridge.rest.model.ActivityEventUpdateType.FUTURE_ONLY;
import static org.sagebionetworks.bridge.rest.model.ActivityEventUpdateType.MUTABLE;
import static org.sagebionetworks.bridge.util.IntegTestUtils.SHARED_APP_ID;
import static org.sagebionetworks.bridge.util.IntegTestUtils.TEST_APP_ID;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Predicate;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.apache.commons.lang3.RandomStringUtils;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.sagebionetworks.bridge.rest.ApiClientProvider;
import org.sagebionetworks.bridge.rest.ClientManager;
import org.sagebionetworks.bridge.rest.RestUtils;
import org.sagebionetworks.bridge.rest.model.ABTestGroup;
import org.sagebionetworks.bridge.rest.model.ABTestScheduleStrategy;
import org.sagebionetworks.bridge.rest.model.Activity;
import org.sagebionetworks.bridge.rest.model.AndroidAppLink;
import org.sagebionetworks.bridge.rest.model.App;
import org.sagebionetworks.bridge.rest.model.AppleAppLink;
import org.sagebionetworks.bridge.rest.model.ClientInfo;
import org.sagebionetworks.bridge.rest.model.MasterSchedulerConfig;
import org.sagebionetworks.bridge.rest.model.OAuthProvider;
import org.sagebionetworks.bridge.rest.model.Phone;
import org.sagebionetworks.bridge.rest.model.Schedule;
import org.sagebionetworks.bridge.rest.model.SchedulePlan;
import org.sagebionetworks.bridge.rest.model.ScheduleType;
import org.sagebionetworks.bridge.rest.model.ScheduledActivity;
import org.sagebionetworks.bridge.rest.model.SignIn;
import org.sagebionetworks.bridge.rest.model.SimpleScheduleStrategy;
import org.sagebionetworks.bridge.rest.model.TaskReference;
import org.sagebionetworks.bridge.util.IntegTestUtils;

public class Tests {
    private static final Logger LOG = LoggerFactory.getLogger(Tests.class);

    public static final SignIn API_SIGNIN = new SignIn().appId(TEST_APP_ID);
    public static final SignIn SHARED_SIGNIN = new SignIn().appId(SHARED_APP_ID);
    public static final String PACKAGE = "org.sagebionetworks.bridge";
    public static final String MOBILE_APP_NAME = "DummyApp";
    public static final String APP_ID = PACKAGE + "." + MOBILE_APP_NAME;
    public static final String FINGERPRINT = "14:6D:E9:83:C5:73:06:50:D8:EE:B9:95:2F:34:FC:64:16:A0:83:42:E6:1D:BE:A8:8A:04:96:B2:3F:CF:44:E5";
    public static final String APP_NAME = "Integration Tests";
    public static final String PASSWORD = "P@ssword`1";
    public static final String STUDY_ID_1 = "study1";
    public static final String STUDY_ID_2 = "study2";
    public static final String ORG_ID_1 = "org1";
    public static final String ORG_ID_2 = "org2";
    public static final Phone PHONE = new Phone().number("+19712486796").regionCode("US");
    public static final String NATIONAL_PHONE_FORMAT = "(971) 248-6796";
    public static final String SYNAPSE_USER_ID = "88888";

    private static final int RETRY_MAX_TRIES = 5;
    private static final long RETRY_SLEEP_MILLIS = 1000;

    public static ClientInfo getClientInfoWithVersion(String osName, int version) {
        return new ClientInfo().appName(APP_NAME).appVersion(version).deviceName(APP_NAME).osName(osName)
                .osVersion("2.0.0").sdkName("BridgeJavaSDK").sdkVersion(Integer.parseInt(IntegTestUtils.CONFIG.getSdkVersion()));
    }

    // This API exists because there's a bug in TestUserHelper.getNonAuthClient() which uses ClientManager.getUrl()
    // instead of ClientManager.getHostUrl().
    public static ApiClientProvider getUnauthenticatedClientProvider(ClientManager clientManager, String appId) {
        String baseUrl = clientManager.getHostUrl();
        String clientInfo = RestUtils.getUserAgent(clientManager.getClientInfo());
        String lang = RestUtils.getAcceptLanguage(clientManager.getAcceptedLanguages());
        return new ApiClientProvider(baseUrl, clientInfo, lang, appId);
    }

    public static String randomIdentifier(Class<?> cls) {
        return ("sdk-" + cls.getSimpleName().toLowerCase() + "-" + RandomStringUtils.randomAlphabetic(5)).toLowerCase();
    }

    /**
     * Helper function that wraps around retries. Used for tests that can fail sporadically (e.g. anything that uses a
     * DynamoDB secondary index.
     *
     * @param testCall
     *         the test code that might sporadically fail (e.g. a server call using DynamoDB secondary index)
     * @param validationCall
     *         a callable that returns true if the test code succeeded, false if we need to retry
     */
    public static <T> T retryHelper(Callable<T> testCall, Predicate<T> validationCall) {
        for (int i = 0; i < RETRY_MAX_TRIES; i++) {
            try {
                Thread.sleep(RETRY_SLEEP_MILLIS);
            } catch (InterruptedException ex) {
                // Ignore exception.
            }

            try {
                T result = testCall.call();
                if (validationCall.test(result)) {
                    return result;
                } else {
                    LOG.warn("Validation failed in retryHelper (try #" + i + ")");
                }
            } catch (Exception ex) {
                LOG.warn("Exception thrown in retryHelper (try #" + i + "): " + ex.getMessage(), ex);
            }
        }

        throw new RuntimeException("retryHelper failed");
    }

    public static List<Activity> labelActivities(List<Activity> activities, String randomLabel) {
        List<Activity> taggedActivities = Lists.newArrayList();
        for (Activity activity : activities) {
            activity.setLabel(randomLabel);
            taggedActivities.add(activity);
        }
        return taggedActivities;
    }
    
    public static List<ScheduledActivity> filterActivitiesForLabel(List<ScheduledActivity> activities, String label) {
        List<ScheduledActivity> filtered = Lists.newArrayList();
        for (ScheduledActivity activity : activities) {
            if (label.equals(activity.getActivity().getLabel())) {
                filtered.add(activity);
            }
        }
        return filtered;
    }
    
    private static void setTaskActivity(Schedule schedule, String taskIdentifier) {
        checkNotNull(taskIdentifier);
        
        TaskReference ref = new TaskReference();
        ref.setIdentifier(taskIdentifier);

        Activity act = new Activity();
        act.setLabel("Task activity");
        act.setTask(ref);
        
        schedule.setActivities(Lists.newArrayList(act));
    }
    
    public static SchedulePlan getABTestSchedulePlan() {
        SchedulePlan plan = new SchedulePlan();
        plan.setLabel("A/B Test Schedule Plan");
        Schedule schedule1 = new Schedule();
        schedule1.setScheduleType(ScheduleType.RECURRING);
        schedule1.setCronTrigger("0 0 11 ? * MON,WED,FRI *");
        setTaskActivity(schedule1, "task:AAA");
        schedule1.setExpires("PT1H");
        schedule1.setLabel("Test label for the user");
        
        Schedule schedule2 = new Schedule();
        schedule2.setCronTrigger("0 0 11 ? * MON,WED,FRI *");
        schedule2.setScheduleType(ScheduleType.RECURRING);
        setTaskActivity(schedule2, "task:BBB");
        schedule2.setExpires("PT1H");
        schedule2.setLabel("Test label for the user");

        Schedule schedule3 = new Schedule();
        schedule3.setCronTrigger("0 0 11 ? * MON,WED,FRI *");
        setTaskActivity(schedule3, "task:CCC");
        // This doesn't exist and now it matters, because we look for a survey to update the identifier
        // setSurveyActivity(schedule3, "identifier", "GUID-AAA", DateTime.parse("2015-01-27T17:46:31.237Z"));
        schedule3.setExpires("PT1H");
        schedule3.setLabel("Test label for the user");
        schedule3.setScheduleType(ScheduleType.RECURRING);

        ABTestScheduleStrategy strategy = new ABTestScheduleStrategy();
        strategy.setScheduleGroups(Lists.newArrayList(abGroup(40, schedule1), abGroup(40, schedule2), abGroup(20, schedule3)));
        strategy.setType("ABTestScheduleStrategy");
        
        plan.setStrategy(strategy);
        return plan;
    }
    
    private static ABTestGroup abGroup(int percentage, Schedule schedule) {
        ABTestGroup group = new ABTestGroup();
        group.setPercentage(percentage);
        group.setSchedule(schedule);
        return group;
    }
    
    public static SchedulePlan getSimpleSchedulePlan() {
        SchedulePlan plan = new SchedulePlan();
        plan.setLabel("Cron-based schedule");
        Schedule schedule = new Schedule();
        schedule.setCronTrigger("0 0 11 ? * MON,WED,FRI *");
        setTaskActivity(schedule, "task:CCC");
        schedule.setExpires("PT1H");
        schedule.setLabel("Test label for the user");
        schedule.setScheduleType(ScheduleType.RECURRING);
        
        SimpleScheduleStrategy strategy = new SimpleScheduleStrategy();
        strategy.setSchedule(schedule);
        strategy.setType("SimpleScheduleStrategy");
        
        plan.setStrategy(strategy);
        return plan;
    }
    
    public static SchedulePlan getDailyRepeatingSchedulePlan() {
        SchedulePlan plan = new SchedulePlan();
        plan.setLabel("Daily repeating schedule plan");
        Schedule schedule = new Schedule();
        schedule.setLabel("Test label for the user");
        schedule.setScheduleType(ScheduleType.RECURRING);
        schedule.setInterval("P1D");
        schedule.setExpires("P1D");
        schedule.setTimes(Lists.newArrayList("12:00"));
        
        TaskReference taskReference = new TaskReference();
        taskReference.setIdentifier("task:CCC");
        
        Activity activity = new Activity();
        activity.setLabel("Task activity");
        activity.setTask(taskReference);
        schedule.setActivities(Lists.newArrayList(activity));
        
        SimpleScheduleStrategy strategy = new SimpleScheduleStrategy();
        strategy.setSchedule(schedule);
        strategy.setType("SimpleScheduleStrategy");
        
        plan.setStrategy(strategy);
        return plan;
    }
    
    public static SchedulePlan getPersistentSchedulePlan() {
        SchedulePlan plan = new SchedulePlan();
        plan.setLabel("Persistent schedule");
        Schedule schedule = new Schedule();
        setTaskActivity(schedule, "CCC");
        schedule.setEventId("task:"+schedule.getActivities().get(0).getTask().getIdentifier()+":finished");
        schedule.setLabel("Test label");
        schedule.setScheduleType(ScheduleType.PERSISTENT);

        SimpleScheduleStrategy strategy = new SimpleScheduleStrategy();
        strategy.setSchedule(schedule);
        strategy.setType("SimpleScheduleStrategy");
        
        plan.setStrategy(strategy);
        return plan;
    }

    public static Schedule getSimpleSchedule(SchedulePlan plan) {
        return ((SimpleScheduleStrategy)plan.getStrategy()).getSchedule();
    }
    
    public static List<Activity> getActivitiesFromSimpleStrategy(SchedulePlan plan) {
        return ((SimpleScheduleStrategy)plan.getStrategy()).getSchedule().getActivities();    
    }
    
    public static Activity getActivityFromSimpleStrategy(SchedulePlan plan) {
        return ((SimpleScheduleStrategy)plan.getStrategy()).getSchedule().getActivities().get(0);    
    }
    
    public static App getApp(String identifier, Long version) {
        App app = new App();
        app.setIdentifier(identifier);
        app.setAutoVerificationEmailSuppressed(true);
        app.setMinAgeOfConsent(18);
        app.setName("Test App [SDK]");
        app.setSponsorName("The Test App Folks [SDK]");
        app.setStrictUploadValidationEnabled(true);
        app.setAppIdExcludedInExport(true);
        app.setSupportEmail("test@test.com");
        app.setConsentNotificationEmail("bridge-testing+test2@sagebase.org");
        app.setTechnicalEmail("test3@test.com");
        app.setUsesCustomExportSchedule(true);
        app.setUserProfileAttributes(Lists.newArrayList("new_profile_attribute"));
        app.setTaskIdentifiers(Lists.newArrayList("taskA")); // setting it differently just for the heck of it 
        app.setDataGroups(Lists.newArrayList("beta_users", "production_users"));
        app.setCustomEvents(ImmutableMap.of("event1", MUTABLE, "event2", FUTURE_ONLY));
        app.setEmailVerificationEnabled(true);
        app.setEmailSignInEnabled(true);
        app.setHealthCodeExportEnabled(true);
        app.setDisableExport(true);
        
        Map<String,Integer> map = new HashMap<>();
        map.put("Android", 10);
        map.put("iPhone OS", 14);
        app.setMinSupportedAppVersions(map);
        
        Map<String,String> platformMap = new HashMap<>();
        platformMap.put("Android", "arn:android:"+identifier);
        platformMap.put("iPhone OS", "arn:ios:"+identifier);
        app.setPushNotificationARNs(platformMap);
        
        OAuthProvider oauthProvider = new OAuthProvider().clientId("clientId").secret("secret")
                .endpoint("https://www.server.com/").callbackUrl("https://client.callback.com/")
                .introspectEndpoint("http://example.com/introspect");
        Map<String,OAuthProvider> oauthProviders = new HashMap<>();
        oauthProviders.put("myProvider", oauthProvider);
        app.setOAuthProviders(oauthProviders);

        List<AndroidAppLink> androidAppLinks = new ArrayList<>();
        androidAppLinks.add(new AndroidAppLink().namespace(PACKAGE).packageName(MOBILE_APP_NAME)
                .addSha256CertFingerprintsItem(FINGERPRINT));
        app.setAndroidAppLinks(androidAppLinks);
        
        List<AppleAppLink> appleAppLinks = new ArrayList<>();
        appleAppLinks.add(new AppleAppLink().appID(APP_ID).addPathsItem("/" + identifier + "/*"));
        app.setAppleAppLinks(appleAppLinks);
        
        if (version != null) {
            app.setVersion(version);
        }
        return app;
    }

    public static void assertDatesWithTimeZoneEqual(DateTime expected, DateTime actual) {
        // Equals only asserts the instant is the same, not the time zone.
        assertTrue(expected.isEqual(actual));

        // This ensures that zones such as "America/Los_Angeles" and "-07:00" are equal
        assertEquals(expected.getZone().getOffset(expected), actual.getZone().getOffset(actual));
    }

    public static <T> boolean assertListsEqualIgnoringOrder(List<T> list1, List<T> list2) {
        if (list1.size() != list2.size()) {
            return false;
        }
        Set<T> setA = Sets.newHashSet(list1);
        Set<T> setB = Sets.newHashSet(list2);
        return Sets.difference(setA, setB).isEmpty();
    }
    
    // Adapted from http://plexus.codehaus.org/plexus-utils which seems to be defunct.
    
    public static void setVariableValueInObject(Object object, String variable, Object value) throws IllegalAccessException {
        Field field = getFieldByNameIncludingSuperclasses(variable, object.getClass());
        field.setAccessible(true);
        field.set(object, value);
    }
    
    @SuppressWarnings("rawtypes")
    private static Field getFieldByNameIncludingSuperclasses(String fieldName, Class clazz) {
        Field retValue = null;
        try {
            retValue = clazz.getDeclaredField(fieldName);
        } catch (NoSuchFieldException e) {
            Class superclass = clazz.getSuperclass();
            if ( superclass != null ) {
                retValue = getFieldByNameIncludingSuperclasses( fieldName, superclass );
            }
        }
        return retValue;
    }

    public static MasterSchedulerConfig getMastSchedulerConfig() {
        MasterSchedulerConfig config = new MasterSchedulerConfig();
        config.setScheduleId("test-schedule-id");
        config.setCronSchedule("0 0 0 1 * ?");
        config.setRequestTemplate(ImmutableMap.of("a", "string", "b", true));
        config.setSqsQueueUrl("https://sqs.us-east-1.amazonaws.com/649232250620/Bridge-UDD-Dummy-Queue");
        config.setVersion(1L);
        
        return config;
    }
    
    public static String escapeJSON(String json) {
        return json.replaceAll("'", "\"");
    }
}
