package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.api.ForWorkersApi;
import org.sagebionetworks.bridge.rest.api.ParticipantsApi;
import org.sagebionetworks.bridge.rest.model.AccountSummaryList;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.SignUp;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.sdk.integration.TestUserHelper.TestUser;

public class WorkerApiTest {
    
    TestUser worker;
    TestUser researcher;
    TestUser phoneUser;
    TestUser user;
    ForWorkersApi workersApi;

    @Before
    public void before() throws Exception {
        worker = TestUserHelper.createAndSignInUser(WorkerApiTest.class, true, Role.WORKER);
        researcher = TestUserHelper.createAndSignInUser(WorkerApiTest.class, true, Role.RESEARCHER);
        workersApi = worker.getClient(ForWorkersApi.class);
    }

    @After
    public void deleteWorker() throws Exception {
        if (worker != null) {
            worker.signOutAndDeleteUser();
        }
    }
    @After
    public void deletePhoneUser() throws Exception {
        if (phoneUser != null) {
            phoneUser.signOutAndDeleteUser();
        }
    }
    @After
    public void deleteResearcher() throws Exception {
        if (researcher != null) {
            researcher.signOutAndDeleteUser();
        }
    }
    @After
    public void deleteUser() throws Exception {
        if (user != null) {
            user.signOutAndDeleteUser();
        }
    }
    
    @Test
    public void retrieveUsers() throws Exception {
        user = TestUserHelper.createAndSignInUser(WorkerApiTest.class, true);
        
        AccountSummaryList list = workersApi.getParticipantsInStudy("api", 0, 5, "", null, null, null).execute().body();
        assertTrue(list.getTotal() > 0);
        
        list = workersApi.getParticipantsInStudy("api", 0, 5, worker.getEmail(), null, null, null).execute().body();
        assertEquals(1, list.getItems().size());
        assertEquals(worker.getEmail(), list.getItems().get(0).getEmail());
        
        // Include consent history in this call.
        StudyParticipant participant = workersApi.getParticipantInStudyById("api", user.getSession().getId(), true)
                .execute().body();
        assertEquals(user.getEmail(), participant.getEmail());
        assertNotNull(participant.getHealthCode());
        assertNotNull(participant.getConsentHistories().get("api").get(0));
        
        // get by health code, also verify we do not include consent histories.
        StudyParticipant participant2 = workersApi.getParticipantInStudyByHealthCode("api", participant.getHealthCode(), false)
                .execute().body();
        assertEquals(participant.getId(), participant2.getId());
        assertNull(participant2.getConsentHistories().get("api"));
        
        // get by external Id, also verify we do not include consent histories.
        String externalId = Tests.randomIdentifier(WorkerApiTest.class);
        participant.externalId(externalId);
        researcher.getClient(ParticipantsApi.class).updateParticipant(participant.getId(), participant).execute();
        
        StudyParticipant participant3 = workersApi.getParticipantInStudyByExternalId("api", externalId, false)
                .execute().body();
        assertEquals(participant.getId(), participant3.getId());
        assertNull(participant3.getConsentHistories().get("api"));
    }
    
    @Test
    public void retrieveUsersWithPhone() throws Exception {
        Tests.deletePhoneUser(researcher);

        SignUp signUp = new SignUp().phone(Tests.PHONE).password("P@ssword`1");
        phoneUser = TestUserHelper.createAndSignInUser(WorkerApiTest.class, true, signUp);
        
        AccountSummaryList list = workersApi.getParticipantsInStudy("api", 0, 5, null, "248-6796", null, null).execute().body();
        assertEquals(1, list.getItems().size());
        assertEquals(phoneUser.getPhone().getNumber(), list.getItems().get(0).getPhone().getNumber());
        
        String userId = list.getItems().get(0).getId();
        StudyParticipant participant = workersApi.getParticipantInStudyById("api", userId, false).execute().body();
        
        assertEquals(phoneUser.getPhone().getNumber(), participant.getPhone().getNumber());
        assertNotNull(participant.getHealthCode());
    }
    
}
