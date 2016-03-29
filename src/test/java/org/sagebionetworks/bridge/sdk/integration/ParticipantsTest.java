package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashSet;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.sdk.ResearcherClient;
import org.sagebionetworks.bridge.sdk.Roles;
import org.sagebionetworks.bridge.sdk.integration.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.sdk.models.accounts.StudyParticipant;
import org.sagebionetworks.bridge.sdk.models.users.SharingScope;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import org.sagebionetworks.bridge.sdk.models.PagedResourceList;
import org.sagebionetworks.bridge.sdk.models.accounts.AccountSummary;

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
    
    @Test
    public void canRetrieveAndPageThroughParticipants() {
        ResearcherClient client = researcher.getSession().getResearcherClient();
        
        PagedResourceList<AccountSummary> summaries = client.getPagedAccountSummaries(0, 10, null);

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
        
        // Filter to only the researcher
        summaries = client.getPagedAccountSummaries(0, 10, researcher.getEmail());
        assertEquals(1, summaries.getItems().size());
        assertEquals(researcher.getEmail(), summaries.getItems().get(0).getEmail());
        assertEquals(researcher.getEmail(), summaries.getEmailFilter());
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void cannotSetBadOffset() {
        ResearcherClient client = researcher.getSession().getResearcherClient();
        client.getPagedAccountSummaries(-1, 10, null);
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void cannotSetBadPageSize() {
        ResearcherClient client = researcher.getSession().getResearcherClient();
        client.getPagedAccountSummaries(0, 4, null);
    }
    
    @Test
    public void getAndUpdateParticipant() throws Exception {
        ResearcherClient client = researcher.getSession().getResearcherClient();
        
        Map<String,String> attributes = Maps.newHashMap();
        attributes.put("phone", "123-456-7890");
        
        LinkedHashSet<String> languages = new LinkedHashSet<>();
        languages.add("en");
        languages.add("fr");
        
        // Let's update the researcher
        StudyParticipant participant = new StudyParticipant.Builder()
                .withFirstName("First name")
                .withLastName("Last name")
                .withExternalId("external ID")
                .withSharingScope(SharingScope.ALL_QUALIFIED_RESEARCHERS)
                .withNotifyByEmail(true)
                .withDataGroups(Sets.newHashSet("group1"))
                .withLanguages(languages)
                .withAttributes(attributes)
                .build();
        
        client.updateStudyParticipant(researcher.getEmail(), participant);
        
        StudyParticipant updated = client.getStudyParticipant(researcher.getEmail());
        assertEquals("First name", updated.getFirstName());
        assertEquals("Last name", updated.getLastName());
        assertEquals("external ID", updated.getExternalId());
        assertEquals(SharingScope.ALL_QUALIFIED_RESEARCHERS, updated.getSharingScope());
        assertEquals(true, updated.isNotifyByEmail());
        assertEquals(Sets.newHashSet("group1"), updated.getDataGroups());
        assertEquals(languages, updated.getLanguages());
        assertEquals(attributes.get("phone"), updated.getAttributes().get("phone"));
    }
}
