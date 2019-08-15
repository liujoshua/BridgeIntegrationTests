package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.sagebionetworks.bridge.sdk.integration.Tests.SUBSTUDY_ID_1;
import static org.sagebionetworks.bridge.sdk.integration.Tests.SUBSTUDY_ID_2;
import static org.sagebionetworks.bridge.sdk.integration.Tests.assertListsEqualIgnoringOrder;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.sagebionetworks.bridge.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.rest.api.ParticipantsApi;
import org.sagebionetworks.bridge.rest.model.ExternalIdentifier;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;

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

            StudyParticipant participant = participantsApi.getUsersParticipantRecord(false).execute().body();

            // This should be true by default, once a participant is created:
            assertTrue(participant.isNotifyByEmail());
            
            participant.setFirstName("Davey");
            participant.setLastName("Crockett");
            participant.setAttributes(new ImmutableMap.Builder<String,String>().put("can_be_recontacted","true").build());
            participant.setNotifyByEmail(null); // this should have no effect
            participantsApi.updateUsersParticipantRecord(participant).execute().body();

            participant = participantsApi.getUsersParticipantRecord(false).execute().body();
            assertEquals("Davey", participant.getFirstName());
            assertEquals("Crockett", participant.getLastName());
            assertEquals("true", participant.getAttributes().get("can_be_recontacted"));
            // This should not have been changed as the result of updating other fields
            assertTrue(participant.isNotifyByEmail());
            
            // Now update only some of the record but verify the map is still there
            participant = participantsApi.getUsersParticipantRecord(false).execute().body();
            participant.setFirstName("Davey2");
            participant.setLastName("Crockett2");
            participant.setNotifyByEmail(false);
            participantsApi.updateUsersParticipantRecord(participant).execute().body();
            
            participant = participantsApi.getUsersParticipantRecord(false).execute().body();
            assertEquals("First name updated", "Davey2", participant.getFirstName());
            assertEquals("Last name updated", "Crockett2", participant.getLastName());
            assertEquals("true", participant.getAttributes().get("can_be_recontacted"));
            assertFalse(participant.isNotifyByEmail());
        } finally {
            user.signOutAndDeleteUser();
        }
    }

    @Test
    public void canAddButNotChangeExternalIdentifier() throws Exception {
        ExternalIdentifier externalId1 = Tests.createExternalId(UserParticipantTest.class, developer, SUBSTUDY_ID_1);
        ExternalIdentifier externalId2 = Tests.createExternalId(UserParticipantTest.class, developer, SUBSTUDY_ID_2);
        
        TestUser user = TestUserHelper.createAndSignInUser(UserParticipantTest.class, true);
        try {
            ForConsentedUsersApi usersApi = user.getClient(ForConsentedUsersApi.class);
            StudyParticipant participant = usersApi.getUsersParticipantRecord(false).execute().body();
            participant.setExternalId(externalId1.getIdentifier());

            usersApi.updateUsersParticipantRecord(participant).execute();

            participant = usersApi.getUsersParticipantRecord(false).execute().body();
            assertEquals(user.getEmail(), participant.getEmail());
            assertTrue(participant.getExternalIds().values().contains(externalId1.getIdentifier()));
            
            participant.setExternalId(externalId2.getIdentifier());
            usersApi.updateUsersParticipantRecord(participant).execute();

            participant = usersApi.getUsersParticipantRecord(false).execute().body();
            assertTrue(participant.getExternalIds().values().contains(externalId1.getIdentifier()));
        } finally {
            Tests.deleteExternalId(externalId1);
            Tests.deleteExternalId(externalId2);
            user.signOutAndDeleteUser();
        }
    }
    
    @Test
    public void canUpdateDataGroups() throws Exception {
        List<String> dataGroups = ImmutableList.of("sdk-int-1", "sdk-int-2");

        ForConsentedUsersApi usersApi = developer.getClient(ForConsentedUsersApi.class);

        StudyParticipant participant = new StudyParticipant();
        participant.setDataGroups(dataGroups);
        usersApi.updateUsersParticipantRecord(participant).execute();

        developer.signOut();
        developer.signInAgain();
        
        participant = usersApi.getUsersParticipantRecord(false).execute().body();
        assertListsEqualIgnoringOrder(dataGroups, participant.getDataGroups());

        // now clear the values, it should be possible to remove them.
        participant.setDataGroups(ImmutableList.of());
        usersApi.updateUsersParticipantRecord(participant).execute();
        
        developer.signOut();
        developer.signInAgain();

        participant = usersApi.getUsersParticipantRecord(false).execute().body();
        assertTrue(participant.getDataGroups().isEmpty());
    }

}
