package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.List;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.api.NotificationsApi;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.model.Criteria;
import org.sagebionetworks.bridge.rest.model.GuidHolder;
import org.sagebionetworks.bridge.rest.model.NotificationTopic;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;

@SuppressWarnings("ConstantConditions")
public class NotificationTopicTest {
    private static final Criteria CRITERIA_1 = new Criteria().addAllOfGroupsItem("sdk-int-1");
    private static final Criteria CRITERIA_2 = new Criteria().addAllOfGroupsItem("sdk-int-2");

    private static TestUser developer;

    @BeforeClass
    public static void before() throws Exception {
        developer = TestUserHelper.createAndSignInUser(ParticipantsTest.class, true, Role.DEVELOPER);
    }

    @AfterClass
    public static void after() throws Exception {
        if (developer != null) {
            developer.signOutAndDeleteUser();
        }
    }

    @Test
    public void crud() throws IOException {
        NotificationsApi api = developer.getClient(NotificationsApi.class);
        
        NotificationTopic topic = new NotificationTopic().name("Topic name").description("topic description");
        GuidHolder keys = api.createNotificationTopic(topic).execute().body();
        
        NotificationTopic retrieved = api.getNotificationTopic(keys.getGuid()).execute().body();
        assertEquals(topic.getName(), retrieved.getName());
        assertEquals(topic.getDescription(), retrieved.getDescription());
        assertEquals(keys.getGuid(), retrieved.getGuid());
        assertNotNull(retrieved.getCreatedOn());
        assertNotNull(retrieved.getModifiedOn());
        
        topic.setName("A new name");
        topic.setDescription("A new description");
        
        api.updateNotificationTopic(keys.getGuid(), topic).execute();
        retrieved = api.getNotificationTopic(keys.getGuid()).execute().body();
        assertEquals(keys.getGuid(), retrieved.getGuid());
        assertEquals("A new name", retrieved.getName());
        assertEquals("A new description", retrieved.getDescription());
        
        api.deleteNotificationTopic(retrieved.getGuid()).execute();
        
        try {
            api.getNotificationTopic(keys.getGuid()).execute();
            fail("Should have thrown exception");
        } catch(EntityNotFoundException e) {
            // expected exception
        }
    }

    @Test
    public void withCriteria() throws Exception {
        NotificationsApi api = developer.getClient(NotificationsApi.class);

        // Create.
        NotificationTopic topic = new NotificationTopic().name("topic").criteria(CRITERIA_1);
        String topicGuid = api.createNotificationTopic(topic).execute().body().getGuid();

        topic = api.getNotificationTopic(topicGuid).execute().body();
        assertEquals(CRITERIA_1.getAllOfGroups(), topic.getCriteria().getAllOfGroups());

        // Update.
        topic.setCriteria(CRITERIA_2);
        api.updateNotificationTopic(topicGuid, topic).execute();

        topic = api.getNotificationTopic(topicGuid).execute().body();
        assertEquals(CRITERIA_2.getAllOfGroups(), topic.getCriteria().getAllOfGroups());

        // List.
        List<NotificationTopic> topicList = api.getNotificationTopics().execute().body().getItems();
        topic = topicList.stream().filter(t -> t.getGuid().equals(topicGuid)).findAny().get();
        assertEquals(CRITERIA_2.getAllOfGroups(), topic.getCriteria().getAllOfGroups());

        // Delete.
        api.deleteNotificationTopic(topicGuid).execute();
        topicList = api.getNotificationTopics().execute().body().getItems();
        assertTrue(topicList.stream().noneMatch(t -> t.getGuid().equals(topicGuid)));
    }
}
