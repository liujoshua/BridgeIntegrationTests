package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.sagebionetworks.bridge.sdk.ClientProvider;
import org.sagebionetworks.bridge.sdk.ResearcherClient;
import org.sagebionetworks.bridge.sdk.Roles;
import org.sagebionetworks.bridge.sdk.Session;
import org.sagebionetworks.bridge.sdk.integration.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.sdk.UserClient;
import org.sagebionetworks.bridge.sdk.models.PagedResourceList;
import org.sagebionetworks.bridge.sdk.models.accounts.AccountSummary;
import org.sagebionetworks.bridge.sdk.models.accounts.StudyParticipant;
import org.sagebionetworks.bridge.sdk.models.users.SharingScope;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

@Category(IntegrationSmokeTest.class)
public class SignInTest {

    private TestUser researcher;
    private TestUser user;
    
    @Before
    public void before() {
        researcher = TestUserHelper.createAndSignInUser(SignInTest.class, true, Roles.RESEARCHER);
        user = TestUserHelper.createAndSignInUser(SignInTest.class, true);
    }
    
    @After
    public void after() {
        if (user != null) {
            user.signOutAndDeleteUser();
        }
        if (researcher != null) {
            researcher.signOutAndDeleteUser();
        }
    }

    @Test
    public void canGetDataGroups(){
        Set<String> dataGroups = Sets.newHashSet("sdk-int-1");
        UserClient client = user.getSession().getUserClient();
        
        StudyParticipant participant = new StudyParticipant.Builder()
                .withDataGroups(dataGroups).build();
        client.saveStudyParticipant(participant);
        
        user.getSession().signOut();
        
        Session session = ClientProvider.signIn(user.getSignInCredentials());
        assertEquals(dataGroups, session.getDataGroups());
    }
    
    @Test
    public void createComplexUser() {
        ResearcherClient researcherClient = researcher.getSession().getResearcherClient();
        Map<String,String> map = Maps.newHashMap();
        map.put("phone", "123-345-5768");

        StudyParticipant participant = new StudyParticipant.Builder()
                .withFirstName("First Name")
                .withLastName("Last Name")
                .withExternalId("external ID")
                .withEmail(Tests.makeEmail(SignInTest.class))
                .withPassword("P@ssword`1")
                .withSharingScope(SharingScope.ALL_QUALIFIED_RESEARCHERS)
                .withNotifyByEmail(true)
                .withDataGroups(Sets.newHashSet("group1"))
                .withLanguages(Tests.newLinkedHashSet("en"))
                .withAttributes(map)
                .build();
                
        ClientProvider.signUp(Tests.TEST_KEY, participant);
        
        PagedResourceList<AccountSummary> summaries = researcherClient.getPagedAccountSummaries(0, 10,
                participant.getEmail());
        assertEquals(1, summaries.getItems().size());
        
        AccountSummary summary = summaries.getItems().get(0);
        StudyParticipant retrieved = researcherClient.getStudyParticipant(summary.getId());
        assertEquals("First Name", retrieved.getFirstName());
        assertEquals("Last Name", retrieved.getLastName());
        assertEquals("external ID", retrieved.getExternalId());
        assertEquals(participant.getEmail(), retrieved.getEmail());
        assertEquals(SharingScope.ALL_QUALIFIED_RESEARCHERS, retrieved.getSharingScope());
        assertTrue(retrieved.isNotifyByEmail());
        assertEquals(Sets.newHashSet("group1"), retrieved.getDataGroups());
        assertEquals(Tests.newLinkedHashSet("en"), retrieved.getLanguages());
        assertEquals("123-345-5768", retrieved.getAttributes().get("phone"));
        
        TestUser admin = TestUserHelper.getSignedInAdmin();
        admin.getSession().getAdminClient().deleteUser(retrieved.getId());
    }
}
