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
import org.sagebionetworks.bridge.rest.model.SignUp;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.sdk.integration.TestUserHelper.TestUser;

public class WorkerApiTest {
    
    TestUser worker;
    TestUser phoneUser;
    ForWorkersApi workersApi;

    @Before
    public void before() throws Exception {
        worker = TestUserHelper.createAndSignInUser(WorkerApiTest.class, true, Role.WORKER);
        workersApi = worker.getClient(ForWorkersApi.class);
    }

    @After
    public void after() throws Exception {
        if (worker != null) {
            worker.signOutAndDeleteUser();
        }
        if (phoneUser != null) {
            phoneUser.signOutAndDeleteUser();
        }
    }
    
    @Test
    public void retrieveUsers() throws Exception {
        AccountSummaryList list = workersApi.getParticipantsInStudy("api", 0, 5, "", null, null, null).execute().body();
        assertTrue(list.getTotal() > 0);
        
        list = workersApi.getParticipantsInStudy("api", 0, 5, worker.getEmail(), null, null, null).execute().body();
        assertEquals(1, list.getItems().size());
        assertEquals(worker.getEmail(), list.getItems().get(0).getEmail());
        
        StudyParticipant participant = workersApi.getParticipantInStudy("api", worker.getSession().getId()).execute()
                .body();
        
        assertEquals(worker.getEmail(), participant.getEmail());
        assertNotNull(participant.getHealthCode());
    }
    
    @Test
    public void retrieveUsersWithPhone() throws Exception {
        SignUp signUp = new SignUp().phone(Tests.PHONE).password("P@ssword`1");
        phoneUser = TestUserHelper.createAndSignInUser(WorkerApiTest.class, true, signUp);
        
        AccountSummaryList list = workersApi.getParticipantsInStudy("api", 0, 5, null, "248-6796", null, null).execute().body();
        assertEquals(1, list.getItems().size());
        assertEquals(phoneUser.getPhone().getNumber(), list.getItems().get(0).getPhone().getNumber());
        
        String userId = list.getItems().get(0).getId();
        StudyParticipant participant = workersApi.getParticipantInStudy("api", userId).execute().body();
        
        assertEquals(phoneUser.getPhone().getNumber(), participant.getPhone().getNumber());
        assertNotNull(participant.getHealthCode());
    }
    
}
