package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.sagebionetworks.bridge.sdk.integration.Tests.assertListsEqualIgnoringOrder;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.sagebionetworks.bridge.sdk.integration.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.rest.api.ParticipantsApi;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;

import java.util.List;

/**
 * Test of the participant APIs that act on the currently authenticated user, which have replaced the 
 * user profile APIs as well as individual calls to update things like dataGroups, externalId, or 
 * sharing settings.
 *
 */
@Category(IntegrationSmokeTest.class)
public class UserParticipantTest {

    private static TestUser developer;

    @BeforeClass
    public static void before() throws Exception {
        developer = TestUserHelper.createAndSignInUser(UserParticipantTest.class, true, Role.DEVELOPER);
    }

    @AfterClass
    public static void after() throws Exception {
        if (developer != null) {
            developer.signOutAndDeleteUser();    
        }
    }

    @Test
    public void canUpdateProfile() throws Exception {
        TestUser user = TestUserHelper.createAndSignInUser(UserParticipantTest.class, true);
        try {
            ParticipantsApi participantsApi = user.getClient(ParticipantsApi.class);

            StudyParticipant participant = participantsApi.getUsersParticipantRecord().execute().body();

            // This should be true by default, once a participant is created:
            assertTrue(participant.getNotifyByEmail());
            
            participant.setFirstName("Davey");
            participant.setLastName("Crockett");
            participant.setAttributes(new ImmutableMap.Builder<String,String>().put("can_be_recontacted","true").build());
            participant.setNotifyByEmail(null); // this should have no effect
            participantsApi.updateUsersParticipantRecord(participant).execute().body();

            participant = participantsApi.getUsersParticipantRecord().execute().body();
            assertEquals("Davey", participant.getFirstName());
            assertEquals("Crockett", participant.getLastName());
            assertEquals("true", participant.getAttributes().get("can_be_recontacted"));
            // This should not have been changed as the result of updating other fields
            assertTrue(participant.getNotifyByEmail());
            
            // Now update only some of the record but verify the map is still there
            participant = participantsApi.getUsersParticipantRecord().execute().body();
            participant.setFirstName("Davey2");
            participant.setLastName("Crockett2");
            participant.setNotifyByEmail(false);
            participantsApi.updateUsersParticipantRecord(participant).execute().body();
            
            participant = participantsApi.getUsersParticipantRecord().execute().body();
            assertEquals("First name updated", "Davey2", participant.getFirstName());
            assertEquals("Last name updated", "Crockett2", participant.getLastName());
            assertEquals("true", participant.getAttributes().get("can_be_recontacted"));
            assertFalse(participant.getNotifyByEmail());
        } finally {
            user.signOutAndDeleteUser();
        }
    }

    @Test
    public void canAddButNotChangeExternalIdentifier() throws Exception {
        ForConsentedUsersApi usersApi = developer.getClient(ForConsentedUsersApi.class);

        StudyParticipant participant = usersApi.getUsersParticipantRecord().execute().body();
        participant.setExternalId("ABC-123-XYZ");

        usersApi.updateUsersParticipantRecord(participant).execute();

        participant = usersApi.getUsersParticipantRecord().execute().body();
        assertEquals(developer.getEmail(), participant.getEmail());
        assertEquals("ABC-123-XYZ", participant.getExternalId());
        
        participant.setExternalId("ThisWillNotWork");
        usersApi.updateUsersParticipantRecord(participant).execute();

        participant = usersApi.getUsersParticipantRecord().execute().body();
        assertEquals("ABC-123-XYZ", participant.getExternalId());
    }
    
    @Test
    public void canUpdateDataGroups() throws Exception {
        List<String> dataGroups = Lists.newArrayList("sdk-int-1", "sdk-int-2");

        ForConsentedUsersApi usersApi = developer.getClient(ForConsentedUsersApi.class);

        StudyParticipant participant = new StudyParticipant();
        participant.setDataGroups(dataGroups);
        usersApi.updateUsersParticipantRecord(participant).execute();

        developer.signOut();
        developer.signInAgain();
        
        participant = usersApi.getUsersParticipantRecord().execute().body();
        assertListsEqualIgnoringOrder(dataGroups, participant.getDataGroups());

        // now clear the values, it should be possible to remove them.
        participant.setDataGroups(Lists.newArrayList());
        usersApi.updateUsersParticipantRecord(participant).execute();
        
        developer.signOut();
        developer.signInAgain();

        participant = usersApi.getUsersParticipantRecord().execute().body();
        assertTrue(participant.getDataGroups().isEmpty());
    }

}
