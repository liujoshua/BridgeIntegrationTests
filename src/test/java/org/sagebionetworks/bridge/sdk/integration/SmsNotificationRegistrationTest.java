package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.sagebionetworks.bridge.util.IntegTestUtils.TEST_APP_ID;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.google.common.collect.ImmutableList;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.rest.api.InternalApi;
import org.sagebionetworks.bridge.rest.api.NotificationsApi;
import org.sagebionetworks.bridge.rest.model.Criteria;
import org.sagebionetworks.bridge.rest.model.NotificationProtocol;
import org.sagebionetworks.bridge.rest.model.NotificationRegistration;
import org.sagebionetworks.bridge.rest.model.NotificationTopic;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.SignUp;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.rest.model.SubscriptionRequest;
import org.sagebionetworks.bridge.rest.model.SubscriptionStatus;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.util.IntegTestUtils;

public class SmsNotificationRegistrationTest {
    private static String autoTopicGuid1;
    private static String autoTopicGuid2;
    private static String manualTopicGuid;
    private static TestUserHelper.TestUser developer;
    private static TestUserHelper.TestUser researcher;

    private TestUserHelper.TestUser phoneUser;

    @BeforeClass
    public static void setup() throws Exception {
        // Ensure that we have the following notification topics:
        // 1. auto-topic-1 (assigned to data group sdk-int-1)
        // 2. auto-topic-2 (assigned to data group sdk-int-2)
        // 3. manual-topic (not managed by criteria)
        developer = TestUserHelper.createAndSignInUser(SmsNotificationRegistrationTest.class, false,
                Role.DEVELOPER);
        NotificationsApi notificationsApi = developer.getClient(NotificationsApi.class);

        List<NotificationTopic> topicList = notificationsApi.getNotificationTopics(false).execute().body().getItems();
        for (NotificationTopic oneTopic : topicList) {
            if ("auto-topic-1".equals(oneTopic.getName())) {
                autoTopicGuid1 = oneTopic.getGuid();
            } else if ("auto-topic-2".equals(oneTopic.getName())) {
                autoTopicGuid2 = oneTopic.getGuid();
            } else if ("manual-topic".equals(oneTopic.getName())) {
                manualTopicGuid = oneTopic.getGuid();
            }
        }

        if (autoTopicGuid1 == null) {
            Criteria criteria = new Criteria().addAllOfGroupsItem("sdk-int-1");
            NotificationTopic topicToCreate = new NotificationTopic().name("auto-topic-1").shortName("auto-1")
                    .criteria(criteria);
            autoTopicGuid1 = notificationsApi.createNotificationTopic(topicToCreate).execute().body().getGuid();
        }

        if (autoTopicGuid2 == null) {
            Criteria criteria = new Criteria().addAllOfGroupsItem("sdk-int-2");
            NotificationTopic topicToCreate = new NotificationTopic().name("auto-topic-2").shortName("auto-2")
                    .criteria(criteria);
            autoTopicGuid2 = notificationsApi.createNotificationTopic(topicToCreate).execute().body().getGuid();
        }

        if (manualTopicGuid == null) {
            NotificationTopic topicToCreate = new NotificationTopic().name("manual-topic").shortName("manual");
            manualTopicGuid = notificationsApi.createNotificationTopic(topicToCreate).execute().body().getGuid();
        }

        // Create researcher.
        researcher = TestUserHelper.createAndSignInUser(SmsNotificationRegistrationTest.class, false,
                Role.RESEARCHER);
    }

    @Before
    public void createUser() throws Exception {
        // Create phone user, initially with data group sdk-int-1.
        SignUp phoneSignUp = new SignUp().appId(TEST_APP_ID).consent(true).phone(IntegTestUtils.PHONE);
        phoneSignUp.addDataGroupsItem("sdk-int-1");
        phoneUser = new TestUserHelper.Builder(SmsNotificationRegistrationTest.class).withConsentUser(true)
                .withSignUp(phoneSignUp).createAndSignInUser();
    }

    @AfterClass
    public static void deleteDeveloperAndResearcher() throws Exception {
        if (developer != null) {
            developer.signOutAndDeleteUser();
        }
        if (researcher != null) {
            researcher.signOutAndDeleteUser();
        }
    }

    @After
    public void deletePhoneUser() throws Exception {
        if (phoneUser != null) {
            phoneUser.signOutAndDeleteUser();
        }
    }

    @Test
    public void userCreateRegistration() throws Exception {
        ForConsentedUsersApi api = phoneUser.getClient(ForConsentedUsersApi.class);

        // Create notification.
        NotificationRegistration registration = new NotificationRegistration()
                .protocol(NotificationProtocol.SMS).endpoint(IntegTestUtils.PHONE.getNumber());
        String registrationGuid = api.createNotificationRegistration(registration).execute().body().getGuid();

        // Test get API.
        registration = api.getNotificationRegistration(registrationGuid).execute().body();
        assertEquals(registrationGuid, registration.getGuid());
        assertEquals(NotificationProtocol.SMS, registration.getProtocol());
        assertEquals(IntegTestUtils.PHONE.getNumber(), registration.getEndpoint());

        // Test list API.
        List<NotificationRegistration> registrationList = api.getNotificationRegistrations().execute().body()
                .getItems();
        assertEquals(1, registrationList.size());

        registration = registrationList.get(0);
        assertEquals(registrationGuid, registration.getGuid());
        assertEquals(NotificationProtocol.SMS, registration.getProtocol());
        assertEquals(IntegTestUtils.PHONE.getNumber(), registration.getEndpoint());

        // Sleep for eventual consistency.
        Thread.sleep(2000);

        // We automatically subscribed to autoTopic1.
        Map<String, Boolean> subscriptionsByGuid = api.getTopicSubscriptions(registrationGuid).execute().body()
                .getItems().stream()
                .collect(Collectors.toMap(SubscriptionStatus::getTopicGuid, SubscriptionStatus::isSubscribed));
        assertTrue(subscriptionsByGuid.get(autoTopicGuid1));
        assertFalse(subscriptionsByGuid.get(autoTopicGuid2));
        assertFalse(subscriptionsByGuid.get(manualTopicGuid));

        // Manually subscribe to the manual topic. This doesn't affect the auto-topics.
        SubscriptionRequest subscriptionRequest = new SubscriptionRequest().addTopicGuidsItem(manualTopicGuid);
        api.subscribeToTopics(registrationGuid, subscriptionRequest).execute();

        // Sleep for eventual consistency.
        Thread.sleep(2000);

        subscriptionsByGuid = api.getTopicSubscriptions(registrationGuid).execute().body().getItems().stream()
                .collect(Collectors.toMap(SubscriptionStatus::getTopicGuid, SubscriptionStatus::isSubscribed));
        assertTrue(subscriptionsByGuid.get(autoTopicGuid1));
        assertFalse(subscriptionsByGuid.get(autoTopicGuid2));
        assertTrue(subscriptionsByGuid.get(manualTopicGuid));

        // Switch to data group sdk-int-2. This flips the auto topics, but doesn't touch the manual topic.
        StudyParticipant participant = api.getUsersParticipantRecord(false).execute().body();
        participant.setDataGroups(ImmutableList.of("sdk-int-2"));
        api.updateUsersParticipantRecord(participant).execute();

        // Sleep for eventual consistency.
        Thread.sleep(2000);

        subscriptionsByGuid = api.getTopicSubscriptions(registrationGuid).execute().body().getItems().stream()
                .collect(Collectors.toMap(SubscriptionStatus::getTopicGuid, SubscriptionStatus::isSubscribed));
        assertFalse(subscriptionsByGuid.get(autoTopicGuid1));
        assertTrue(subscriptionsByGuid.get(autoTopicGuid2));
        assertTrue(subscriptionsByGuid.get(manualTopicGuid));

        // Test delete API.
        api.deleteNotificationRegistration(registrationGuid).execute();
        registrationList = api.getNotificationRegistrations().execute().body().getItems();
        assertTrue(registrationList.stream().noneMatch(t -> t.getGuid().equals(registrationGuid)));
    }

    @Test
    public void researcherCreatesRegistration() throws Exception {
        // Create registration.
        String userId = phoneUser.getUserId();
        researcher.getClient(InternalApi.class).createSmsRegistration(userId).execute();

        // Verify the user's registration.
        List<NotificationRegistration> registrationList = researcher.getClient(NotificationsApi.class)
                .getParticipantPushNotificationRegistrations(userId).execute().body().getItems();
        assertEquals(1, registrationList.size());

        NotificationRegistration registration = registrationList.get(0);
        assertEquals(NotificationProtocol.SMS, registration.getProtocol());
        assertEquals(IntegTestUtils.PHONE.getNumber(), registration.getEndpoint());

        // Verify auto-subscriptions.
        Map<String, Boolean> subscriptionsByGuid = phoneUser.getClient(ForConsentedUsersApi.class)
                .getTopicSubscriptions(registration.getGuid()).execute().body().getItems().stream()
                .collect(Collectors.toMap(SubscriptionStatus::getTopicGuid, SubscriptionStatus::isSubscribed));
        assertTrue(subscriptionsByGuid.get(autoTopicGuid1));
        assertFalse(subscriptionsByGuid.get(autoTopicGuid2));
        assertFalse(subscriptionsByGuid.get(manualTopicGuid));
    }
}
