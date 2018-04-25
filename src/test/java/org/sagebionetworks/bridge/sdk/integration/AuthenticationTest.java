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
import org.sagebionetworks.bridge.rest.exceptions.AuthenticationFailedException;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.exceptions.InvalidEntityException;
import org.sagebionetworks.bridge.rest.model.EmailSignIn;
import org.sagebionetworks.bridge.rest.model.EmailSignInRequest;
import org.sagebionetworks.bridge.rest.model.Identifier;
import org.sagebionetworks.bridge.rest.model.Message;
import org.sagebionetworks.bridge.rest.model.Phone;
import org.sagebionetworks.bridge.rest.model.PhoneSignIn;
import org.sagebionetworks.bridge.rest.model.PhoneSignInRequest;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.SignIn;
import org.sagebionetworks.bridge.rest.model.SignUp;
import org.sagebionetworks.bridge.rest.model.Study;
import org.sagebionetworks.bridge.rest.model.UserSessionInfo;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.util.IntegTestUtils;

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
    private static TestUser researchUser;
    private static TestUser testUser;
    private static TestUser adminUser;
    private static TestUser phoneOnlyTestUser;
    private static AuthenticationApi authApi;
    private static ForAdminsApi adminApi;
    
    @BeforeClass
    public static void beforeClass() throws IOException {
        researchUser = TestUserHelper.createAndSignInUser(AuthenticationTest.class, true, Role.RESEARCHER);
        
        // Make a test user with a phone number.
        SignUp phoneOnlyUser = new SignUp().study(IntegTestUtils.STUDY_ID).consent(true).phone(IntegTestUtils.PHONE);
        phoneOnlyTestUser = new TestUserHelper.Builder(AuthenticationTest.class).withConsentUser(true)
                .withSignUp(phoneOnlyUser).withSetPassword(false).createUser();
        testUser = TestUserHelper.createAndSignInUser(AuthenticationTest.class, true);
        authApi = testUser.getClient(AuthenticationApi.class);
        
        adminUser = TestUserHelper.getSignedInAdmin();
        adminApi = adminUser.getClient(ForAdminsApi.class);
        
        Study study = adminApi.getUsersStudy().execute().body();
        if (!study.getPhoneSignInEnabled() || !study.getEmailSignInEnabled()) {
            study.setPhoneSignInEnabled(true);
            study.setEmailSignInEnabled(true);
            adminApi.updateStudy(study.getIdentifier(), study).execute();
        }
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
            assertTrue(study.getEmailSignInEnabled());
            
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
    public void requestingResetPasswordForUnknownPhoneDoesNotThrowException() throws Exception {
        SignIn email = new SignIn().study(testUser.getStudyId())
                .phone(new Phone().number("4082588569").regionCode("CA"));
        
        Response<Message> response = authApi.requestResetPassword(email).execute();
        assertEquals(200, response.code());
    }

    @Test
    public void canResendPhoneVerification() throws IOException {
        Identifier phone = new Identifier().study(phoneOnlyTestUser.getSignIn().getStudy())
                .phone(phoneOnlyTestUser.getPhone());

        Response<Message> response = authApi.resendPhoneVerification(phone).execute();
        assertEquals(200, response.code());
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
                
                authClient.signInV4(otherStudySignIn).execute();
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
        AuthenticationApi authApi = phoneOnlyTestUser.getClient(AuthenticationApi.class);
        
        PhoneSignInRequest phoneSignIn = new PhoneSignInRequest().phone(phoneOnlyTestUser.getPhone())
                .study(phoneOnlyTestUser.getStudyId());

        Response<Message> response = authApi.requestPhoneSignIn(phoneSignIn).execute();
        assertEquals(202, response.code());
    }

    @Test(expected = AuthenticationFailedException.class)
    public void phoneSignInThrows() throws Exception {
        Tests.deletePhoneUser(researchUser);

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
}
