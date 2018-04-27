package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import java.io.IOException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.api.NotificationsApi;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.model.GuidHolder;
import org.sagebionetworks.bridge.rest.model.NotificationTopic;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;

public class NotificationTopicTest {

    private TestUser developer;
    
    @Before
    public void before() throws Exception {
        developer = TestUserHelper.createAndSignInUser(ParticipantsTest.class, true, Role.DEVELOPER);
    }
    
    @After
    public void after() throws Exception {
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
            
        }
    }

}
