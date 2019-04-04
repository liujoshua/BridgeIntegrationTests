package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.api.ParticipantsApi;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;

import com.google.gson.Gson;
import com.google.gson.JsonElement;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * The user profile APIs are deprecated, but still heavily in use. This verifies the APIs work and don't 
 * change a property like notifyByEmail. There is no SDK support for this call.
 */
public class UserProfileTest {
    
    TestUser testUser;

    @Before
    public void before() throws Exception {
        testUser = TestUserHelper.createAndSignInUser(ParticipantsTest.class, true);
    }

    @After
    public void after() throws Exception {
        if (testUser != null) {
            testUser.signOutAndDeleteUser();
        }
    }
    
    @Test
    public void canCrudUserProfileWithDeprecatedApi() throws Exception {
        StudyParticipant newParticipant = new StudyParticipant();
        newParticipant.setFirstName("FirstName2");
        newParticipant.setLastName("LastName2");
        newParticipant.setNotifyByEmail(null);
        
        JsonElement node = new Gson().toJsonTree(newParticipant);
        node.getAsJsonObject().addProperty("can_be_recontacted", "true");
        String json = new Gson().toJson(node);
        
        Request request = new Request.Builder().url(testUser.getClientManager().getHostUrl() + "/v3/users/self")
                .header("Bridge-Session", testUser.getSession().getSessionToken())
                .method("POST", RequestBody.create(MediaType.parse("application/json"), json)).build();
        
        OkHttpClient client = new OkHttpClient();
        Response response = client.newCall(request).execute();
        assertEquals(200, response.code());
        
        // Now get the participant record and verify that notifyByEmail is true (the default)
        ParticipantsApi participantsApi = testUser.getClient(ParticipantsApi.class);
        
        StudyParticipant participant = participantsApi.getUsersParticipantRecord(false).execute().body();
        
        assertEquals("FirstName2", participant.getFirstName());
        assertEquals("LastName2", participant.getLastName());
        assertTrue(participant.isNotifyByEmail()); // NOT CHANGED
        assertEquals("true", participant.getAttributes().get("can_be_recontacted"));
    }
    
}
