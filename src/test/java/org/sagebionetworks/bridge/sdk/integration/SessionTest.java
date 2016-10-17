package org.sagebionetworks.bridge.sdk.integration;

import static org.sagebionetworks.bridge.sdk.Roles.ADMIN;
import static org.sagebionetworks.bridge.sdk.Roles.RESEARCHER;
import static org.sagebionetworks.bridge.sdk.Roles.TEST_USERS;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.Test;

import org.sagebionetworks.bridge.sdk.ParticipantClient;
import org.sagebionetworks.bridge.sdk.Roles;
import org.sagebionetworks.bridge.sdk.Session;
import org.sagebionetworks.bridge.sdk.integration.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.sdk.models.accounts.AccountStatus;
import org.sagebionetworks.bridge.sdk.models.accounts.SharingScope;
import org.sagebionetworks.bridge.sdk.models.accounts.StudyParticipant;
import org.sagebionetworks.bridge.sdk.rest.model.AbstractStudyParticipant;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

public class SessionTest {
    
    @Test
    public void canGetStudyParticipantWithAllData() {
        TestUser user = TestUserHelper.createAndSignInUser(SessionTest.class, true);
        try {
            // Roles. These should not be set.
            Set<Roles> roles = Sets.newHashSet(ADMIN);
            
            Set<String> dataGroups = Sets.newHashSet("group1");
            
            LinkedHashSet<String> languages = new LinkedHashSet<>();
            languages.add("de");
            languages.add("fr");
            
            StudyParticipant participant = user.getSession().getStudyParticipant();
            org.sagebionetworks.bridge.sdk.rest.model.StudyParticipant updated = new org
                    .sagebionetworks.bridge.sdk.rest.model.StudyParticipant();

                    updated.firstName("TestFirstName")
                           .externalId("an_external_id")
                           .dataGroups(Arrays.asList(dataGroups.toArray(new String[0])))
                           .notifyByEmail(false)

                           .status(AbstractStudyParticipant.StatusEnum.DISABLED)
                           .sharingScope(AbstractStudyParticipant.SharingScopeEnum.SPONSORS_AND_PARTNERS)
                           .roles(Lists.newArrayList(AbstractStudyParticipant.RolesEnum.ADMIN))
                    .lastName("TestLastName")
                    .languages(Arrays.asList(languages.toArray(new String[0])));
            
            user.getSession().getUserClient().saveStudyParticipant(updated);
            
            StudyParticipant asUpdated = user.getSession().getStudyParticipant();
            
            assertEquals("TestFirstName", asUpdated.getFirstName());
            assertEquals("TestLastName", asUpdated.getLastName());
            assertEquals(languages, asUpdated.getLanguages());
            assertEquals(dataGroups, asUpdated.getDataGroups());
            assertEquals("an_external_id", asUpdated.getExternalId());
            assertFalse(asUpdated.isNotifyByEmail());
            assertEquals(SharingScope.SPONSORS_AND_PARTNERS, asUpdated.getSharingScope());
            assertEquals(Sets.newHashSet(TEST_USERS), asUpdated.getRoles());
            assertEquals(AccountStatus.ENABLED, asUpdated.getStatus());
        } finally {
            if (user != null) {
                user.signOutAndDeleteUser();
            }
        }
    }

    @Test
    public void sessionUpdatedWhenResearcherUpdatesOwnAccount() throws Exception {
        StudyParticipant participant = new StudyParticipant.Builder().withRoles(Sets.newHashSet(RESEARCHER)).build();
        TestUser researcher = TestUserHelper.createAndSignInUser(SessionTest.class, false, participant);
        try {
            // Roles. ADMIN should not be set.
            Set<Roles> roles = Sets.newHashSet(RESEARCHER, ADMIN);
            
            Set<String> dataGroups = Sets.newHashSet("group1");
            
            LinkedHashSet<String> languages = new LinkedHashSet<>();
            languages.add("de");
            languages.add("fr");
            
            ParticipantClient participantClient = researcher.getSession().getParticipantClient();
            Session session = researcher.getSession();
            
            participant = participantClient.getStudyParticipant(session.getStudyParticipant().getId());
            StudyParticipant updated = new StudyParticipant.Builder().copyOf(participant)
                    .withFirstName("TestFirstName")
                    .withLastName("TestLastName")
                    .withLanguages(languages)
                    .withDataGroups(dataGroups)
                    .withExternalId("an_external_id")
                    .withNotifyByEmail(false)
                    .withSharingScope(SharingScope.SPONSORS_AND_PARTNERS)
                    .withRoles(roles)
                    .build();
            // don't test disabling the account because, you can disable the account, causing lots of confusion.
            assertNotNull(updated.getId());
            
            participantClient.updateStudyParticipant(updated);
            
            // When you update your own record via this API, you are signed out.
            assertFalse(session.isSignedIn());
            
            researcher.signInAgain();
            session = researcher.getSession();
            
            assertTrue(session.isSignedIn());
            
            // This should have updated the researcher's session StudyParticipant
            StudyParticipant asUpdated = session.getStudyParticipant();
            
            assertEquals("TestFirstName", asUpdated.getFirstName());
            assertEquals("TestLastName", asUpdated.getLastName());
            assertEquals(languages, asUpdated.getLanguages());
            assertEquals(dataGroups, asUpdated.getDataGroups());
            assertEquals("an_external_id", asUpdated.getExternalId());
            assertFalse(asUpdated.isNotifyByEmail());
            assertEquals(SharingScope.SPONSORS_AND_PARTNERS, asUpdated.getSharingScope());
            assertEquals(Sets.newHashSet(RESEARCHER), asUpdated.getRoles());
            assertEquals(AccountStatus.ENABLED, asUpdated.getStatus());
        } finally {
            if (researcher != null) {
                researcher.signOutAndDeleteUser();
            }
        }
    }
}
