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
import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.api.ForResearchersApi;
import org.sagebionetworks.bridge.rest.api.InternalApi;
import org.sagebionetworks.bridge.rest.exceptions.AuthenticationFailedException;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.exceptions.InvalidEntityException;
import org.sagebionetworks.bridge.rest.model.EmailSignIn;
import org.sagebionetworks.bridge.rest.model.EmailSignInRequest;
import org.sagebionetworks.bridge.rest.model.HealthDataRecord;
import org.sagebionetworks.bridge.rest.model.Identifier;
import org.sagebionetworks.bridge.rest.model.Message;
import org.sagebionetworks.bridge.rest.model.Phone;
import org.sagebionetworks.bridge.rest.model.PhoneSignIn;
import org.sagebionetworks.bridge.rest.model.PhoneSignInRequest;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.SignIn;
import org.sagebionetworks.bridge.rest.model.SignUp;
import org.sagebionetworks.bridge.rest.model.SmsMessage;
import org.sagebionetworks.bridge.rest.model.SmsType;
import org.sagebionetworks.bridge.rest.model.Study;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.rest.model.UserSessionInfo;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.util.IntegTestUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import retrofit2.Response;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@Category(IntegrationSmokeTest.class)
@SuppressWarnings("unchecked")
public class AuthenticationTest {
    private static TestUser adminUser;
    private static TestUser researchUser;
    private static TestUser testUser;
    private static TestUser phoneOnlyTestUser;
    private static AuthenticationApi authApi;
    private static ForAdminsApi adminApi;
    
    @BeforeClass
    public static void beforeClass() throws IOException {
        researchUser = TestUserHelper.createAndSignInUser(AuthenticationTest.class, true, Role.RESEARCHER);
        
        // Make a test user with a phone number.
        SignUp phoneOnlyUser = new SignUp().study(IntegTestUtils.STUDY_ID).consent(true).phone(IntegTestUtils.PHONE);
        phoneOnlyTestUser = new TestUserHelper.Builder(AuthenticationTest.class).withConsentUser(true)
                .withSignUp(phoneOnlyUser).createUser();
        testUser = TestUserHelper.createAndSignInUser(AuthenticationTest.class, true);
        authApi = testUser.getClient(AuthenticationApi.class);

        adminUser = TestUserHelper.getSignedInAdmin();
        adminApi = adminUser.getClient(ForAdminsApi.class);

        // Verify necessary flags (health code export, email sign in, phone sign in, reauth) are enabled
        Study study = adminApi.getUsersStudy().execute().body();
        study.setHealthCodeExportEnabled(true);
        study.setPhoneSignInEnabled(true);
        study.setEmailSignInEnabled(true);
        study.setReauthenticationEnabled(true);
        adminApi.updateStudy(study.getIdentifier(), study).execute();
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
        Study study = adminApi.getUsersStudy().execute().body();
        study.setReauthenticationEnabled(false);
        adminApi.updateStudy(study.getIdentifier(), study).execute();
    }

    @Test
    public void requestEmailSignIn() throws Exception {
        EmailSignInRequest emailSignInRequest = new EmailSignInRequest().study(testUser.getStudyId())
                .email(testUser.getEmail());
        Study study = adminApi.getStudy(testUser.getStudyId()).execute().body();
        try {
            // Turn on email-based sign in for test. We can't verify the email was sent... we can verify this call
            // works and returns the right error conditions.
            study.setEmailSignInEnabled(true);
            
            // Bug: this call does not return VersionHolder (BRIDGE-1809). Retrieve study again.
            adminApi.updateStudy(study.getIdentifier(), study).execute();
            study = adminApi.getStudy(testUser.getStudyId()).execute().body();
            assertTrue(study.isEmailSignInEnabled());
            
            Response<Message> response = authApi.requestEmailSignIn(emailSignInRequest).execute();
            assertEquals(202, response.code());
        } finally {
            study.setEmailSignInEnabled(false);
            adminApi.updateStudy(study.getIdentifier(), study).execute();
        }
    }
    
    @Test
    public void emailSignIn() throws Exception {
        // We can't read the email from the test in order to extract a useful token, but we
        // can verify this call is made and fails as we'd expect.
        EmailSignIn emailSignIn = new EmailSignIn().study(testUser.getStudyId()).email(testUser.getEmail())
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
        Identifier email = new Identifier().study(testUser.getSignIn().getStudy()).email(testUser.getSignIn().getEmail());

        Response<Message> response = authApi.resendEmailVerification(email).execute();
        assertEquals(200, response.code());
    }
    
    @Test
    public void resendingEmailVerificationToUnknownEmailDoesNotThrowException() throws Exception {
        Identifier email = new Identifier().study(testUser.getStudyId()).email("bridge-testing@sagebase.org");
        
        Response<Message> response = authApi.resendEmailVerification(email).execute();
        assertEquals(200, response.code());
    }
    
    @Test
    public void requestingResetPasswordForUnknownEmailDoesNotThrowException() throws Exception {
        SignIn email = new SignIn().study(testUser.getStudyId()).email("fooboo-sagebridge@antwerp.com");
        
        Response<Message> response = authApi.requestResetPassword(email).execute();
        assertEquals(200, response.code());
    }

    @Test
    public void canRequestPasswordForPhone() throws Exception {
        // Request reset password.
        Response<Message> response = authApi.requestResetPassword(phoneOnlyTestUser.getSignIn()).execute();
        assertEquals(200, response.code());

        // Verify message logs contains the expected message.
        verifyTransactionalMessage();
    }

    @Test
    public void requestingResetPasswordForUnknownPhoneDoesNotThrowException() throws Exception {
        SignIn email = new SignIn().study(testUser.getStudyId())
                .phone(new Phone().number("4082588569").regionCode("CA"));
        
        Response<Message> response = authApi.requestResetPassword(email).execute();
        assertEquals(200, response.code());
    }

    @Test
    public void canResendPhoneVerification() throws Exception {
        // Request resend phone verificaiton.
        Identifier phone = new Identifier().study(phoneOnlyTestUser.getSignIn().getStudy())
                .phone(phoneOnlyTestUser.getPhone());

        Response<Message> response = authApi.resendPhoneVerification(phone).execute();
        assertEquals(200, response.code());

        // Verify message logs contains the expected message.
        verifyTransactionalMessage();
    }
    
    @Test
    public void resendingPhoneVerificationToUnknownPhoneDoesNotThrowException() throws Exception {
        Identifier identifier = new Identifier().study(testUser.getStudyId())
                .phone(new Phone().number("4082588569").regionCode("CA"));
        
        Response<Message> response = authApi.resendPhoneVerification(identifier).execute();
        assertEquals(200, response.code());
    }
    
    @Test
    public void accountWithOneStudySeparateFromAccountWithSecondStudy() throws IOException {
        String studyId = Tests.randomIdentifier(AuthenticationTest.class);
        try {
            testUser.signInAgain();

            // Make a second study for this test:
            Study study = new Study();
            study.setIdentifier(studyId);
            study.setName("Second Study");
            study.setSponsorName("Second Study");
            study.setSupportEmail("bridge-testing+support@sagebase.org");
            study.setConsentNotificationEmail("bridge-testing+consent@sagebase.org");
            study.setTechnicalEmail("bridge-testing+technical@sagebase.org");
            study.setResetPasswordTemplate(Tests.TEST_RESET_PASSWORD_TEMPLATE);
            study.setVerifyEmailTemplate(Tests.TEST_VERIFY_EMAIL_TEMPLATE);
            study.setEmailVerificationEnabled(true);
            
            adminApi.createStudy(study).execute();

            // Can we sign in to secondstudy? No.
            try {
                SignIn otherStudySignIn = new SignIn().study(studyId).email(testUser.getEmail())
                        .password(testUser.getPassword());
                ClientManager otherStudyManager = new ClientManager.Builder().withSignIn(otherStudySignIn).build();
                
                AuthenticationApi authClient = otherStudyManager.getClient(AuthenticationApi.class);
                
                authClient.signInV4(otherStudySignIn).execute();
                fail("Should not have allowed sign in");
            } catch (EntityNotFoundException e) {
                assertEquals(404, e.getStatusCode());
            }
        } finally {
            adminApi.deleteStudy(studyId, true).execute();
        }
    }
    
    // BRIDGE-465. We can at least verify that it gets processed as an error.
    @Test
    public void emailVerificationThrowsTheCorrectError() throws Exception {
        String hostUrl = testUser.getClientManager().getHostUrl();

        HttpResponse response = Request.Post(hostUrl + "/v3/auth/verifyEmail?study=api")
                .body(new StringEntity("{\"sptoken\":\"testtoken\",\"study\":\"api\"}")).execute().returnResponse();
        assertEquals(400, response.getStatusLine().getStatusCode());
        
        JsonNode node = new ObjectMapper().readTree(EntityUtils.toString(response.getEntity()));
        assertEquals("Verification token is invalid (it may have expired, or already been used).", node.get("message").asText());
    }
    
    @Test
    public void phoneVerificationThrowsTheCorrectError() throws Exception {
        String hostUrl = testUser.getClientManager().getHostUrl();

        HttpResponse response = Request.Post(hostUrl + "/v3/auth/verifyPhone?study=api")
                .body(new StringEntity("{\"sptoken\":\"testtoken\",\"study\":\"api\"}")).execute().returnResponse();
        assertEquals(400, response.getStatusLine().getStatusCode());
        
        JsonNode node = new ObjectMapper().readTree(EntityUtils.toString(response.getEntity()));
        assertEquals("Verification token is invalid (it may have expired, or already been used).", node.get("message").asText());
    }
    

    // Should not be able to tell from the sign up response if an email is enrolled in the study or not.
    // Server change is not yet checked in for this.
    @Test
    public void secondTimeSignUpLooksTheSameAsFirstTimeSignUp() throws Exception {
        TestUser testUser = TestUserHelper.createAndSignInUser(AuthenticationTest.class, true);
        try {
            testUser.signOut();
            
            // Now create the same user in terms of a sign up (same email).
            SignUp signUp = new SignUp().study(testUser.getStudyId()).email(testUser.getEmail())
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

        PhoneSignInRequest phoneSignIn = new PhoneSignInRequest().study(testUser.getStudyId());

        Response<Message> response = authApi.requestPhoneSignIn(phoneSignIn).execute();
        assertEquals(202, response.code());
    }

    @Test
    public void requestPhoneSignInWithPhone() throws Exception {
        // Request phone sign-in.
        AuthenticationApi authApi = phoneOnlyTestUser.getClient(AuthenticationApi.class);
        
        PhoneSignInRequest phoneSignIn = new PhoneSignInRequest().phone(phoneOnlyTestUser.getPhone())
                .study(phoneOnlyTestUser.getStudyId());

        Response<Message> response = authApi.requestPhoneSignIn(phoneSignIn).execute();
        assertEquals(202, response.code());

        // Verify message logs contains the expected message.
        verifyTransactionalMessage();
    }

    @Test(expected = AuthenticationFailedException.class)
    public void phoneSignInThrows() throws Exception {
        AuthenticationApi authApi = phoneOnlyTestUser.getClient(AuthenticationApi.class);

        PhoneSignIn phoneSignIn = new PhoneSignIn().phone(IntegTestUtils.PHONE).study(phoneOnlyTestUser.getStudyId()).token("test-token");

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
        SignIn badSignIn = new SignIn().study(IntegTestUtils.STUDY_ID).email(testUser.getEmail())
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
        SignIn badReauth = new SignIn().study(IntegTestUtils.STUDY_ID).email(testUser.getEmail())
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

        // Test again with reauth.
        SignIn reauth = new SignIn().study(IntegTestUtils.STUDY_ID).email(testUser.getEmail())
                .reauthToken(newSessionV4.getReauthToken());
        UserSessionInfo newSessionReauth = authApi.reauthenticate(reauth).execute().body();
        verifySession(401, newSessionV4.getSessionToken());
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
        assertEquals(phoneOnlyTestUser.getStudyId(), message.getStudyId());

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
        List<HealthDataRecord> recordList = phoneOnlyTestUser.getClient(InternalApi.class).getHealthDataByCreatedOn(
                messageSentOn, messageSentOn).execute().body().getItems();
        HealthDataRecord smsMessageRecord = recordList.stream()
                .filter(r -> r.getSchemaId().equals("sms-messages-sent-from-bridge")).findAny().get();
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
