package org.sagebionetworks.bridge.sdk.integration;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.rest.api.ParticipantsApi;
import org.sagebionetworks.bridge.rest.exceptions.ConsentRequiredException;
import org.sagebionetworks.bridge.user.TestUserHelper;

import static org.junit.Assert.fail;
import static org.sagebionetworks.bridge.rest.model.Role.DEVELOPER;
import static org.sagebionetworks.bridge.util.IntegTestUtils.SHARED_STUDY_ID;

import org.joda.time.DateTime;

public class SessionRefreshTest {
    private static TestUserHelper.TestUser user;
    private static TestUserHelper.TestUser sharedDeveloper;

    @BeforeClass
    public static void createUser() throws Exception {
        user = TestUserHelper.createAndSignInUser(SessionRefreshTest.class, false);
        sharedDeveloper = TestUserHelper.createAndSignInUser(SessionRefreshTest.class, SHARED_STUDY_ID, DEVELOPER);
    }

    @AfterClass
    public static void deleteUser() throws Exception {
        if (user != null) {
            user.signOutAndDeleteUser();
        }
        if (sharedDeveloper != null) {
            sharedDeveloper.signOutAndDeleteUser();
        }
    }

    @Test
    public void testReauthenticationThrowsConsentException() throws Exception {
        // User starts out signed in. Initial call succeeds.
        user.getClient(ParticipantsApi.class).getUsersParticipantRecord(false).execute();

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
        sharedDeveloper.getClient(ParticipantsApi.class).getUsersParticipantRecord(false).execute();

        // Sign user out.
        sharedDeveloper.signOut();

        // Call should succeed again. Sign-in happens again behind the scenes
        sharedDeveloper.getClient(ParticipantsApi.class).getUsersParticipantRecord(false).execute();
    }
}
