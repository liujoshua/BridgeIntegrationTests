package org.sagebionetworks.bridge.sdk.integration;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.sagebionetworks.bridge.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.rest.api.ParticipantsApi;
import org.sagebionetworks.bridge.rest.exceptions.ConsentRequiredException;
import org.sagebionetworks.bridge.user.TestUserHelper;

import static org.junit.Assert.fail;

import org.joda.time.DateTime;

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
    public void testReauthenticationThrowsConsentException() throws Exception {
        // User starts out signed in. Initial call succeeds.
        user.getClient(ParticipantsApi.class).getUsersParticipantRecord().execute();

        // Sign user out.
        user.signOut();

        // Call should succeed again. Sign-in happens again behind the scenes
        try {
            user.getClient(ForConsentedUsersApi.class)
                    .getScheduledActivitiesByDateRange(DateTime.now().minusDays(2), DateTime.now()).execute();
            fail("ConsentRequiredException expected");
        } catch (ConsentRequiredException e) {
            // this is expected
        }
    }

    @Test
    public void testReauthenticationAcrossStudies() throws Exception {
        // Use developer from the Shared study to test across studies. Initial call succeeds.
        TestUserHelper.TestUser sharedDeveloper = TestUserHelper.getSignedInSharedDeveloper();
        sharedDeveloper.getClient(ParticipantsApi.class).getUsersParticipantRecord().execute();

        // Sign user out.
        sharedDeveloper.signOut();

        // Call should succeed again. Sign-in happens again behind the scenes
        sharedDeveloper.getClient(ParticipantsApi.class).getUsersParticipantRecord().execute();
    }
}
