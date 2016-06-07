package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import org.sagebionetworks.bridge.sdk.ClientProvider;
import org.sagebionetworks.bridge.sdk.Session;
import org.sagebionetworks.bridge.sdk.StudyClient;
import org.sagebionetworks.bridge.sdk.UserClient;
import org.sagebionetworks.bridge.sdk.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.sdk.exceptions.InvalidEntityException;
import org.sagebionetworks.bridge.sdk.integration.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.sdk.models.accounts.EmailCredentials;
import org.sagebionetworks.bridge.sdk.models.accounts.SignInCredentials;
import org.sagebionetworks.bridge.sdk.models.accounts.StudyParticipant;
import org.sagebionetworks.bridge.sdk.models.studies.Study;

public class ClientProviderTest {

    @Test
    public void canAuthenticateAndCreateClientAndSignOut() {
        TestUserHelper.TestUser testUser = TestUserHelper.createAndSignInUser(ClientProviderTest.class, true);
        try {
            testUser.getSession().signOut();
            
            SignInCredentials credentials = new SignInCredentials(Tests.TEST_KEY, testUser.getEmail(), testUser.getPassword());
            Session session = ClientProvider.signIn(credentials);
            assertTrue(session.isSignedIn());

            UserClient userClient = session.getUserClient();
            assertNotNull(userClient);

            session.signOut();
            assertFalse(session.isSignedIn());
        } finally {
            testUser.signOutAndDeleteUser();
        }
    }
    
    @Test(expected = EntityNotFoundException.class)
    public void badStudyReturns404() {
        ClientProvider.requestResetPassword(new EmailCredentials("junk", "bridge-testing@sagebase.org"));
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void badEmailCredentialsReturnsException() {
        ClientProvider.requestResetPassword(new EmailCredentials(null, "bridge-testing@sagebase.org"));
    }
    
    @Test
    public void signUpForStudyWithExternalIdValidation() {
        Study study = Tests.getStudy(Tests.randomIdentifier(ClientProviderTest.class), null);
        study.setExternalIdValidationEnabled(true);
        
        TestUser admin = TestUserHelper.getSignedInAdmin();
        
        StudyClient studyClient = admin.getSession().getStudyClient();
        studyClient.createStudy(study);
        
        StudyParticipant participant = new StudyParticipant.Builder()
                .withEmail(Tests.makeEmail(ClientProviderTest.class))
                .withPassword("P@ssword`1")
                .build();
        try {
            try {
                ClientProvider.signUp(study.getIdentifier(), participant);
                fail("Should have thrown exception");
            } catch(InvalidEntityException e) {
                assertEquals("StudyParticipant is invalid: externalId cannot be null or blank", e.getMessage());
            }
            
            // Wrong ID. We can't add an ID to this study, as we can't add a user.
            participant = new StudyParticipant.Builder()
                    .copyOf(participant).withExternalId("ABC").build();
            try {
                ClientProvider.signUp(study.getIdentifier(), participant);
                fail("Should have thrown exception");
            }catch(EntityNotFoundException e) {
                assertEquals("ExternalIdentifier not found.", e.getMessage());
            }
            
        } finally {
            studyClient.deleteStudy(study.getIdentifier());
        }
    }
}
