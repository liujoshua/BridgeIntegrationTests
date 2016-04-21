package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.joda.time.DateTime;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.sdk.ResearcherClient;
import org.sagebionetworks.bridge.sdk.Roles;
import org.sagebionetworks.bridge.sdk.integration.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.sdk.models.accounts.StudyParticipant;
import org.sagebionetworks.bridge.sdk.models.users.SharingScope;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;

import org.sagebionetworks.bridge.sdk.models.PagedResourceList;
import org.sagebionetworks.bridge.sdk.models.accounts.AccountStatus;
import org.sagebionetworks.bridge.sdk.models.accounts.AccountSummary;

public class ParticipantsTest {

    private TestUser admin;
    private TestUser researcher;
    
    @Before
    public void before() {
        admin = TestUserHelper.getSignedInAdmin();
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
        
        StudyParticipant participant = client.getStudyParticipant(researcher.getSession().getId());
        // Verify that what we know about this test user exists in the record
        assertEquals(researcher.getEmail(), participant.getEmail());
        assertEquals(researcher.getSession().getId(), participant.getId());
        assertTrue(participant.getRoles().contains(Roles.RESEARCHER));
        assertFalse(participant.getConsentHistories().get("api").isEmpty());
    }
    
    @Test
    public void canRetrieveAndPageThroughParticipants() {
        ResearcherClient client = researcher.getSession().getResearcherClient();
        
        PagedResourceList<AccountSummary> summaries = client.getPagedAccountSummaries(0, 10, null);

        // Well we know there's at least two accounts... the admin and the researcher.
        assertEquals(new Integer(0), summaries.getOffsetBy());
        assertEquals(10, summaries.getPageSize());
        assertTrue(summaries.getItems().size() <= summaries.getTotal());
        assertTrue(summaries.getItems().size() > 2);
        
        AccountSummary summary = summaries.getItems().get(0);
        assertNotNull(summary.getFirstName());
        assertNotNull(summary.getLastName());
        assertNotNull(summary.getCreatedOn());
        assertNotNull(summary.getEmail());
        assertNotNull(summary.getStatus());
        assertNotNull(summary.getId());
        
        // Filter to only the researcher
        summaries = client.getPagedAccountSummaries(0, 10, researcher.getEmail());
        assertEquals(1, summaries.getItems().size());
        assertEquals(researcher.getEmail(), summaries.getItems().get(0).getEmail());
        assertEquals(researcher.getEmail(), summaries.getFilters().get("emailFilter"));
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
    public void crudParticipant() {
        String email = Tests.makeEmail(ParticipantsTest.class);
        Map<String,String> attributes = new ImmutableMap.Builder<String,String>().put("phone","123-456-7890").build();
        LinkedHashSet<String> languages = Tests.newLinkedHashSet("en","fr");
        Set<String> dataGroups = Sets.newHashSet("sdk-int-1", "sdk-int-2");
        DateTime createdOn = null;
        
        StudyParticipant participant = new StudyParticipant.Builder()
            .withFirstName("FirstName")
            .withLastName("LastName")
            .withPassword("P@ssword1!")
            .withEmail(email)
            .withExternalId("externalID")
            .withSharingScope(SharingScope.ALL_QUALIFIED_RESEARCHERS)
            .withNotifyByEmail(true)
            .withDataGroups(dataGroups)
            .withLanguages(languages)
            .withStatus(AccountStatus.DISABLED) // should be ignored
            .withAttributes(attributes)
            .build();
        ResearcherClient client = researcher.getSession().getResearcherClient();
        client.createStudyParticipant(participant);
        
        String id = null;
        try {
            // It has been persisted. Right now we don't get the ID back so we have to conduct a 
            // search by email to get this user.
            // Can be found through paged results
            PagedResourceList<AccountSummary> search = client.getPagedAccountSummaries(0, 10, email);
            assertEquals(1, search.getTotal());
            AccountSummary summary = search.getItems().get(0);
            assertEquals("FirstName", summary.getFirstName());
            assertEquals("LastName", summary.getLastName());
            assertEquals(email, summary.getEmail());
            
            id = summary.getId();
            
            StudyParticipant retrieved = client.getStudyParticipant(id);
            assertEquals("FirstName", retrieved.getFirstName());
            assertEquals("LastName", retrieved.getLastName());
            assertEquals(email, retrieved.getEmail());
            assertEquals("externalID", retrieved.getExternalId());
            assertNull(retrieved.getPassword());
            assertEquals(SharingScope.ALL_QUALIFIED_RESEARCHERS, retrieved.getSharingScope());
            assertTrue(retrieved.isNotifyByEmail());
            assertEquals(dataGroups, retrieved.getDataGroups());
            assertEquals(languages, retrieved.getLanguages());
            assertEquals(attributes.get("phone"), retrieved.getAttributes().get("phone"));
            assertEquals(AccountStatus.UNVERIFIED, retrieved.getStatus());
            assertNotNull(retrieved.getCreatedOn());
            assertNotNull(retrieved.getId());
            createdOn = retrieved.getCreatedOn();
            
            // Update the user. Identified by the email address
            Map<String,String> newAttributes = new ImmutableMap.Builder<String,String>().put("phone","206-555-1212").build();
            LinkedHashSet<String> newLanguages = Tests.newLinkedHashSet("de","sw");
            Set<String> newDataGroups = Sets.newHashSet("group1");
            
            StudyParticipant newParticipant = new StudyParticipant.Builder()
                    .withFirstName("FirstName2")
                    .withLastName("LastName2")
                    .withEmail(email)
                    .withExternalId("externalID2")
                    .withSharingScope(SharingScope.NO_SHARING)
                    .withNotifyByEmail(false)
                    .withDataGroups(newDataGroups)
                    .withLanguages(newLanguages)
                    .withAttributes(newAttributes)
                    .withId(id)
                    .withStatus(AccountStatus.ENABLED)
                    .build();
            client.updateStudyParticipant(newParticipant);
            
            // Get it again, verify it has been updated
            retrieved = client.getStudyParticipant(id);
            assertEquals("FirstName2", retrieved.getFirstName());
            assertEquals("LastName2", retrieved.getLastName());
            assertEquals(email, retrieved.getEmail());
            assertEquals("externalID2", retrieved.getExternalId());
            assertNull(retrieved.getPassword());
            assertEquals(SharingScope.NO_SHARING, retrieved.getSharingScope());
            assertFalse(retrieved.isNotifyByEmail());
            assertEquals(newDataGroups, retrieved.getDataGroups());
            assertEquals(newLanguages, retrieved.getLanguages());
            assertEquals(id, retrieved.getId());
            assertEquals(newAttributes.get("phone"), retrieved.getAttributes().get("phone"));
            assertEquals(AccountStatus.ENABLED, retrieved.getStatus()); // user was enabled
            assertEquals(createdOn, retrieved.getCreatedOn()); // hasn't been changed, still exists
        } finally {
            if (id != null) {
                admin.getSession().getAdminClient().deleteUser(id);    
            }
        }
    }
}
