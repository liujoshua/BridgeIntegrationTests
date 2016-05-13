package org.sagebionetworks.bridge.sdk.integration;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

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
        private final String email;
        private final String userId;
        private Session userSession;

        public TestUser(AdminClient client, Session userSession) {
            this.adminClient = client;
            this.userSession = userSession;
            this.email = userSession.getStudyParticipant().getEmail();
            this.userId = userSession.getStudyParticipant().getId();
        }
        public Session getSession() {
            return userSession;
        }
        public String getEmail() {
            return email;
        }
        public String getPassword() {
            return PASSWORD;
        }
        public Set<Roles> getRoles() {
            return userSession.getStudyParticipant().getRoles();
        }
        public SubpopulationGuid getDefaultSubpopulation() {
            return new SubpopulationGuid(Tests.TEST_KEY);
        }
        public void signInAgain() throws Exception {
            try {
                userSession = ClientProvider.signIn( getSignInCredentials() );
            } catch (ConsentRequiredException e) {
                userSession = e.getSession();
            }
        }
        public void signOutAndDeleteUser() {
            userSession.signOut();
            adminClient.deleteUser(userId);
        }
        public SignInCredentials getSignInCredentials() {
            return new SignInCredentials(Tests.TEST_KEY, email, PASSWORD);
        }
    }

    public static TestUser getSignedInAdmin() {
        Config config = ClientProvider.getConfig();
        Session adminSession = ClientProvider.signIn(config.getAdminCredentials());
        AdminClient adminClient = adminSession.getAdminClient();
        
        return new TestUserHelper.TestUser(adminClient, adminSession);
    }
    
    public static TestUser createAndSignInUser(Class<?> cls, boolean consent, Roles... roles) {
        StudyParticipant.Builder builder = new StudyParticipant.Builder();
        if (roles != null) {
            Set<Roles> rolesList = Arrays.stream(roles).collect(Collectors.toSet());
            builder.withRoles(rolesList);
        }
        return createAndSignInUser(cls, consent, builder.build());
    }
    
    public static TestUser createAndSignInUser(Class<?> cls, boolean consent, StudyParticipant participant) {
        checkNotNull(cls);

        ClientProvider.setClientInfo(Tests.TEST_CLIENT_INFO);

        Config config = ClientProvider.getConfig();
        Session adminSession = ClientProvider.signIn(config.getAdminCredentials());
        AdminClient adminClient = adminSession.getAdminClient();

        Set<Roles> rolesList = Sets.newHashSet(Roles.TEST_USERS);
        if (participant != null && participant.getRoles() != null) {
            rolesList.addAll(participant.getRoles());
        }

        // For email address, we don't want consent emails to bounce or SES will get mad at us. All test user email
        // addresses should be in the form bridge-testing+[semi-unique token]@sagebase.org. This directs all test
        // email to bridge-testing@sagebase.org.
        String emailAddress = Tests.makeEmail(cls);

        StudyParticipant.Builder builder = new StudyParticipant.Builder();
        if (participant != null) {
            builder.copyOf(participant);
        }
        builder.withRoles(rolesList);
        builder.withEmail(emailAddress);
        builder.withPassword(PASSWORD);
        
        // We don't need the ID here, we get it because we always retrieve a session. The iOS integration tests
        // use this however.
        adminClient.createUser(builder.build(), consent);

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
            return new TestUserHelper.TestUser(adminClient, userSession);
        } catch (RuntimeException ex) {
            // Clean up the account, so we don't end up with a bunch of leftover accounts.
            if (userSession != null && userSession.getStudyParticipant() != null) {
                adminClient.deleteUser(userSession.getStudyParticipant().getId());    
            }
            throw ex;
        }
    }
}
