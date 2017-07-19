package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.api.ForWorkersApi;
import org.sagebionetworks.bridge.rest.model.AccountSummaryList;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.sdk.integration.TestUserHelper.TestUser;

public class WorkerApiTest {
    
    TestUser testUser;
    ForWorkersApi workersApi;

    @Before
    public void before() throws Exception {
        testUser = TestUserHelper.createAndSignInUser(ParticipantsTest.class, true, Role.WORKER);
        workersApi = testUser.getClient(ForWorkersApi.class);
    }

    @After
    public void after() throws Exception {
        if (testUser != null) {
            testUser.signOutAndDeleteUser();
        }
    }
    
    @Test
    public void retrieveUsers() throws Exception {
        AccountSummaryList list = workersApi.getParticipantsInStudy("api", 0, 5, "", null, null).execute().body();
        assertTrue(list.getTotal() > 0);
        
        list = workersApi.getParticipantsInStudy("api", 0, 5, testUser.getEmail(), null, null).execute().body();
        assertEquals(1, list.getItems().size());
        assertEquals(testUser.getEmail(), list.getItems().get(0).getEmail());
        
        StudyParticipant participant = workersApi.getParticipantInStudy("api", testUser.getSession().getId()).execute()
                .body();
        
        assertEquals(testUser.getEmail(), participant.getEmail());
        assertNotNull(participant.getHealthCode());
    }
    
}
