package org.sagebionetworks.bridge.sdk.integration;

import org.junit.Test;
import org.sagebionetworks.bridge.rest.api.AuthenticationApi;
import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.api.ForSuperadminsApi;
import org.sagebionetworks.bridge.rest.api.OrganizationsApi;
import org.sagebionetworks.bridge.rest.api.ParticipantsApi;
import org.sagebionetworks.bridge.rest.api.StudiesApi;
import org.sagebionetworks.bridge.rest.exceptions.BadRequestException;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.exceptions.InvalidEntityException;
import org.sagebionetworks.bridge.rest.model.App;
import org.sagebionetworks.bridge.rest.model.Organization;
import org.sagebionetworks.bridge.rest.model.SharingScope;
import org.sagebionetworks.bridge.rest.model.SignIn;
import org.sagebionetworks.bridge.rest.model.SignUp;
import org.sagebionetworks.bridge.rest.model.Study;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.rest.model.UserSessionInfo;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.util.IntegTestUtils;

import java.io.IOException;
import java.util.Map;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.sagebionetworks.bridge.rest.model.SharingScope.NO_SHARING;
import static org.sagebionetworks.bridge.sdk.integration.Tests.API_SIGNIN;
import static org.sagebionetworks.bridge.sdk.integration.Tests.PASSWORD;
import static org.sagebionetworks.bridge.sdk.integration.Tests.SHARED_SIGNIN;
import static org.sagebionetworks.bridge.sdk.integration.Tests.STUDY_ID_1;
import static org.sagebionetworks.bridge.sdk.integration.Tests.STUDY_ID_2;
import static org.sagebionetworks.bridge.util.IntegTestUtils.SAGE_ID;
import static org.sagebionetworks.bridge.util.IntegTestUtils.SHARED_APP_ID;
import static org.sagebionetworks.bridge.util.IntegTestUtils.TEST_APP_ID;

public class SignUpTest {

    @Test
    public void defaultValuesExist() throws Exception {
        TestUser testUser = TestUserHelper.createAndSignInUser(SignUpTest.class, true);
        try {
            ParticipantsApi participantsApi = testUser.getClientManager().getClient(ParticipantsApi.class);

            StudyParticipant participant = participantsApi.getUsersParticipantRecord(false).execute().body();
            assertTrue(participant.isNotifyByEmail());
            assertEquals(SharingScope.NO_SHARING, participant.getSharingScope());
        } finally {
            testUser.signOutAndDeleteUser();
        }
    }
    
    @Test
    public void canAuthenticateAndCreateClientAndSignOut() throws IOException {
        TestUser testUser = TestUserHelper.createAndSignInUser(SignUpTest.class, true);
        try {
            AuthenticationApi authApi = testUser.getClient(AuthenticationApi.class);
            
            authApi.signOut().execute();
            
            SignIn signIn = testUser.getSignIn();
            UserSessionInfo session = authApi.signInV4(signIn).execute().body();
            
            assertTrue(session.isAuthenticated());

            authApi.signOut().execute();
        } finally {
            testUser.signOutAndDeleteUser();
        }
    }
    
    @Test(expected = EntityNotFoundException.class)
    public void badAppIdReturns404() throws IOException {
        TestUser testUser = TestUserHelper.createAndSignInUser(SignUpTest.class, true);
        try {
            AuthenticationApi authApi = testUser.getClient(AuthenticationApi.class);
            
            SignIn email = new SignIn().appId("junk").email("bridge-testing@sagebase.org");
            authApi.requestResetPassword(email).execute();
        } finally {
            testUser.signOutAndDeleteUser();
        }
    }
    
    @Test(expected = BadRequestException.class)
    public void badEmailCredentialsReturnsException() throws IOException {
        TestUser testUser = TestUserHelper.createAndSignInUser(SignUpTest.class, true);
        try {
            AuthenticationApi authApi = testUser.getClient(AuthenticationApi.class);
            
            SignIn email = new SignIn().email("bridge-testing@sagebase.org");
            authApi.requestResetPassword(email).execute();
        } finally {
            testUser.signOutAndDeleteUser();
        }
    }
    
    @Test
    public void signUpForAppWithExternalIdValidation() throws Exception {
        App app = Tests.getApp(Tests.randomIdentifier(SignUpTest.class), null);
        app.setExternalIdRequiredOnSignup(true);
        
        TestUser admin = TestUserHelper.getSignedInAdmin();
        
        ForSuperadminsApi superadminApi = admin.getClient(ForSuperadminsApi.class);
        superadminApi.createApp(app).execute();

        SignUp signUp = new SignUp()
                .appId(app.getIdentifier())
                .email(IntegTestUtils.makeEmail(SignUpTest.class))
                .password("P@ssword`1");
        AuthenticationApi authApi = admin.getClient(AuthenticationApi.class);
        try {
            try {
                authApi.signUp(signUp).execute();
                fail("Should have thrown exception");
            } catch(InvalidEntityException e) {
                assertEquals("StudyParticipant is invalid: externalId is required", e.getMessage());
            }
        } finally {
            superadminApi.deleteApp(app.getIdentifier(), true).execute();
        }
    }
    
    @Test
    public void signUpWithExternalIdAndNoAccountSucceeds() throws Exception {
        TestUser admin = TestUserHelper.getSignedInAdmin();
        ForAdminsApi adminsApi = admin.getClient(ForAdminsApi.class);
        StudiesApi studiesApi = admin.getClient(StudiesApi.class);
        OrganizationsApi orgsApi = admin.getClient(OrganizationsApi.class);
        ParticipantsApi participantsApi = admin.getClient(ParticipantsApi.class);
        ForSuperadminsApi superadminApi = admin.getClient(ForSuperadminsApi.class);
        AuthenticationApi authApi = admin.getClient(AuthenticationApi.class);

        String extId = Tests.randomIdentifier(SignUpTest.class);

        // In the API app we have two studies created by the initializer for integration tests,
        // and neither is "test", so one will be chosen at random.
        
        SignUp signUp = new SignUp().appId(TEST_APP_ID).dataGroups(ImmutableList.of("test_user"))
                .password(PASSWORD).externalId(extId).sharingScope(NO_SHARING);
        StudyParticipant participant = null;
        
        try {
            authApi.signUp(signUp).execute();
            participant = participantsApi.getParticipantByExternalId(extId, false).execute().body();
            assertEquals(participant.getExternalIds().size(), 1);
            Map.Entry<String, String> reg = Iterables.getFirst(participant.getExternalIds().entrySet(), null);
            assertEquals(extId, reg.getValue());
            assertTrue(ImmutableSet.of(STUDY_ID_1, STUDY_ID_2).contains(reg.getKey()));
        } finally {
            if (participant != null) {
                adminsApi.deleteUser(participant.getId()).execute();  
            }
        }
        
        // In shared there is only one study, "shared", and so that is the one that is used.
        signUp = new SignUp().appId(SHARED_APP_ID).dataGroups(ImmutableList.of("test_user"))
                .password(PASSWORD).externalId(extId).sharingScope(NO_SHARING);
        try {
            authApi.signUp(signUp).execute();
            
            authApi.changeApp(SHARED_SIGNIN).execute();
            participant = participantsApi.getParticipantByExternalId(extId, false).execute().body();
            assertEquals(1, participant.getExternalIds().size());
            // ... however that study is named differently in different environments.
            String extIdValue = participant.getExternalIds().get("shared-study");
            if (extIdValue != null) {
                extIdValue = participant.getExternalIds().get("shared");
            }
            assertEquals(extId, extIdValue);
            adminsApi.deleteUser(participant.getId()).execute();
        } finally {
            if (participant != null) {
                authApi.changeApp(API_SIGNIN).execute();    
            }
        }
        
        // Create an app with a test study only, and it will select that.
        App app = Tests.getApp(Tests.randomIdentifier(SignUpTest.class), null);
        superadminApi.createApp(app).execute();
        
        authApi.changeApp(new SignIn().appId(app.getIdentifier())).execute();
        
        // The superadmin is in the organization Sage Bionetworks, so that needs to exist in
        // this new app when we create a study.
        Organization org = new Organization().identifier(SAGE_ID).name("Sage Bionetworks");
        orgsApi.createOrganization(org).execute();
        
        Study study1 = new Study().identifier("test").name("Test");
        studiesApi.createStudy(study1).execute();
        
        // we need to remove the default study that is created with the app.
        studiesApi.deleteStudy(app.getIdentifier() + "-study", true).execute();

        signUp = new SignUp().appId(app.getIdentifier()).dataGroups(ImmutableList.of("test_user"))
                .password(PASSWORD).externalId(extId).sharingScope(NO_SHARING);
        try {
            authApi.signUp(signUp).execute();
            participant = participantsApi.getParticipantByExternalId(extId, false).execute().body();
            assertEquals(1, participant.getExternalIds().size());
            assertEquals(extId, participant.getExternalIds().get("test"));
        } finally {
            adminsApi.deleteUser(participant.getId()).execute();
        }
        
        // One last scenario: add in another study, and now test should not be chosen
        String study2Id = Tests.randomIdentifier(SignUpTest.class);
        Study study2 = new Study().identifier(study2Id).name("Second Study");
        studiesApi.createStudy(study2).execute();
        
        signUp = new SignUp().appId(app.getIdentifier()).dataGroups(ImmutableList.of("test_user"))
                .password(PASSWORD).externalId(extId).sharingScope(NO_SHARING);
        try {
            authApi.signUp(signUp).execute();
            participant = participantsApi.getParticipantByExternalId(extId, false).execute().body();
            assertEquals(1, participant.getExternalIds().size());
            assertEquals(extId, participant.getExternalIds().get(study2Id));
        } finally {
            adminsApi.deleteUser(participant.getId()).execute();
            studiesApi.deleteStudy(study2Id, true).execute();
            studiesApi.deleteStudy("test", true).execute();
            authApi.changeApp(API_SIGNIN).execute();
            superadminApi.deleteApp(app.getIdentifier(), true).execute();
        }
    }
}
