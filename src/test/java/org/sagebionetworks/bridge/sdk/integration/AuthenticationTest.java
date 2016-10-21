package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.*;

import java.io.IOException;

import org.apache.http.HttpResponse;
import org.apache.http.client.fluent.Request;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.sagebionetworks.bridge.sdk.ClientManager;
import org.sagebionetworks.bridge.sdk.Config;
import org.sagebionetworks.bridge.sdk.integration.TestUserHelper2.TestUser;
import org.sagebionetworks.bridge.sdk.rest.api.AuthenticationApi;
import org.sagebionetworks.bridge.sdk.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.sdk.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.sdk.rest.model.Email;
import org.sagebionetworks.bridge.sdk.rest.model.SignIn;
import org.sagebionetworks.bridge.sdk.rest.model.SignUp;
import org.sagebionetworks.bridge.sdk.rest.model.Study;

@Category(IntegrationSmokeTest.class)
public class AuthenticationTest {

    private static TestUser testUser;
    private static AuthenticationApi authApi;
    
    @BeforeClass
    public static void beforeClass() throws IOException {
        testUser = TestUserHelper2.createAndSignInUser(AuthenticationTest.class, true);
        authApi = testUser.getClient(AuthenticationApi.class);
    }
    
    @AfterClass
    public static void afterClass() {
        testUser.signOutAndDeleteUser();
    }
    
    @Test
    public void canResendEmailVerification() throws IOException {
        Email email = new Email().study(testUser.getSignIn().getStudy())
                .email(testUser.getSignIn().getEmail());
        
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
        TestUser adminUser = TestUserHelper2.getSignedInAdmin();
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
            
            adminsApi.createStudy(study).execute();

            // Can we sign in to secondstudy? No.
            try {
                SignIn otherStudySignIn = new SignIn().study(studyId).email(testUser.getEmail()).password(testUser.getPassword());
                ClientManager otherStudyManager = new ClientManager.Builder().withSignIn(otherStudySignIn).build();
                
                AuthenticationApi authClient = otherStudyManager.getClient(AuthenticationApi.class);
                
                authClient.signIn(otherStudySignIn).execute();
                fail("Should not have allowed sign in");
            } catch(EntityNotFoundException e) {
                assertEquals(404, e.getStatusCode());
            }
        } finally {
            adminsApi.deleteStudy(studyId).execute();
        }
    }
    
    // BRIDGE-465. We can at least verify that it gets processed as an error.
    @Test
    public void emailVerificationThrowsTheCorrectError() throws Exception {
        Config config = testUser.getClientManager().getConfig();
        
        HttpResponse response = Request
            .Post(config.getEnvironment().getUrl() + "/api/v1/auth/verifyEmail?study=api")
            .body(new StringEntity("{\"sptoken\":\"testtoken\",\"study\":\"api\"}"))
            .execute().returnResponse();
        assertEquals(404, response.getStatusLine().getStatusCode());
        assertEquals("{\"message\":\"Account not found.\"}", EntityUtils.toString(response.getEntity()));
    }
    
    // Should not be able to tell from the sign up response if an email is enrolled in the study or not.
    // Server change is not yet checked in for this.
    @Test
    public void secondTimeSignUpLooksTheSameAsFirstTimeSignUp() throws IOException {
        TestUser testUser = TestUserHelper2.createAndSignInUser(AuthenticationTest.class, true);
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
}
