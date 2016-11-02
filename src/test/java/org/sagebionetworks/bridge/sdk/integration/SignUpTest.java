package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;

import org.junit.Test;

import org.sagebionetworks.bridge.sdk.integration.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.rest.api.AuthenticationApi;
import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.exceptions.InvalidEntityException;
import org.sagebionetworks.bridge.rest.model.Email;
import org.sagebionetworks.bridge.rest.model.EmptyPayload;
import org.sagebionetworks.bridge.rest.model.SignIn;
import org.sagebionetworks.bridge.rest.model.SignUp;
import org.sagebionetworks.bridge.rest.model.Study;
import org.sagebionetworks.bridge.rest.model.UserSessionInfo;

public class SignUpTest {

    @Test
    public void canAuthenticateAndCreateClientAndSignOut() throws IOException {
        TestUser testUser = TestUserHelper.createAndSignInUser(SignUpTest.class, true);
        try {
            AuthenticationApi authApi = testUser.getClient(AuthenticationApi.class);
            
            authApi.signOut(new EmptyPayload()).execute();
            
            SignIn signIn = testUser.getSignIn();
            UserSessionInfo session = authApi.signIn(signIn).execute().body();
            
            assertTrue(session.getAuthenticated());

            authApi.signOut(new EmptyPayload()).execute();
        } finally {
            testUser.signOutAndDeleteUser();
        }
    }
    
    @Test(expected = EntityNotFoundException.class)
    public void badStudyReturns404() throws IOException {
        TestUser testUser = TestUserHelper.createAndSignInUser(SignUpTest.class, true);
        try {
            AuthenticationApi authApi = testUser.getClient(AuthenticationApi.class);
            
            Email email = new Email().study("junk").email("bridge-testing@sagebase.org");
            authApi.requestResetPassword(email).execute();
        } finally {
            testUser.signOutAndDeleteUser();
        }
    }
    
    @Test(expected = EntityNotFoundException.class)
    public void badEmailCredentialsReturnsException() throws IOException {
        TestUser testUser = TestUserHelper.createAndSignInUser(SignUpTest.class, true);
        try {
            AuthenticationApi authApi = testUser.getClient(AuthenticationApi.class);
            
            Email email = new Email().email("bridge-testing@sagebase.org");
            authApi.requestResetPassword(email).execute();
        } finally {
            testUser.signOutAndDeleteUser();
        }
    }
    
    @Test
    public void signUpForStudyWithExternalIdValidation() throws Exception {
        Study study = Tests.getStudy(Tests.randomIdentifier(SignUpTest.class), null);
        study.setExternalIdValidationEnabled(true);
        
        TestUser admin = TestUserHelper.getSignedInAdmin();
        
        ForAdminsApi adminApi = admin.getClient(ForAdminsApi.class);
        adminApi.createStudy(study).execute();

        SignUp signUp = new SignUp()
                .study(study.getIdentifier())
                .email(Tests.makeEmail(SignUpTest.class))
                .password("P@ssword`1");
        AuthenticationApi authApi = admin.getClient(AuthenticationApi.class);
        try {
            try {
                authApi.signUp(signUp).execute();
                fail("Should have thrown exception");
            } catch(InvalidEntityException e) {
                assertEquals("StudyParticipant is invalid: externalId cannot be null or blank", e.getMessage());
            }
            try {
                // Wrong ID. We can't add an ID to this study, as we can't add a user.
                signUp.setExternalId("ABC");
                authApi.signUp(signUp).execute();
                fail("Should have thrown exception");
            }catch(EntityNotFoundException e) {
                assertEquals("ExternalIdentifier not found.", e.getMessage());
            }
        } finally {
            adminApi.deleteStudy(study.getIdentifier()).execute();
        }
    }
}
