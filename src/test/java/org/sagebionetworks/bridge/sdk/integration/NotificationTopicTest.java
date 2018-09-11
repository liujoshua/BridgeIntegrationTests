package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
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

    private static TestUser admin;
    private static TestUser developer;

    @BeforeClass
    public static void before() throws Exception {
        admin = TestUserHelper.getSignedInAdmin();
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
        
        NotificationTopic topic = new NotificationTopic().name("Topic name").shortName("shortname")
                .description("topic description");
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
        
        api.deleteNotificationTopic(retrieved.getGuid(), false).execute();
        
        retrieved = api.getNotificationTopic(keys.getGuid()).execute().body();
        assertTrue(retrieved.isDeleted());
        
        admin.getClient(ForAdminsApi.class).deleteNotificationTopic(retrieved.getGuid(), true).execute();
        
        // Now it is really deleted
        try {
            api.getNotificationTopic(keys.getGuid()).execute();
            fail("Should have thrown exception");
        } catch(EntityNotFoundException e) {
            // expected exception
        }
    }
    
    @Test
    public void listTopics() throws Exception {
        NotificationsApi api = developer.getClient(NotificationsApi.class);
        List<NotificationTopic> list = api.getNotificationTopics(false).execute().body().getItems();
        int startingSize = list.size();
        
        NotificationTopic topic1 = new NotificationTopic().name("Topic 1").shortName("topic 1")
                .description("topic description");
        GuidHolder keys1 = api.createNotificationTopic(topic1).execute().body();

        NotificationTopic topic2 = new NotificationTopic().name("Topic 2").shortName("topic 2")
                .description("topic description");
        GuidHolder keys2 = api.createNotificationTopic(topic2).execute().body();
        
        try {
            list = api.getNotificationTopics(false).execute().body().getItems();
            assertEquals(startingSize+2, list.size());
            Set<String> guids = list.stream().map(NotificationTopic::getGuid).collect(Collectors.toSet());
            assertTrue(guids.contains(keys1.getGuid()));
            assertTrue(guids.contains(keys2.getGuid()));
            
            // delete one
            api.deleteNotificationTopic(keys1.getGuid(), false).execute();
            
            list = api.getNotificationTopics(false).execute().body().getItems();
            assertEquals(startingSize+1, list.size());
            guids = list.stream().map(NotificationTopic::getGuid).collect(Collectors.toSet());
            assertTrue(guids.contains(keys2.getGuid()));
            
            list = api.getNotificationTopics(true).execute().body().getItems();
            assertEquals(startingSize+2, list.size());
            guids = list.stream().map(NotificationTopic::getGuid).collect(Collectors.toSet());
            assertTrue(guids.contains(keys1.getGuid()));
            assertTrue(guids.contains(keys2.getGuid()));
        } finally {
            // physically delete them all
            ForAdminsApi adminApi = TestUserHelper.getSignedInAdmin().getClient(ForAdminsApi.class);
            adminApi.deleteNotificationTopic(keys1.getGuid(), true).execute();
            adminApi.deleteNotificationTopic(keys2.getGuid(), true).execute();
            
            list = api.getNotificationTopics(true).execute().body().getItems();
            assertEquals(startingSize, list.size());
        }
    }

    @Test
    public void withCriteria() throws Exception {
        NotificationsApi api = developer.getClient(NotificationsApi.class);

        // Create.
        NotificationTopic topic = new NotificationTopic().name("topic").shortName("topic").criteria(CRITERIA_1);
        String topicGuid = api.createNotificationTopic(topic).execute().body().getGuid();

        topic = api.getNotificationTopic(topicGuid).execute().body();
        assertEquals(CRITERIA_1.getAllOfGroups(), topic.getCriteria().getAllOfGroups());

        // Update.
        topic.setCriteria(CRITERIA_2);
        api.updateNotificationTopic(topicGuid, topic).execute();

        topic = api.getNotificationTopic(topicGuid).execute().body();
        assertEquals(CRITERIA_2.getAllOfGroups(), topic.getCriteria().getAllOfGroups());

        // List.
        List<NotificationTopic> topicList = api.getNotificationTopics(false).execute().body().getItems();
        topic = topicList.stream().filter(t -> t.getGuid().equals(topicGuid)).findAny().get();
        assertEquals(CRITERIA_2.getAllOfGroups(), topic.getCriteria().getAllOfGroups());

        // Delete.
        admin.getClient(ForAdminsApi.class).deleteNotificationTopic(topicGuid, true).execute();
        topicList = api.getNotificationTopics(true).execute().body().getItems();
        assertTrue(topicList.stream().noneMatch(t -> t.getGuid().equals(topicGuid)));
    }
}
