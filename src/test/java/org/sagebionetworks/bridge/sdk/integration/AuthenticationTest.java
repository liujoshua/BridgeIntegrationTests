package org.sagebionetworks.bridge.sdk.integration;

import org.apache.http.HttpResponse;
import org.apache.http.client.fluent.Request;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.joda.time.DateTime;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.sagebionetworks.bridge.rest.ClientManager;
import org.sagebionetworks.bridge.rest.RestUtils;
import org.sagebionetworks.bridge.rest.api.AuthenticationApi;
import org.sagebionetworks.bridge.rest.api.ForResearchersApi;
import org.sagebionetworks.bridge.rest.api.ForSuperadminsApi;
import org.sagebionetworks.bridge.rest.api.InternalApi;
import org.sagebionetworks.bridge.rest.exceptions.AuthenticationFailedException;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.exceptions.InvalidEntityException;
import org.sagebionetworks.bridge.rest.model.App;
import org.sagebionetworks.bridge.rest.model.EmailSignIn;
import org.sagebionetworks.bridge.rest.model.EmailSignInRequest;
import org.sagebionetworks.bridge.rest.model.HealthDataRecord;
import org.sagebionetworks.bridge.rest.model.Identifier;
import org.sagebionetworks.bridge.rest.model.Message;
import org.sagebionetworks.bridge.rest.model.Phone;
import org.sagebionetworks.bridge.rest.model.PhoneSignIn;
import org.sagebionetworks.bridge.rest.model.PhoneSignInRequest;
import org.sagebionetworks.bridge.rest.model.SignIn;
import org.sagebionetworks.bridge.rest.model.SignUp;
import org.sagebionetworks.bridge.rest.model.SmsMessage;
import org.sagebionetworks.bridge.rest.model.SmsType;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.rest.model.UserSessionInfo;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import retrofit2.Response;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.sagebionetworks.bridge.rest.model.Role.RESEARCHER;
import static org.sagebionetworks.bridge.util.IntegTestUtils.PHONE;
import static org.sagebionetworks.bridge.util.IntegTestUtils.TEST_APP_ID;

@Category(IntegrationSmokeTest.class)
@SuppressWarnings({ "ConstantConditions", "unchecked" })
public class AuthenticationTest {
    private static final Logger LOG = LoggerFactory.getLogger(AuthenticationTest.class);

    private static TestUser adminUser;
    private static TestUser researchUser;
    private static TestUser testUser;
    private static TestUser phoneOnlyTestUser;
    private static AuthenticationApi authApi;
    private static ForSuperadminsApi superadminApi;
    
    @BeforeClass
    public static void beforeClass() throws IOException {
        researchUser = TestUserHelper.createAndSignInUser(AuthenticationTest.class, true, RESEARCHER);
        
        // Make a test user with a phone number.
        SignUp phoneOnlyUser = new SignUp().appId(TEST_APP_ID).consent(true).phone(PHONE);
        phoneOnlyTestUser = new TestUserHelper.Builder(AuthenticationTest.class).withConsentUser(true)
                .withSignUp(phoneOnlyUser).createUser();
        testUser = TestUserHelper.createAndSignInUser(AuthenticationTest.class, true);
        authApi = testUser.getClient(AuthenticationApi.class);

        adminUser = TestUserHelper.getSignedInAdmin();
        superadminApi = adminUser.getClient(ForSuperadminsApi.class);

        // Verify necessary flags (health code export, email sign in, phone sign in, reauth) are enabled
        App app = superadminApi.getApp(TEST_APP_ID).execute().body();
        app.setHealthCodeExportEnabled(true);
        app.setPhoneSignInEnabled(true);
        app.setEmailSignInEnabled(true);
        app.setReauthenticationEnabled(true);
        superadminApi.updateApp(app.getIdentifier(), app).execute();
    }
    
    @AfterClass
    public static void deleteResearcher() throws Exception {
        if (researchUser != null) {
            researchUser.signOutAndDeleteUser();
        }
    }
    @AfterClass
    public static void deleteUser() throws Exception {
        if (testUser != null) {
            testUser.signOutAndDeleteUser();
        }
    }
    @AfterClass
    public static void deletePhoneUser() throws Exception {
        if (phoneOnlyTestUser != null) {
            phoneOnlyTestUser.signOutAndDeleteUser();
        }
    }

    @AfterClass
    public static void disableReauth() throws Exception {
        // Because of https://sagebionetworks.jira.com/browse/BRIDGE-2091, we don't want to leave reauth enabled.
        App app = superadminApi.getApp(TEST_APP_ID).execute().body();
        app.setReauthenticationEnabled(false);
        superadminApi.updateApp(app.getIdentifier(), app).execute();
    }

    @Test
    public void requestEmailSignIn() throws Exception {
        EmailSignInRequest emailSignInRequest = new EmailSignInRequest().appId(testUser.getAppId())
                .email(testUser.getEmail());
        App app = superadminApi.getApp(testUser.getAppId()).execute().body();
        try {
            // Turn on email-based sign in for test. We can't verify the email was sent... we can verify this call
            // works and returns the right error conditions.
            app.setEmailSignInEnabled(true);
            
            // Bug: this call does not return VersionHolder (BRIDGE-1809). Retrieve app again.
            superadminApi.updateApp(app.getIdentifier(), app).execute();
            app = superadminApi.getApp(testUser.getAppId()).execute().body();
            assertTrue(app.isEmailSignInEnabled());
            
            Response<Message> response = authApi.requestEmailSignIn(emailSignInRequest).execute();
            assertEquals(202, response.code());
        } finally {
            app.setEmailSignInEnabled(false);
            superadminApi.updateApp(app.getIdentifier(), app).execute();
        }
    }
    
    @Test
    public void emailSignIn() throws Exception {
        // We can't read the email from the test in order to extract a useful token, but we
        // can verify this call is made and fails as we'd expect.
        EmailSignIn emailSignIn = new EmailSignIn().appId(testUser.getAppId()).email(testUser.getEmail())
                .token("ABD");
        try {
            authApi.signInViaEmail(emailSignIn).execute();
            fail("Should have thrown exception");
        } catch (AuthenticationFailedException e) {
            // expected exception
        }
    }
    
    @Test
    public void canResendEmailVerification() throws IOException {
        Identifier email = new Identifier().appId(testUser.getSignIn().getAppId()).email(testUser.getSignIn().getEmail());

        Response<Message> response = authApi.resendEmailVerification(email).execute();
        assertEquals(202, response.code());
    }
    
    @Test
    public void resendingEmailVerificationToUnknownEmailDoesNotThrowException() throws Exception {
        Identifier email = new Identifier().appId(testUser.getAppId()).email("bridge-testing@sagebase.org");
        
        Response<Message> response = authApi.resendEmailVerification(email).execute();
        assertEquals(202, response.code());
    }
    
    @Test
    public void requestingResetPasswordForUnknownEmailDoesNotThrowException() throws Exception {
        SignIn email = new SignIn().appId(testUser.getAppId()).email("fooboo-sagebridge@antwerp.com");
        
        Response<Message> response = authApi.requestResetPassword(email).execute();
        assertEquals(202, response.code());
    }

    @Test
    public void canRequestPasswordForPhone() throws Exception {
        // Request reset password.
        Response<Message> response = authApi.requestResetPassword(phoneOnlyTestUser.getSignIn()).execute();
        assertEquals(202, response.code());

        // Verify message logs contains the expected message.
        verifyTransactionalMessage();
    }

    @Test
    public void requestingResetPasswordForUnknownPhoneDoesNotThrowException() throws Exception {
        SignIn email = new SignIn().appId(testUser.getAppId())
                .phone(new Phone().number("4082588569").regionCode("CA"));
        
        Response<Message> response = authApi.requestResetPassword(email).execute();
        assertEquals(202, response.code());
    }

    @Test
    public void canResendPhoneVerification() throws Exception {
        // Request resend phone verification.
        Identifier phone = new Identifier().appId(phoneOnlyTestUser.getSignIn().getAppId())
                .phone(phoneOnlyTestUser.getPhone());

        Response<Message> response = authApi.resendPhoneVerification(phone).execute();
        assertEquals(202, response.code());

        // Verify message logs contains the expected message.
        verifyTransactionalMessage();
    }
    
    @Test
    public void resendingPhoneVerificationToUnknownPhoneDoesNotThrowException() throws Exception {
        Identifier identifier = new Identifier().appId(testUser.getAppId())
                .phone(new Phone().number("4082588569").regionCode("CA"));
        
        Response<Message> response = authApi.resendPhoneVerification(identifier).execute();
        assertEquals(202, response.code());
    }
    
    @Test
    public void accountWithOneAppSeparateFromAccountWithSecondApp() throws IOException {
        String appId = Tests.randomIdentifier(AuthenticationTest.class);
        try {
            testUser.signInAgain();

            // Make a second app for this test:
            App app = new App();
            app.setIdentifier(appId);
            app.setName("Second App");
            app.setSponsorName("Second App");
            app.setSupportEmail("bridge-testing+support@sagebase.org");
            app.setConsentNotificationEmail("bridge-testing+consent@sagebase.org");
            app.setTechnicalEmail("bridge-testing+technical@sagebase.org");
            app.setEmailVerificationEnabled(true);

            superadminApi.createApp(app).execute();

            // Can we sign in to secondapp? No.
            try {
                SignIn otherAppSignIn = new SignIn().appId(appId).email(testUser.getEmail())
                        .password(testUser.getPassword());
                ClientManager otherAppManager = new ClientManager.Builder().withSignIn(otherAppSignIn).build();
                
                AuthenticationApi authClient = otherAppManager.getClient(AuthenticationApi.class);
                
                authClient.signInV4(otherAppSignIn).execute();
                fail("Should not have allowed sign in");
            } catch (EntityNotFoundException e) {
                assertEquals(404, e.getStatusCode());
            }
        } finally {
            try {
                superadminApi.deleteApp(appId, true).execute();
            } catch (Exception ex) {
                LOG.error("Error deleting app " + appId + ": " + ex.getMessage(), ex);
            }
        }
    }
    
    // BRIDGE-465. We can at least verify that it gets processed as an error.
    @Test
    public void emailVerificationThrowsTheCorrectError() throws Exception {
        String hostUrl = testUser.getClientManager().getHostUrl();

        HttpResponse response = Request.Post(hostUrl + "/v3/auth/verifyEmail?appId=api")
                .body(new StringEntity("{\"sptoken\":\"testtoken\",\"appId\":\"api\"}")).execute().returnResponse();
        assertEquals(400, response.getStatusLine().getStatusCode());
        
        JsonNode node = new ObjectMapper().readTree(EntityUtils.toString(response.getEntity()));
        assertEquals("This verification token is no longer valid.", node.get("message").asText());
    }
    
    @Test
    public void phoneVerificationThrowsTheCorrectError() throws Exception {
        String hostUrl = testUser.getClientManager().getHostUrl();

        HttpResponse response = Request.Post(hostUrl + "/v3/auth/verifyPhone?appId=api")
                .body(new StringEntity("{\"sptoken\":\"testtoken\",\"appId\":\"api\"}")).execute().returnResponse();
        assertEquals(400, response.getStatusLine().getStatusCode());
        
        JsonNode node = new ObjectMapper().readTree(EntityUtils.toString(response.getEntity()));
        assertEquals("This verification token is no longer valid.", node.get("message").asText());
    }
    

    // Should not be able to tell from the sign up response if an email is enrolled in the app or not.
    // Server change is not yet checked in for this.
    @Test
    public void secondTimeSignUpLooksTheSameAsFirstTimeSignUp() throws Exception {
        TestUser testUser = TestUserHelper.createAndSignInUser(AuthenticationTest.class, true);
        try {
            testUser.signOut();
            
            // Now create the same user in terms of a sign up (same email).
            SignUp signUp = new SignUp().appId(testUser.getAppId()).email(testUser.getEmail())
                    .password(testUser.getPassword());
            
            // This should not throw an exception.
            AuthenticationApi authApi = testUser.getClient(AuthenticationApi.class);
            authApi.signUp(signUp).execute();
            
        } finally {
            testUser.signOutAndDeleteUser();
        }
    }
    
    @Test(expected = InvalidEntityException.class)
    public void requestPhoneSignInWithoutPhone() throws Exception {
        AuthenticationApi authApi = testUser.getClient(AuthenticationApi.class);

        PhoneSignInRequest phoneSignIn = new PhoneSignInRequest().appId(testUser.getAppId());

        Response<Message> response = authApi.requestPhoneSignIn(phoneSignIn).execute();
        assertEquals(202, response.code());
    }

    @Test
    public void requestPhoneSignInWithPhone() throws Exception {
        // Request phone sign-in.
        AuthenticationApi authApi = phoneOnlyTestUser.getClient(AuthenticationApi.class);
        
        PhoneSignInRequest phoneSignIn = new PhoneSignInRequest().phone(phoneOnlyTestUser.getPhone())
                .appId(phoneOnlyTestUser.getAppId());

        Response<Message> response = authApi.requestPhoneSignIn(phoneSignIn).execute();
        assertEquals(202, response.code());

        // Verify message logs contains the expected message.
        verifyTransactionalMessage();
    }

    @Test(expected = AuthenticationFailedException.class)
    public void phoneSignInThrows() throws Exception {
        AuthenticationApi authApi = phoneOnlyTestUser.getClient(AuthenticationApi.class);

        PhoneSignIn phoneSignIn = new PhoneSignIn().phone(PHONE).appId(phoneOnlyTestUser.getAppId()).token("test-token");

        authApi.signInViaPhone(phoneSignIn).execute();
    }
    
    @Test
    public void signInAndReauthenticateV4() throws IOException {
        AuthenticationApi authApi = testUser.getClient(AuthenticationApi.class);
        authApi.signOut().execute();
        
        UserSessionInfo session = authApi.signInV4(testUser.getSignIn()).execute().body();
        assertNotNull(session);
    }

    @Test
    public void sessionInvalidationTest() throws Exception {
        // Test account is shared across multiple tests, at least one of which signs out and signs back in. Sign in and
        // get the new session Id.
        AuthenticationApi authApi = testUser.getClient(AuthenticationApi.class);
        UserSessionInfo session = authApi.signIn(testUser.getSignIn()).execute().body();
        verifySession(200, session.getSessionToken());

        // Make sure signIn actually checks credentials even while already logged in.
        SignIn badSignIn = new SignIn().appId(TEST_APP_ID).email(testUser.getEmail())
                .password("bad password");
        try {
            authApi.signIn(badSignIn).execute();
            fail("expected exception");
        } catch (EntityNotFoundException ex) {
            // expected exception
        }
        try {
            authApi.signInV4(badSignIn).execute();
            fail("expected exception");
        } catch (EntityNotFoundException ex) {
            // expected exception
        }

        // Also, reauth
        SignIn badReauth = new SignIn().appId(TEST_APP_ID).email(testUser.getEmail())
                .reauthToken("bad token");
        try {
            authApi.reauthenticate(badReauth).execute();
            fail("expected exception");
        } catch (EntityNotFoundException ex) {
            // expected exception
        }

        // Verify the old session still works, despite the failed signIns.
        verifySession(200, session.getSessionToken());

        // Sign in to get a new session. Verify old session no longer works, but new session is still good.
        UserSessionInfo newSession = authApi.signIn(testUser.getSignIn()).execute().body();
        verifySession(401, session.getSessionToken());
        verifySession(200, newSession.getSessionToken());

        // Test again with v4.
        UserSessionInfo newSessionV4 = authApi.signInV4(testUser.getSignIn()).execute().body();
        verifySession(401, newSession.getSessionToken());
        verifySession(200, newSessionV4.getSessionToken());

        // Reauth will reacquire the same session because the token has not expired
        SignIn reauth = new SignIn().appId(TEST_APP_ID).email(testUser.getEmail())
                .reauthToken(newSessionV4.getReauthToken());
        UserSessionInfo newSessionReauth = authApi.reauthenticate(reauth).execute().body();
        verifySession(200, newSessionV4.getSessionToken());
        verifySession(200, newSessionReauth.getSessionToken());
    }

    // Helper function to make a raw HTTP request to a simple lightweight API that requires authentication, like get
    // activity events.
    private static void verifySession(int expectedStatusCode, String sessionId) throws Exception {
        String hostUrl = testUser.getClientManager().getHostUrl();
        HttpResponse httpResponse = Request.Get(hostUrl + "/v1/activityevents")
                .setHeader("Bridge-Session", sessionId).execute().returnResponse();
        assertEquals(expectedStatusCode, httpResponse.getStatusLine().getStatusCode());
    }

    private static void verifyTransactionalMessage() throws Exception {
        // Verify message logs contains the expected message.
        SmsMessage message = adminUser.getClient(InternalApi.class).getMostRecentSmsMessage(phoneOnlyTestUser
                .getUserId()).execute().body();
        assertEquals(phoneOnlyTestUser.getPhone().getNumber(), message.getPhoneNumber());
        assertNotNull(message.getMessageId());
        assertEquals(SmsType.TRANSACTIONAL, message.getSmsType());
        assertEquals(phoneOnlyTestUser.getAppId(), message.getAppId());

        // Message body isn't constrained by the test, so just check that it exists.
        assertNotNull(message.getMessageBody());

        // Clock skew on Jenkins can be known to go as high as 10 minutes. For a robust test, simply check that the
        // message was sent within the last hour.
        assertTrue(message.getSentOn().isAfter(DateTime.now().minusHours(1)));

        // Verify the health code matches.
        StudyParticipant participant = researchUser.getClient(ForResearchersApi.class).getParticipantById(
                phoneOnlyTestUser.getUserId(), false).execute().body();
        assertEquals(participant.getHealthCode(), message.getHealthCode());

        // Verify the SMS message log was written to health data.
        DateTime messageSentOn = message.getSentOn();
        Optional<HealthDataRecord> smsMessageRecordOpt = Tests.retryHelper(() -> phoneOnlyTestUser
                        .getClient(InternalApi.class).getHealthDataByCreatedOn(messageSentOn, messageSentOn).execute()
                        .body().getItems().stream().filter(r -> r.getSchemaId().equals("sms-messages-sent-from-bridge"))
                        .findAny(),
                Optional::isPresent);
        HealthDataRecord smsMessageRecord = smsMessageRecordOpt.get();
        assertEquals("sms-messages-sent-from-bridge", smsMessageRecord.getSchemaId());
        assertEquals(1, smsMessageRecord.getSchemaRevision().intValue());

        // SMS Message log saves the date as epoch milliseconds.
        assertEquals(messageSentOn.getMillis(), smsMessageRecord.getCreatedOn().getMillis());

        // Verify data.
        Map<String, String> recordDataMap = RestUtils.toType(smsMessageRecord.getData(), Map.class);
        assertEquals("Transactional", recordDataMap.get("smsType"));
        assertNotNull(recordDataMap.get("messageBody"));
        assertEquals(messageSentOn.getMillis(), DateTime.parse(recordDataMap.get("sentOn")).getMillis());
    }
}
