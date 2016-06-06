package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.*;

import org.apache.http.HttpResponse;
import org.apache.http.client.fluent.Request;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.sagebionetworks.bridge.sdk.ClientProvider;
import org.sagebionetworks.bridge.sdk.Config;
import org.sagebionetworks.bridge.sdk.Config.Props;
import org.sagebionetworks.bridge.sdk.StudyClient;
import org.sagebionetworks.bridge.sdk.integration.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.sdk.exceptions.BridgeSDKException;
import org.sagebionetworks.bridge.sdk.models.accounts.EmailCredentials;
import org.sagebionetworks.bridge.sdk.models.accounts.SignInCredentials;
import org.sagebionetworks.bridge.sdk.models.accounts.StudyParticipant;
import org.sagebionetworks.bridge.sdk.models.studies.Study;

@Category(IntegrationSmokeTest.class)
public class AuthenticationTest {

    @Test
    public void canResendEmailVerification() {
        // Just verify this doesn't throw an exception
        ClientProvider.resendEmailVerification(
                new EmailCredentials(Tests.TEST_KEY, "bridge-testing@sagebase.org"));
    }
    
    @Test
    public void resendingEmailVerificationToUnknownEmailDoesNotThrowException() throws Exception {
        ClientProvider.resendEmailVerification(new EmailCredentials(Tests.TEST_KEY, "fooboo-sagebridge@antwerp.com"));
    }
    
    @Test
    public void requestingResetPasswordForUnknownEmailDoesNotThrowException() throws Exception {
        ClientProvider.requestResetPassword(new EmailCredentials(Tests.TEST_KEY, "fooboo-sagebridge@antwerp.com"));
    }
    
    @Test
    public void accountWithOneStudySeparateFromAccountWithSecondStudy() {
        TestUser testUser1 = TestUserHelper.createAndSignInUser(AuthenticationTest.class, true);
        Config config = ClientProvider.getConfig();
        StudyClient studyClient = ClientProvider.signIn(config.getAdminCredentials()).getStudyClient();
        String studyId = Tests.randomIdentifier(AuthenticationTest.class);

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
        studyClient.createStudy(study);

        try {
            // Can we sign in to secondstudy? No.
            try {
                config.set(Props.STUDY_IDENTIFIER, studyId);
                ClientProvider.signIn(new SignInCredentials(studyId, testUser1.getEmail(), testUser1.getPassword()));
                fail("Should not have allowed sign in");
            } catch(BridgeSDKException e) {
                assertEquals(404, e.getStatusCode());
            }
        } finally {
            config.set(Props.STUDY_IDENTIFIER, "api");
            studyClient.deleteStudy(studyId);
            testUser1.signOutAndDeleteUser();
        }
    }
    
    // BRIDGE-465. We can at least verify that it gets processed as an error.
    @Test
    public void emailVerificationThrowsTheCorrectError() throws Exception {
        Config config = ClientProvider.getConfig();
        
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
    public void secondTimeSignUpLooksTheSameAsFirstTimeSignUp() {
        TestUser testUser = TestUserHelper.createAndSignInUser(AuthenticationTest.class, true);
        try {
            testUser.getSession().signOut();
            
            // Now create the same user.
            StudyParticipant participant = new StudyParticipant.Builder()
                    .withEmail(testUser.getEmail()).withPassword(testUser.getPassword()).build();
            ClientProvider.signUp(Tests.TEST_KEY, participant);
            
        } finally {
            testUser.signOutAndDeleteUser();
        }
    }
}
