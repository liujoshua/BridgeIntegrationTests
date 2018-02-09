package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.sagebionetworks.bridge.rest.api.AuthenticationApi;
import org.sagebionetworks.bridge.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.rest.api.StudiesApi;
import org.sagebionetworks.bridge.rest.exceptions.ConsentRequiredException;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.model.SignIn;
import org.sagebionetworks.bridge.rest.model.UserSessionInfo;
import org.sagebionetworks.bridge.sdk.integration.TestUserHelper.TestUser;

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
        StudiesApi studiesApi = admin.getClient(StudiesApi.class);
        
        Study study = studiesApi.getStudy("api").execute().body();
        study.setReauthenticationEnabled(true);
        studiesApi.updateStudy("api", study).execute();
    }
    
    @AfterClass
    public static void turnOffReauthentication() throws Exception {
        TestUser admin = TestUserHelper.getSignedInAdmin();
        StudiesApi studiesApi = admin.getClient(StudiesApi.class);
        
        Study study = studiesApi.getStudy("api").execute().body();
        study.setReauthenticationEnabled(false);
        studiesApi.updateStudy("api", study).execute();
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
    public void reauthenticationCannotHappenTwice() throws Exception {
        UserSessionInfo session = user.getSession();
        
        // You can re-authenticate.
        String oldSessionToken = session.getSessionToken();
        String reauthToken = session.getReauthToken();
        SignIn request = new SignIn().study(user.getStudyId())
                .email(user.getSession().getEmail()).reauthToken(reauthToken);
        
        AuthenticationApi authApi = user.getClient(AuthenticationApi.class);
        UserSessionInfo newSession = authApi.reauthenticate(request).execute().body();
        
        assertNotEquals(reauthToken, newSession.getReauthToken());
        assertNotEquals(oldSessionToken, newSession.getSessionToken());
        
        // Using the same token again does not work
        try {
            authApi.reauthenticate(request).execute().body();
            fail("Should have thrown exception.");
        } catch(EntityNotFoundException e) {
            
        }
        
        // User should be able to make this call without incident.
        ForConsentedUsersApi usersApi = user.getClient(ForConsentedUsersApi.class);
        usersApi.getActivityEvents().execute();
        
        user.signOut();
        
        try {
            request = new SignIn().study(user.getStudyId()).email(newSession.getEmail())
                    .reauthToken(newSession.getReauthToken());
            authApi.reauthenticate(request).execute().body();
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
}
