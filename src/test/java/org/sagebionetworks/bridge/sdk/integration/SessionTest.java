package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import org.sagebionetworks.bridge.sdk.integration.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.rest.api.ParticipantsApi;
import org.sagebionetworks.bridge.rest.model.AccountStatus;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.SharingScope;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.rest.model.UserSessionInfo;

import com.google.common.collect.Lists;

// This test has gotten less useful since we no longer manipulate the session in the client 
// api classes. Still I've rewritten it to examine updates on the server.
public class SessionTest {
    
    @Test
    public void canGetStudyParticipantWithAllData() throws Exception {
        TestUser user = TestUserHelper.createAndSignInUser(SessionTest.class, true);
        try {
            List<Role> roles = Lists.newArrayList(Role.ADMIN);
            List<String> dataGroups = Lists.newArrayList("group1");
            List<String> languages = Lists.newArrayList("de");
            
            ForConsentedUsersApi usersApi = user.getClient(ForConsentedUsersApi.class);

            StudyParticipant updated = usersApi.getUsersParticipantRecord().execute().body();
            updated.setFirstName("TestFirstName");
            updated.setExternalId("an_external_id");
            updated.setDataGroups(dataGroups);
            updated.setNotifyByEmail(false);
            updated.setStatus(AccountStatus.DISABLED);
            updated.setSharingScope(SharingScope.SPONSORS_AND_PARTNERS);
            updated.setRoles(roles);
            updated.setLastName("TestLastName");
            updated.setLanguages(languages);
            
            usersApi.updateUsersParticipantRecord(updated).execute();
            
            StudyParticipant asUpdated = usersApi.getUsersParticipantRecord().execute().body();
            
            assertEquals("TestFirstName", asUpdated.getFirstName());
            assertEquals("TestLastName", asUpdated.getLastName());
            assertEquals(languages, asUpdated.getLanguages());
            assertEquals(dataGroups, asUpdated.getDataGroups());
            assertEquals("an_external_id", asUpdated.getExternalId());
            assertFalse(asUpdated.getNotifyByEmail());
            assertEquals(SharingScope.SPONSORS_AND_PARTNERS, asUpdated.getSharingScope());
            assertTrue(asUpdated.getRoles().isEmpty()); // can't update the role
            assertEquals(AccountStatus.ENABLED, asUpdated.getStatus());
        } finally {
            if (user != null) {
                user.signOutAndDeleteUser();
            }
        }
    }

    @Test
    public void sessionUpdatedWhenResearcherUpdatesOwnAccount() throws Exception {
        TestUser researcher = TestUserHelper.createAndSignInUser(SessionTest.class, false, Role.RESEARCHER);
        try {
            // Roles. ADMIN should not be set.
            List<Role> roles = Lists.newArrayList(Role.RESEARCHER, Role.ADMIN);
            List<String> dataGroups = Lists.newArrayList("group1");
            List<String> languages = Lists.newArrayList("de", "fr");

            ParticipantsApi participantsApi = researcher.getClient(ParticipantsApi.class);

            StudyParticipant participant = participantsApi.getParticipantById(researcher.getSession().getId(), false).execute().body();
            participant.setFirstName("TestFirstName");
            participant.setLastName("TestLastName");
            participant.setLanguages(languages);
            participant.setDataGroups(dataGroups);
            participant.setExternalId("an_external_id");
            participant.setNotifyByEmail(false);
            participant.setSharingScope(SharingScope.SPONSORS_AND_PARTNERS);
            participant.setRoles(roles);

            participantsApi.updateParticipant(participant.getId(), participant).execute();
            
            researcher.signOut();
            researcher.signInAgain();
            UserSessionInfo session = researcher.getSession();
            
            // Session should be updated with this information. 
            assertTrue(session.getAuthenticated());
            assertEquals("TestFirstName", session.getFirstName());
            assertEquals("TestLastName", session.getLastName());
            assertEquals(languages, session.getLanguages());
            assertEquals(dataGroups, session.getDataGroups());
            assertEquals("an_external_id", session.getExternalId());
            assertFalse(session.getNotifyByEmail());
            assertEquals(SharingScope.SPONSORS_AND_PARTNERS, session.getSharingScope());
            assertEquals(Lists.newArrayList(Role.RESEARCHER), session.getRoles());
            assertEquals(AccountStatus.ENABLED, session.getStatus());
        } finally {
            if (researcher != null) {
                researcher.signOutAndDeleteUser();
            }
        }
    }
}
