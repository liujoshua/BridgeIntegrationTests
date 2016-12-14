package org.sagebionetworks.bridge.sdk.integration;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.api.ParticipantsApi;

public class SessionRefreshTest {
    private static TestUserHelper.TestUser user;

    @BeforeClass
    public static void createUser() throws Exception {
        user = TestUserHelper.createAndSignInUser(SessionRefreshTest.class, false);
    }

    @AfterClass
    public static void deleteUser() throws Exception {
        if (user != null) {
            user.signOutAndDeleteUser();
        }
    }

    @Test
    public void test() throws Exception {
        // User starts out signed in. Initial call succeeds.
        user.getClient(ParticipantsApi.class).getUsersParticipantRecord().execute();

        // Sign user out.
        user.signOut();

        // Call should succeed again. Sign-in happens again behind the scenes
        user.getClient(ParticipantsApi.class).getUsersParticipantRecord().execute();
    }
}
