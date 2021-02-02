package org.sagebionetworks.bridge.sdk.integration;

import org.junit.After;
import org.junit.Before;
import org.sagebionetworks.bridge.rest.api.ForSuperadminsApi;
import org.sagebionetworks.bridge.rest.model.App;
import org.sagebionetworks.bridge.rest.model.VersionHolder;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.util.IntegTestUtils;

import static org.sagebionetworks.bridge.rest.model.Role.DEVELOPER;
import static org.sagebionetworks.bridge.rest.model.Role.RESEARCHER;
import static org.sagebionetworks.bridge.util.IntegTestUtils.TEST_APP_ID;

public class ParticipantDataTest {
    private TestUser admin;
    private TestUser developer;
    private TestUser researcher;
    private TestUser phoneUser;
    private TestUser emailUser;
    private String externalId;

    @Before
    public void before() throws Exception {
        admin = TestUserHelper.getSignedInAdmin();
        developer = TestUserHelper.createAndSignInUser(ParticipantDataTest.class, false, DEVELOPER);
        researcher = TestUserHelper.createAndSignInUser(ParticipantDataTest.class, true, RESEARCHER);

        externalId = Tests.randomIdentifier(ParticipantDataTest.class);

        IntegTestUtils.deletePhoneUser(); //TODO I don't understand why this line is here

        ForSuperadminsApi superAdminApi = admin.getClient(ForSuperadminsApi.class);
        App app = superAdminApi.getApp(TEST_APP_ID).execute().body();
        if (!app.isPhoneSignInEnabled() || !app.isEmailSignInEnabled()) {
            app.setPhoneSignInEnabled(true);
            app.setEmailSignInEnabled(true);
            VersionHolder keys = superAdminApi.updateApp(app.getIdentifier(), app).execute().body();
            app.version(keys.getVersion());
        }
    }

    @After
    public void after() throws Exception {
        if (developer != null) {
            developer.signOutAndDeleteUser();
        }
        if (researcher != null) {
            researcher.signOutAndDeleteUser();
        }
        if (phoneUser != null) {
            phoneUser.signOutAndDeleteUser();
        }
        if (emailUser != null) {
            emailUser.signOutAndDeleteUser();
        }
    }

}
