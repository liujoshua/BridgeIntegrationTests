package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertNotNull;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sagebionetworks.bridge.sdk.AdminClient;
import org.sagebionetworks.bridge.sdk.ClientProvider;
import org.sagebionetworks.bridge.sdk.Config;
import org.sagebionetworks.bridge.sdk.ParticipantClient;
import org.sagebionetworks.bridge.sdk.Roles;
import org.sagebionetworks.bridge.sdk.Session;
import org.sagebionetworks.bridge.sdk.integration.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.sdk.models.accounts.StudyParticipant;

public class UserManagementTest {

    private Session adminSession;
    private AdminClient adminClient;

    private TestUser researcher;
    private ParticipantClient participantClient;

    @Before
    public void before() {
        Config config = ClientProvider.getConfig();
        adminSession = ClientProvider.signIn(config.getAdminCredentials());
        adminClient = adminSession.getAdminClient();

        researcher = TestUserHelper.createAndSignInUser(UserManagementTest.class, true, Roles.RESEARCHER);
        participantClient = researcher.getSession().getParticipantClient();
    }

    @After
    public void after() {
        if (researcher != null) {
            researcher.signOutAndDeleteUser(); //must do before admin session signout    
        }
        adminSession.signOut();
    }

    @Test
    public void canCreateAndSignOutAndDeleteUser() {
        String email = Tests.makeEmail(UserManagementTest.class);
        String password = "P4ssword";

        StudyParticipant participant = new StudyParticipant.Builder()
                .withEmail(email).withPassword(password).build();
        
        String id = adminClient.createUser(participant, true);
        assertNotNull(id);

        participantClient.signOutUser(id);
        adminClient.deleteUser(id);
    }

}
