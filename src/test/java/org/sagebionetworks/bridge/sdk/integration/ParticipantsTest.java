t :qpackage org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
<<<<<<< HEAD
import static org.junit.Assert.assertFalse;
=======
import static org.junit.Assert.assertNotNull;
>>>>>>> 3f0e6c85c5ce858eab28df2795bf28d0fa5ff56a
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.sdk.ResearcherClient;
import org.sagebionetworks.bridge.sdk.Roles;
import org.sagebionetworks.bridge.sdk.integration.TestUserHelper.TestUser;
<<<<<<< HEAD
import org.sagebionetworks.bridge.sdk.models.accounts.StudyParticipant;

public class ParticipantsTest {
    
=======
import org.sagebionetworks.bridge.sdk.models.PagedResourceList;
import org.sagebionetworks.bridge.sdk.models.accounts.AccountSummary;

public class ParticipantsTest {

>>>>>>> 3f0e6c85c5ce858eab28df2795bf28d0fa5ff56a
    private TestUser researcher;
    
    @Before
    public void before() {
<<<<<<< HEAD
        researcher = TestUserHelper.createAndSignInUser(ParticipantsTest.class, true, Roles.RESEARCHER);
=======
        researcher = TestUserHelper.createAndSignInUser(ParticipantsTest.class, false, Roles.RESEARCHER);
>>>>>>> 3f0e6c85c5ce858eab28df2795bf28d0fa5ff56a
    }
    
    @After
    public void after() {
<<<<<<< HEAD
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
=======
        researcher.signOutAndDeleteUser();
    }
    
    @Test
    public void canRetrieveAndPageThroughParticipants() {
        ResearcherClient client = researcher.getSession().getResearcherClient();
        
        PagedResourceList<AccountSummary> summaries = client.getPagedAccountSummaries(0, 10);

        // Well we know there's at least two accounts... the admin and the researcher.
        assertEquals(0, summaries.getOffsetBy());
        assertEquals(10, summaries.getPageSize());
        assertTrue(summaries.getItems().size() <= summaries.getTotal());
        assertTrue(summaries.getItems().size() > 2);
        
        AccountSummary summary = summaries.getItems().get(0);
        assertNotNull(summary.getFirstName());
        assertNotNull(summary.getLastName());
        assertNotNull(summary.getEmail());
        assertNotNull(summary.getStatus());
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void cannotSetBadOffset() {
        ResearcherClient client = researcher.getSession().getResearcherClient();
        client.getPagedAccountSummaries(-1, 10);
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void cannotSetBadPageSize() {
        ResearcherClient client = researcher.getSession().getResearcherClient();
        client.getPagedAccountSummaries(0, 4);
    }
    
>>>>>>> 3f0e6c85c5ce858eab28df2795bf28d0fa5ff56a
}
