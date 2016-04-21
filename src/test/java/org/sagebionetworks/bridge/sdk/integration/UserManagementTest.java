package org.sagebionetworks.bridge.sdk.integration;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sagebionetworks.bridge.sdk.AdminClient;
import org.sagebionetworks.bridge.sdk.ClientProvider;
import org.sagebionetworks.bridge.sdk.Config;
import org.sagebionetworks.bridge.sdk.ResearcherClient;
import org.sagebionetworks.bridge.sdk.Roles;
import org.sagebionetworks.bridge.sdk.Session;
import org.sagebionetworks.bridge.sdk.integration.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.sdk.models.PagedResourceList;
import org.sagebionetworks.bridge.sdk.models.accounts.AccountSummary;
import org.sagebionetworks.bridge.sdk.models.users.SignUpByAdmin;

import static org.junit.Assert.assertTrue;

public class UserManagementTest {

    private Session adminSession;
    private AdminClient adminClient;

    private TestUser researcher;
    private ResearcherClient researcherClient;

    @Before
    public void before() {
        Config config = ClientProvider.getConfig();
        adminSession = ClientProvider.signIn(config.getAdminCredentials());
        adminClient = adminSession.getAdminClient();

        researcher = TestUserHelper.createAndSignInUser(UserManagementTest.class, true, Roles.RESEARCHER);
        researcherClient = researcher.getSession().getResearcherClient();
    }

    @After
    public void after() {
        researcher.signOutAndDeleteUser(); //must do before admin session signout
        adminSession.signOut();
    }

    @Test
    public void canCreateAndSignOutAndDeleteUser() {
        String email = Tests.makeEmail(UserManagementTest.class);
        String password = "P4ssword";

        SignUpByAdmin signUp = new SignUpByAdmin(email, password, null, /*consent*/true);

        boolean result = adminClient.createUser(signUp);
        assertTrue(result);
        
        PagedResourceList<AccountSummary> page = researcherClient.getPagedAccountSummaries(0, 10, email);
        String id = page.getItems().get(0).getId();

        researcherClient.signOutUser(id);

        adminClient.deleteUser(id);
    }

}
