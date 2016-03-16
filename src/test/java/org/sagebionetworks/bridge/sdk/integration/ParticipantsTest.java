package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.sdk.ResearcherClient;
import org.sagebionetworks.bridge.sdk.Roles;
import org.sagebionetworks.bridge.sdk.integration.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.sdk.models.accounts.StudyParticipant;

public class ParticipantsTest {
    
    private TestUser researcher;
    
    @Before
    public void before() {
        researcher = TestUserHelper.createAndSignInUser(ParticipantsTest.class, true, Roles.RESEARCHER);
    }
    
    @After
    public void after() {
        if (researcher != null) {
            researcher.signOutAndDeleteUser();
        }
    }

    @Test
    public void retrieveParticipant() {
        ResearcherClient client = researcher.getSession().getResearcherClient();
        
        StudyParticipant participant = client.getStudyParticipant(researcher.getEmail());
        // Verify that what we know about this test user exists in the record
        assertEquals(researcher.getEmail(), participant.getEmail());
        assertTrue(participant.getRoles().contains(Roles.RESEARCHER));
        assertFalse(participant.getConsentHistories().get("api").isEmpty());
    }
}
