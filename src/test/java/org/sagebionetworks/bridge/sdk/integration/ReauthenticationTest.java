package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
import static org.sagebionetworks.bridge.util.IntegTestUtils.STUDY_ID;

import java.util.HashSet;
import java.util.Set;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.sagebionetworks.bridge.rest.api.AuthenticationApi;
import org.sagebionetworks.bridge.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.rest.api.ForSuperadminsApi;
import org.sagebionetworks.bridge.rest.exceptions.ConsentRequiredException;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.model.SignIn;
import org.sagebionetworks.bridge.rest.model.Study;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.rest.model.UserSessionInfo;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

/**
 * Use of the re-authentication token:
 * - you can use it to acquire a new session under a new token (time should be reset on the session as well)
 * - it's in the session whe you get a consent required exception
 * - you can't use the token twice
 * - the token is invalidated when you sign out of the server
 * - if it's wrong, you get a 404 (no indication whether the email is in system or not) 
 */
public class ReauthenticationTest {

    private static final int REAUTH_CACHE_IN_MILLIS = 15000;
    private TestUser user;
    
    @Before
    public void before() throws Exception {
        user = TestUserHelper.createAndSignInUser(ReauthenticationTest.class, true);
    }
    
    @After
    public void after() throws Exception {
        if (user != null) {
            user.signOutAndDeleteUser();    
        }
    }
    
    @BeforeClass
    public static void turnOnReauthentication() throws Exception {
        TestUser admin = TestUserHelper.getSignedInAdmin();
        ForSuperadminsApi superadminApi = admin.getClient(ForSuperadminsApi.class);
        
        Study study = superadminApi.getStudy(STUDY_ID).execute().body();
        study.setReauthenticationEnabled(true);
        superadminApi.updateStudy(STUDY_ID, study).execute();
    }
    
    @AfterClass
    public static void turnOffReauthentication() throws Exception {
        TestUser admin = TestUserHelper.getSignedInAdmin();
        ForSuperadminsApi superadminApi = admin.getClient(ForSuperadminsApi.class);
        
        Study study = superadminApi.getStudy(STUDY_ID).execute().body();
        study.setReauthenticationEnabled(false);
        superadminApi.updateStudy(STUDY_ID, study).execute();
    }
    
    @Test
    public void simulateServerSessionTimeout() throws Exception {
        ForConsentedUsersApi usersApi = user.getClient(ForConsentedUsersApi.class);
        usersApi.getActivityEvents().execute().body();
        
        // Simulate loss of the session on the server.
        String sessionToken = user.getSession().getSessionToken();
        
        RequestBody body = RequestBody.create(MediaType.parse("application/json"), "{}");
        
        // This happens outside of the client, so the client is still holding onto a session
        // that is no longer valid. Using the authorization interceptor, it reacquires a 
        // session on the next call.
        Request request = new Request.Builder()
                .addHeader("Bridge-Session", sessionToken)
                .post(body)
                .url(user.getClientManager().getHostUrl()+"/v3/auth/signOut")
                .build();
        OkHttpClient client = new OkHttpClient.Builder().build();        
        client.newCall(request).execute();

        usersApi.getActivityEvents().execute().body();
    }
    
    @Test
    public void reauthenticationTwiceReturnsSameSession() throws Exception {
        UserSessionInfo session = user.getSession();
        
        // You can re-authenticate.
        String oldSessionToken = session.getSessionToken();
        String reauthToken = session.getReauthToken();
        SignIn signIn = new SignIn().study(user.getStudyId())
                .email(user.getSession().getEmail()).reauthToken(reauthToken);
        
        Set<String> sessionTokens = new HashSet<>();
        Set<String> reauthTokens = new HashSet<>();
        AuthenticationApi authApi = user.getClient(AuthenticationApi.class);

        UserSessionInfo firstSession = authApi.reauthenticate(signIn).execute().body();
        sessionTokens.add(firstSession.getSessionToken());
        reauthTokens.add(firstSession.getReauthToken());
        
        assertNotEquals(reauthToken, firstSession.getReauthToken());
        assertEquals(oldSessionToken, firstSession.getSessionToken());
        
        // Using the same token also returns a session.
        UserSessionInfo secondSession = authApi.reauthenticate(signIn).execute().body();
        sessionTokens.add(secondSession.getSessionToken());
        reauthTokens.add(secondSession.getReauthToken());
        
        UserSessionInfo thirdSession = authApi.reauthenticate(signIn).execute().body();
        sessionTokens.add(thirdSession.getSessionToken());
        reauthTokens.add(thirdSession.getReauthToken());

        // User should be able to make this call without incident.
        ForConsentedUsersApi usersApi = user.getClient(ForConsentedUsersApi.class);
        usersApi.getActivityEvents().execute();
        
        // All three sign-ins got back unique session tokens and reauth tokens
        assertEquals(1, sessionTokens.size());
        assertEquals(3, reauthTokens.size());
        
        try {
            // The first reauth token should now be rotated out and no longer usable.
            authApi.reauthenticate(signIn).execute().body();
            fail("Should have thrown exception.");
        } catch(EntityNotFoundException e) {

        }
        
        // If you sign out, all reauthentication tokens are destroyed, even the most recent token.
        String email = user.getSession().getEmail();
        user.signOut();
        
        try {
            signIn = new SignIn().study(user.getStudyId()).email(email).reauthToken(thirdSession.getReauthToken());
            authApi.reauthenticate(signIn).execute().body();
            fail("Should have thrown exception.");
        } catch(EntityNotFoundException e) {
        }
    }
    
    @Test
    public void consentRequiredException() throws Exception {
        TestUser unconsentedUser = TestUserHelper.createAndSignInUser(ReauthenticationTest.class, false);
        try {
            try {
                unconsentedUser.signInAgain();    
                fail("Should have thrown exception.");
            } catch(ConsentRequiredException e) {
                assertNotNull(e.getSession().getReauthToken());
            }
        } finally {
            unconsentedUser.signOutAndDeleteUser();
        }
    }
    
    @Test
    public void reauthenticationWorksAfterAccountUpdate() throws Exception {
        TestUser testUser = TestUserHelper.createAndSignInUser(ReauthenticationTest.class, true);
        try {
            String reauthToken = testUser.getSession().getReauthToken();
            
            ForConsentedUsersApi userApi = testUser.getClient(ForConsentedUsersApi.class);
            StudyParticipant participant = userApi.getUsersParticipantRecord(false).execute().body();
            participant.setFirstName("Lacy");
            participant.setLastName("Loo");
            
            userApi.updateUsersParticipantRecord(participant).execute().body();
            
            // Pause because we're now caching the reauth token and we can't verify it 
            // rotates without waiting
            Thread.sleep(REAUTH_CACHE_IN_MILLIS);
            // Cannot sign out, it destroys the token... but this will still reauth and rotate the token.
            // Pause for 16 seconds... becuase we're caching this value
            Thread.sleep(16000);
            
            SignIn signIn = new SignIn().study(testUser.getStudyId()).email(testUser.getEmail()).reauthToken(reauthToken);
            AuthenticationApi authApi = testUser.getClient(AuthenticationApi.class);
            UserSessionInfo newSession = authApi.reauthenticate(signIn).execute().body();
            assertNotEquals(reauthToken, newSession.getReauthToken());
        } finally {
            testUser.signOutAndDeleteUser();
        }
    }
}
