package org.sagebionetworks.bridge.sdk.integration;

import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.sagebionetworks.bridge.rest.api.AuthenticationApi;
import org.sagebionetworks.bridge.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.rest.api.ForSuperadminsApi;
import org.sagebionetworks.bridge.rest.model.App;
import org.sagebionetworks.bridge.rest.model.SignIn;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.rest.model.UserSessionInfo;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;

import static org.junit.Assert.assertEquals;
import static org.sagebionetworks.bridge.sdk.integration.Tests.API_SIGNIN;

@Category(IntegrationSmokeTest.class)
public class UTF8Test {
    @Test
    public void canSaveAndRetrieveDataStoredInDynamo() throws Exception {
        String appId = Tests.randomIdentifier(UTF8Test.class);
        String appName = "☃지구상의　３대　극지라　불리는";
        ForSuperadminsApi superadminApi = TestUserHelper.getSignedInAdmin().getClient(ForSuperadminsApi.class);

        // make minimal study
        App app = new App();
        app.setIdentifier(appId);
        app.setName(appName);
        app.setSponsorName(appName);
        app.setTechnicalEmail("bridge-testing+technical@sagebase.org");
        app.setSupportEmail("bridge-testing+support@sagebase.org");
        app.setConsentNotificationEmail("bridge-testing+consent@sagebase.org");
        app.setEmailVerificationEnabled(true);

        // create study
        superadminApi.createApp(app).execute();

        try {
            superadminApi.adminChangeApp(new SignIn().appId(appId)).execute();
            
            // get study back and verify fields
            App returnedApp = superadminApi.getApp(appId).execute().body();
            assertEquals(appId, returnedApp.getIdentifier());
            assertEquals(appName, returnedApp.getName());
        } finally {
            superadminApi.adminChangeApp(API_SIGNIN).execute();
            // clean-up: delete study
            superadminApi.deleteApp(appId, true).execute();
        }
    }

    @Test
    public void canSaveAndRetrieveDataStoredInRedis() throws Exception {
        TestUser testUser = TestUserHelper.createAndSignInUser(UTF8Test.class, true);
        try {
            ForConsentedUsersApi usersApi = testUser.getClient(ForConsentedUsersApi.class);

            StudyParticipant participant = new StudyParticipant();
            participant.setFirstName("☃");
            participant.setLastName("지구상의　３대　극지라　불리는");

            usersApi.updateUsersParticipantRecord(participant).execute();

            // Force a refresh of the Redis session cache.
            AuthenticationApi authApi = testUser.getClient(AuthenticationApi.class);
            authApi.signOut().execute();
            UserSessionInfo session = authApi.signInV4(testUser.getSignIn()).execute().body();

            assertEquals("☃", session.getFirstName());
            assertEquals("지구상의　３대　극지라　불리는", session.getLastName());
        } finally {
            testUser.signOutAndDeleteUser();
        }
    }
}
