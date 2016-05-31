package org.sagebionetworks.bridge.sdk.integration;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.HashSet;
import java.util.Set;

import org.sagebionetworks.bridge.sdk.AdminClient;
import org.sagebionetworks.bridge.sdk.ClientProvider;
import org.sagebionetworks.bridge.sdk.Config;
import org.sagebionetworks.bridge.sdk.Roles;
import org.sagebionetworks.bridge.sdk.Session;
import org.sagebionetworks.bridge.sdk.exceptions.ConsentRequiredException;
import org.sagebionetworks.bridge.sdk.models.accounts.StudyParticipant;
import org.sagebionetworks.bridge.sdk.models.subpopulations.SubpopulationGuid;
import org.sagebionetworks.bridge.sdk.models.users.SignInCredentials;

import com.google.common.collect.Sets;

public class TestUserHelper {

    public static final String PASSWORD = "P4ssword";
    
    @SuppressWarnings("Convert2Diamond")
    public static class TestUser {
        private final AdminClient adminClient;
        private final Session userSession;
        private final String email;
        private final String password;
        private final Set<Roles> roles;

        public TestUser(AdminClient client, Session userSession, String email, String password,
                Set<Roles> roleList) {

            this.adminClient = client;
            this.userSession = userSession;
            this.email = email;
            this.password = password;
            this.roles = (roleList == null) ? new HashSet<Roles>() : roleList;
            roles.add(Roles.TEST_USERS);
        }
        public Session getSession() {
            return userSession;
        }
        public String getEmail() {
            return email;
        }
        public String getPassword() {
            return password;
        }
        public Set<Roles> getRoles() {
            return roles;
        }
        public SubpopulationGuid getDefaultSubpopulation() {
            return new SubpopulationGuid(Tests.TEST_KEY);
        }
        public void signOutAndDeleteUser() {
            userSession.signOut();
            adminClient.deleteUser(userSession.getId());
        }
        public SignInCredentials getSignInCredentials() {
            return new SignInCredentials(Tests.TEST_KEY, email, PASSWORD);
        }
    }

    public static TestUser getSignedInAdmin() {
        Config config = ClientProvider.getConfig();
        Session session = ClientProvider.signIn(config.getAdminCredentials());
        AdminClient adminClient = session.getAdminClient();

        return new TestUserHelper.TestUser(adminClient, session, "", "", Sets.newHashSet(Roles.ADMIN));
    }
    
    public static TestUser createAndSignInUser(Class<?> cls, boolean consent, Roles... roles) {
        checkNotNull(cls);

        ClientProvider.setClientInfo(Tests.TEST_CLIENT_INFO);

        Config config = ClientProvider.getConfig();
        Session session = ClientProvider.signIn(config.getAdminCredentials());
        AdminClient adminClient = session.getAdminClient();

        Set<Roles> rolesList = (roles == null) ? Sets.<Roles>newHashSet() : Sets.newHashSet(roles);
        rolesList.add(Roles.TEST_USERS);

        // For email address, we don't want consent emails to bounce or SES will get mad at us. All test user email
        // addresses should be in the form bridge-testing+[semi-unique token]@sagebase.org. This directs all test
        // email to bridge-testing@sagebase.org.
        String emailAddress = Tests.makeEmail(cls);

        StudyParticipant participant = new StudyParticipant.Builder()
                .withEmail(emailAddress).withPassword(PASSWORD).withRoles(rolesList).build();
        
        // We don't need the ID here, we get it because we always retrieve a session. The iOS integration tests
        // use this however.
        adminClient.createUser(participant, consent);

        Session userSession = null;
        try {
            try {
                SignInCredentials signIn = new SignInCredentials(Tests.TEST_KEY, emailAddress, PASSWORD);
                userSession = ClientProvider.signIn(signIn);
            } catch (ConsentRequiredException e) {
                userSession = e.getSession();
                if (consent) {
                    // If there's no consent but we're expecting one, that's an error.
                    throw e;
                }
            }
            return new TestUserHelper.TestUser(adminClient, userSession, participant.getEmail(), participant.getPassword(),
                    rolesList);
        } catch (RuntimeException ex) {
            // Clean up the account, so we don't end up with a bunch of leftover accounts.
            if (userSession != null) {
                adminClient.deleteUser(userSession.getId());    
            }
            throw ex;
        }
    }
}
