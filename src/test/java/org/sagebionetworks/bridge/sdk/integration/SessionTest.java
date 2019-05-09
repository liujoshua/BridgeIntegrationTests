package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.joda.time.DateTime;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.rest.api.ParticipantsApi;
import org.sagebionetworks.bridge.rest.model.AccountStatus;
import org.sagebionetworks.bridge.rest.model.ConsentStatus;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.SharingScope;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.rest.model.UserSessionInfo;
import org.sagebionetworks.bridge.rest.model.Withdrawal;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.util.IntegTestUtils;

import com.google.common.collect.Lists;

public class SessionTest {
    @Test
    public void verifySession() throws Exception {
        TestUser user = TestUserHelper.createAndSignInUser(SessionTest.class, true);
        try {
            DateTime startOfTest = DateTime.now();

            UserSessionInfo session = user.getSession();
            assertNotNull(session.getId());
            assertEquals(SharingScope.NO_SHARING, session.getSharingScope());
            assertTrue(session.getCreatedOn().isAfter(startOfTest.minusHours(1)));
            assertEquals(AccountStatus.ENABLED, session.getStatus());
            assertEquals("en", session.getLanguages().get(0));
            assertEquals(1, session.getLanguages().size());
            assertTrue(session.isAuthenticated());
            assertNotNull(session.getSessionToken());
            assertNotNull(session.getEmail());
            assertEquals(user.getEmail(), session.getEmail());
            assertTrue(session.isConsented());

            ConsentStatus status = session.getConsentStatuses().get(IntegTestUtils.STUDY_ID);
            assertEquals("Default Consent Group", status.getName());
            assertEquals(IntegTestUtils.STUDY_ID, status.getSubpopulationGuid());
            assertTrue(status.isRequired());
            assertTrue(status.isConsented());
            assertTrue(status.isSignedMostRecentConsent());
            assertTrue(status.getSignedOn().isAfter(startOfTest.minusHours(1)));

            ForConsentedUsersApi usersApi = user.getClient(ForConsentedUsersApi.class);

            Withdrawal withdrawal = new Withdrawal().reason("No longer want to be a test subject");
            UserSessionInfo session2 = usersApi.withdrawConsentFromSubpopulation(IntegTestUtils.STUDY_ID, withdrawal).execute().body();

            ConsentStatus status2 = session2.getConsentStatuses().get(IntegTestUtils.STUDY_ID);
            assertEquals("Default Consent Group", status2.getName());
            assertEquals(IntegTestUtils.STUDY_ID, status2.getSubpopulationGuid());
            assertTrue(status2.isRequired());
            assertFalse(status2.isConsented());
            assertFalse(status2.isSignedMostRecentConsent());
            assertNull(status2.getSignedOn());
        } finally {
            if (user != null) {
                user.signOutAndDeleteUser();
            }
        }
    }
    
    @Test
    public void canGetStudyParticipantWithAllData() throws Exception {
        TestUser user = TestUserHelper.createAndSignInUser(SessionTest.class, true);
        try {
            List<Role> roles = Lists.newArrayList(Role.ADMIN);
            List<String> dataGroups = Lists.newArrayList("group1");
            List<String> languages = Lists.newArrayList("de");
            
            ForConsentedUsersApi usersApi = user.getClient(ForConsentedUsersApi.class);

            StudyParticipant updated = usersApi.getUsersParticipantRecord(false).execute().body();
            updated.setFirstName("TestFirstName");
            updated.setDataGroups(dataGroups);
            updated.setNotifyByEmail(false);
            updated.setStatus(AccountStatus.DISABLED);
            updated.setSharingScope(SharingScope.SPONSORS_AND_PARTNERS);
            updated.setRoles(roles);
            updated.setLastName("TestLastName");
            updated.setLanguages(languages);
            
            usersApi.updateUsersParticipantRecord(updated).execute();
            
            StudyParticipant asUpdated = usersApi.getUsersParticipantRecord(false).execute().body();
            
            assertEquals("TestFirstName", asUpdated.getFirstName());
            assertEquals("TestLastName", asUpdated.getLastName());
            assertEquals(languages, asUpdated.getLanguages());
            assertEquals(dataGroups, asUpdated.getDataGroups());
            assertFalse(asUpdated.isNotifyByEmail());
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
            participant.setNotifyByEmail(false);
            participant.setSharingScope(SharingScope.SPONSORS_AND_PARTNERS);
            participant.setRoles(roles);

            participantsApi.updateParticipant(participant.getId(), participant).execute();
            
            researcher.signOut();
            researcher.signInAgain();
            UserSessionInfo session = researcher.getSession();
            
            // Session should be updated with this information. 
            assertTrue(session.isAuthenticated());
            assertEquals("TestFirstName", session.getFirstName());
            assertEquals("TestLastName", session.getLastName());
            assertEquals(languages, session.getLanguages());
            assertEquals(dataGroups, session.getDataGroups());
            assertFalse(session.isNotifyByEmail());
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
