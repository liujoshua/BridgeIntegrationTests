package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.sagebionetworks.bridge.sdk.Roles;
import org.sagebionetworks.bridge.sdk.integration.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.sdk.UserClient;
import org.sagebionetworks.bridge.sdk.models.accounts.StudyParticipant;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

/**
 * Test of the participant APIs that act on the currently authenticated user, which have replaced the 
 * user profile APIs as well as individual calls to update things like dataGroups, externalId, or 
 * sharing settings.
 *
 */
@Category(IntegrationSmokeTest.class)
public class UserParticipantTest {

    private TestUser developer;

    @Before
    public void before() {
        developer = TestUserHelper.createAndSignInUser(UserParticipantTest.class, true, Roles.DEVELOPER);
    }

    @After
    public void after() {
        developer.signOutAndDeleteUser();
    }

    @Test
    @Ignore
    public void canUpdateProfile() throws Exception {
        UserClient client = developer.getSession().getUserClient();

        Map<String,String> attributes = Maps.newHashMap();
        attributes.put("can_be_recontacted", "true");
        
        StudyParticipant participant = new StudyParticipant.Builder()
                .withFirstName("Davey")
                .withLastName("Crockett")
                .withAttributes(attributes)
                .build();

        client.saveStudyParticipant(participant);

        participant = client.getStudyParticipant();

        assertEquals(developer.getEmail(), participant.getEmail());
        assertEquals("First name updated", "Davey", participant.getFirstName());
        assertEquals("Last name updated", "Crockett", participant.getLastName());
        assertEquals("Attribute set", "true", participant.getAttributes().get("can_be_recontacted"));
    }
    
    @Test
    @Ignore
    public void canAddExternalIdentifier() throws Exception {
        final UserClient client = developer.getSession().getUserClient();
        
        StudyParticipant participant = client.getStudyParticipant();
        StudyParticipant updated = new StudyParticipant.Builder().copyOf(participant)
                .withExternalId("ABC-123-XYZ").build();
        
        client.saveStudyParticipant(updated);
        
        participant = client.getStudyParticipant();
        assertEquals(developer.getEmail(), participant.getEmail());
        assertEquals("ABC-123-XYZ", participant.getExternalId());
    }
    
    @Test
    public void canUpdateDataGroups() throws Exception {
        Set<String> dataGroups = Sets.newHashSet("sdk-int-1", "sdk-int-2");
        
        UserClient client = developer.getSession().getUserClient();
        
        StudyParticipant participant = new StudyParticipant.Builder().withDataGroups(dataGroups).build();
        
        client.saveStudyParticipant(participant);
        
        participant = client.getStudyParticipant();
        assertEquals(dataGroups, participant.getDataGroups());
        
        participant = new StudyParticipant.Builder().withDataGroups(Sets.<String>newHashSet()).build();
        client.saveStudyParticipant(participant);
        
        participant = client.getStudyParticipant();
        assertTrue(participant.getDataGroups().isEmpty());
    }

}
