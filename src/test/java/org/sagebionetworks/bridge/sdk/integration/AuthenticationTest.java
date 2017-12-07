package org.sagebionetworks.bridge.sdk.integration;

import org.apache.http.HttpResponse;
import org.apache.http.client.fluent.Request;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.sagebionetworks.bridge.rest.ClientManager;
import org.sagebionetworks.bridge.rest.api.AuthenticationApi;
import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.api.StudiesApi;
import org.sagebionetworks.bridge.rest.exceptions.AuthenticationFailedException;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.exceptions.InvalidEntityException;
import org.sagebionetworks.bridge.rest.model.Email;
import org.sagebionetworks.bridge.rest.model.EmailSignIn;
import org.sagebionetworks.bridge.rest.model.EmailSignInRequest;
import org.sagebionetworks.bridge.rest.model.Message;
import org.sagebionetworks.bridge.rest.model.PhoneSignIn;
import org.sagebionetworks.bridge.rest.model.PhoneSignInRequest;
import org.sagebionetworks.bridge.rest.model.SignIn;
import org.sagebionetworks.bridge.rest.model.SignUp;
import org.sagebionetworks.bridge.rest.model.Study;
import org.sagebionetworks.bridge.rest.model.UserSessionInfo;
import org.sagebionetworks.bridge.sdk.integration.TestUserHelper.TestUser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import retrofit2.Response;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@Category(IntegrationSmokeTest.class)
public class AuthenticationTest {

    private static TestUser testUser;
    private static TestUser adminUser;
    private static TestUser phoneOnlyTestUser;
    private static AuthenticationApi authApi;
    private static StudiesApi adminStudiesApi;
    
    @BeforeClass
    public static void beforeClass() throws IOException {
        // Make a test user with a phone number.
        SignUp phoneOnlyUser = new SignUp().study(Tests.TEST_KEY).consent(true).phone(Tests.PHONE);
        phoneOnlyTestUser = new TestUserHelper.Builder(AuthenticationTest.class).withConsentUser(true)
                .withSignUp(phoneOnlyUser).withSetPassword(false).createUser();
        testUser = TestUserHelper.createAndSignInUser(AuthenticationTest.class, true);
        authApi = testUser.getClient(AuthenticationApi.class);
        
        adminUser = TestUserHelper.getSignedInAdmin();
        adminStudiesApi = adminUser.getClient(StudiesApi.class);
    }
    
    @AfterClass
    public static void afterClass() throws Exception {
        try {
            testUser.signOutAndDeleteUser();    
        } finally {
            phoneOnlyTestUser.signOutAndDeleteUser();
        }
    }
    
    @Test
    public void requestEmailSignIn() throws Exception {
        EmailSignInRequest emailSignInRequest = new EmailSignInRequest().study(testUser.getStudyId())
                .email(testUser.getEmail());
        Study study = adminStudiesApi.getStudy(testUser.getStudyId()).execute().body();
        try {
            // Turn on email-based sign in for test. We can't verify the email was sent... we can verify this call
            // works and returns the right error conditions.
            study.setEmailSignInEnabled(true);
            
            // Bug: this call does not return VersionHolder (BRIDGE-1809). Retrieve study again.
            adminStudiesApi.updateStudy(study.getIdentifier(), study).execute();
            study = adminStudiesApi.getStudy(testUser.getStudyId()).execute().body();
            assertTrue(study.getEmailSignInEnabled());
            
            authApi.requestEmailSignIn(emailSignInRequest).execute();
        } finally {
            study.setEmailSignInEnabled(false);
            adminStudiesApi.updateStudy(study.getIdentifier(), study).execute();
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
        }
    }
    
    @Test
    public void canResendEmailVerification() throws IOException {
        Email email = new Email().study(testUser.getSignIn().getStudy()).email(testUser.getSignIn().getEmail());

        authApi.resendEmailVerification(email).execute();
    }
    
    @Test
    public void resendingEmailVerificationToUnknownEmailDoesNotThrowException() throws Exception {
        Email email = new Email().study(testUser.getStudyId()).email("bridge-testing@sagebase.org");
        
        authApi.resendEmailVerification(email).execute();
    }
    
    @Test
    public void requestingResetPasswordForUnknownEmailDoesNotThrowException() throws Exception {
        Email email = new Email().study(testUser.getStudyId()).email("fooboo-sagebridge@antwerp.com");
        
        authApi.requestResetPassword(email).execute();
    }
    
    @Test
    public void accountWithOneStudySeparateFromAccountWithSecondStudy() throws IOException {
        TestUser adminUser = TestUserHelper.getSignedInAdmin();
        ForAdminsApi adminsApi = adminUser.getClient(ForAdminsApi.class);
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
            
            adminsApi.createStudy(study).execute();

            // Can we sign in to secondstudy? No.
            try {
                SignIn otherStudySignIn = new SignIn().study(studyId).email(testUser.getEmail())
                        .password(testUser.getPassword());
                ClientManager otherStudyManager = new ClientManager.Builder().withSignIn(otherStudySignIn).build();
                
                AuthenticationApi authClient = otherStudyManager.getClient(AuthenticationApi.class);
                
                authClient.signIn(otherStudySignIn).execute();
                fail("Should not have allowed sign in");
            } catch (EntityNotFoundException e) {
                assertEquals(404, e.getStatusCode());
            }
        } finally {
            adminsApi.deleteStudy(studyId, true).execute();
        }
    }
    
    // BRIDGE-465. We can at least verify that it gets processed as an error.
    @Test
    public void emailVerificationThrowsTheCorrectError() throws Exception {
        String hostUrl = testUser.getClientManager().getHostUrl();

        HttpResponse response = Request.Post(hostUrl + "/api/v1/auth/verifyEmail?study=api")
                .body(new StringEntity("{\"sptoken\":\"testtoken\",\"study\":\"api\"}")).execute().returnResponse();
        assertEquals(400, response.getStatusLine().getStatusCode());
        
        JsonNode node = new ObjectMapper().readTree(EntityUtils.toString(response.getEntity()));
        assertEquals("Email verification token has expired (or already been used).", node.get("message").asText());
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
        AuthenticationApi authApi = phoneOnlyTestUser.getClient(AuthenticationApi.class);
        
        PhoneSignInRequest phoneSignIn = new PhoneSignInRequest().phone(Tests.PHONE)
                .study(phoneOnlyTestUser.getStudyId());

        Response<Message> response = authApi.requestPhoneSignIn(phoneSignIn).execute();
        assertEquals(202, response.code());
    }

    @Test(expected = AuthenticationFailedException.class)
    public void phoneSignInThrows() throws Exception {
        AuthenticationApi authApi = phoneOnlyTestUser.getClient(AuthenticationApi.class);

        PhoneSignIn phoneSignIn = new PhoneSignIn().phone(Tests.PHONE).study(phoneOnlyTestUser.getStudyId()).token("test-token");

        authApi.signInViaPhone(phoneSignIn).execute();
    }
    
    @Test
    public void signInAndReauthenticateV4() throws IOException {
        AuthenticationApi authApi = testUser.getClient(AuthenticationApi.class);
        authApi.signOut().execute();
        
        UserSessionInfo session = authApi.signInV4(testUser.getSignIn()).execute().body();
        assertNotNull(session);
    }
}
