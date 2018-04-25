package org.sagebionetworks.bridge.sdk.integration;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.apache.commons.lang3.RandomStringUtils;
import org.joda.time.DateTime;

import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.api.ParticipantsApi;
import org.sagebionetworks.bridge.rest.model.ABTestGroup;
import org.sagebionetworks.bridge.rest.model.ABTestScheduleStrategy;
import org.sagebionetworks.bridge.rest.model.AccountSummaryList;
import org.sagebionetworks.bridge.rest.model.Activity;
import org.sagebionetworks.bridge.rest.model.AndroidAppLink;
import org.sagebionetworks.bridge.rest.model.AppleAppLink;
import org.sagebionetworks.bridge.rest.model.ClientInfo;
import org.sagebionetworks.bridge.rest.model.EmailTemplate;
import org.sagebionetworks.bridge.rest.model.MimeType;
import org.sagebionetworks.bridge.rest.model.OAuthProvider;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.Schedule;
import org.sagebionetworks.bridge.rest.model.SchedulePlan;
import org.sagebionetworks.bridge.rest.model.ScheduleType;
import org.sagebionetworks.bridge.rest.model.ScheduledActivity;
import org.sagebionetworks.bridge.rest.model.SimpleScheduleStrategy;
import org.sagebionetworks.bridge.rest.model.SmsTemplate;
import org.sagebionetworks.bridge.rest.model.Study;
import org.sagebionetworks.bridge.rest.model.TaskReference;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.util.IntegTestUtils;

public class Tests {
    public static final String PACKAGE = "org.sagebionetworks.bridge";
    public static final String MOBILE_APP_NAME = "DummyApp";
    public static final String APP_ID = PACKAGE + "." + MOBILE_APP_NAME;
    public static final String FINGERPRINT = "14:6D:E9:83:C5:73:06:50:D8:EE:B9:95:2F:34:FC:64:16:A0:83:42:E6:1D:BE:A8:8A:04:96:B2:3F:CF:44:E5";
    public static final String APP_NAME = "Integration Tests";
    public static final String PASSWORD = "P@ssword`1";

    public static final EmailTemplate TEST_RESET_PASSWORD_TEMPLATE = new EmailTemplate().subject("Reset your password")
        .body("<p>${url}</p>").mimeType(MimeType.TEXT_HTML);
    public static final EmailTemplate TEST_VERIFY_EMAIL_TEMPLATE = new EmailTemplate().subject("Verify your email")
        .body("<p>${url}</p>").mimeType(MimeType.TEXT_HTML);
    public static final EmailTemplate TEST_EMAIL_SIGNIN_TEMPLATE = new EmailTemplate().subject("${studyName} sign in link")
            .body("<p>${host}/${token}</p>").mimeType(MimeType.TEXT_HTML);
    public static final EmailTemplate TEST_ACCOUNT_EXISTS_TEMPLATE = new EmailTemplate().subject("Your ${studyName} account")
            .body("<p>${url}</p>").mimeType(MimeType.TEXT_HTML);
    public static final SmsTemplate RESET_PASSWORD_SMS_TEMPLATE = new SmsTemplate().message("resetPasswordSmsTemplate ${resetPasswordUrl}");
    public static final SmsTemplate PHONE_SIGNIN_SMS_TEMPLATE = new SmsTemplate().message("phoneSignInSmsTemplate ${token}");
    public static final SmsTemplate APP_INSTALL_LINK_SMS_TEMPLATE = new SmsTemplate().message("appInstallLinkSmsTemplate ${appInstallUrl}");
    public static final SmsTemplate VERIFY_PHONE_SMS_TEMPLATE = new SmsTemplate().message("verifyPhoneSmsTemplate ${token}");
    public static final SmsTemplate ACCOUNT_EXISTS_SMS_TEMPLATE = new SmsTemplate().message("accountExistsSmsTemplate ${token}");

    public static ClientInfo getClientInfoWithVersion(String osName, int version) {
        return new ClientInfo().appName(APP_NAME).appVersion(version).deviceName(APP_NAME).osName(osName)
                .osVersion("2.0.0").sdkName("BridgeJavaSDK").sdkVersion(Integer.parseInt(IntegTestUtils.CONFIG.getSdkVersion()));
    }

    public static String randomIdentifier(Class<?> cls) {
        return ("sdk-" + cls.getSimpleName().toLowerCase() + "-" + RandomStringUtils.randomAlphabetic(5)).toLowerCase();
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
    
    public static Study getStudy(String identifier, Long version) {
        Study study = new Study();
        study.setIdentifier(identifier);
        study.setAutoVerificationEmailSuppressed(true);
        study.setMinAgeOfConsent(18);
        study.setName("Test Study [SDK]");
        study.setSponsorName("The Test Study Folks [SDK]");
        study.setStrictUploadValidationEnabled(true);
        study.setStudyIdExcludedInExport(true);
        study.setSupportEmail("test@test.com");
        study.setConsentNotificationEmail("bridge-testing+test2@sagebase.org");
        study.setTechnicalEmail("test3@test.com");
        study.setUsesCustomExportSchedule(true);
        study.getUserProfileAttributes().add("new_profile_attribute");
        study.setTaskIdentifiers(Lists.newArrayList("taskA")); // setting it differently just for the heck of it 
        study.setDataGroups(Lists.newArrayList("beta_users", "production_users"));
        study.setResetPasswordTemplate(Tests.TEST_RESET_PASSWORD_TEMPLATE);
        study.setVerifyEmailTemplate(Tests.TEST_VERIFY_EMAIL_TEMPLATE);
        study.setEmailSignInTemplate(Tests.TEST_EMAIL_SIGNIN_TEMPLATE);
        study.setAccountExistsTemplate(Tests.TEST_ACCOUNT_EXISTS_TEMPLATE);
        study.setResetPasswordSmsTemplate(RESET_PASSWORD_SMS_TEMPLATE);
        study.setPhoneSignInSmsTemplate(PHONE_SIGNIN_SMS_TEMPLATE);
        study.setAppInstallLinkSmsTemplate(APP_INSTALL_LINK_SMS_TEMPLATE);
        study.setVerifyPhoneSmsTemplate(VERIFY_PHONE_SMS_TEMPLATE);
        study.setAccountExistsSmsTemplate(ACCOUNT_EXISTS_SMS_TEMPLATE);
        study.setEmailVerificationEnabled(true);
        study.setEmailSignInEnabled(true);
        study.setHealthCodeExportEnabled(true);
        study.setDisableExport(true);
        
        Map<String,Integer> map = new HashMap<>();
        map.put("Android", 10);
        map.put("iPhone OS", 14);
        study.setMinSupportedAppVersions(map);
        
        Map<String,String> platformMap = new HashMap<>();
        platformMap.put("Android", "arn:android:"+identifier);
        platformMap.put("iPhone OS", "arn:ios:"+identifier);
        study.setPushNotificationARNs(platformMap);
        
        OAuthProvider oauthProvider = new OAuthProvider().clientId("clientId").secret("secret")
                .endpoint("https://www.server.com/").callbackUrl("https://client.callback.com/");
        Map<String,OAuthProvider> oauthProviders = new HashMap<>();
        oauthProviders.put("myProvider", oauthProvider);
        study.setOAuthProviders(oauthProviders);

        List<AndroidAppLink> androidAppLinks = new ArrayList<>();
        androidAppLinks.add(new AndroidAppLink().namespace(PACKAGE).packageName(MOBILE_APP_NAME)
                .addSha256CertFingerprintsItem(FINGERPRINT));
        study.setAndroidAppLinks(androidAppLinks);
        
        List<AppleAppLink> appleAppLinks = new ArrayList<>();
        appleAppLinks.add(new AppleAppLink().appID(APP_ID).addPathsItem("/" + identifier + "/*"));
        study.setAppleAppLinks(appleAppLinks);
        
        if (version != null) {
            study.setVersion(version);
        }
        return study;
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
}
