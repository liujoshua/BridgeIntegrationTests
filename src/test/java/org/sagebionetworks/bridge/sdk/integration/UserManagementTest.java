package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.ClientManager;
import org.sagebionetworks.bridge.rest.api.AuthenticationApi;
import org.sagebionetworks.bridge.rest.exceptions.EntityAlreadyExistsException;
import org.sagebionetworks.bridge.rest.model.SignIn;
import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.api.ParticipantsApi;
import org.sagebionetworks.bridge.rest.api.StudiesApi;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.SignUp;
import org.sagebionetworks.bridge.rest.model.Study;
import org.sagebionetworks.bridge.rest.model.UserSessionInfo;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.util.IntegTestUtils;

public class UserManagementTest {
    
    private static final String API = "api";
    private static final String SHARED = "shared";
    private TestUser admin;
    private TestUser researcher;

    @Before
    public void before() throws Exception {
        admin = TestUserHelper.getSignedInAdmin();

        researcher = TestUserHelper.createAndSignInUser(UserManagementTest.class, true, Role.RESEARCHER);
    }

    @After
    public void after() throws Exception {
        if (researcher != null) {
            researcher.signOutAndDeleteUser(); //must do before admin session signout    
        }
        admin.signOut();
    }

    @Test
    public void canCreateAndSignOutAndDeleteUser() throws Exception {
        String email = IntegTestUtils.makeEmail(UserManagementTest.class);
        String password = "P4ssword";

        SignUp signUp = new SignUp();
        signUp.setStudy(admin.getStudyId());
        signUp.setEmail(email);
        signUp.setConsent(true);
        signUp.setPassword(password);
        
        ForAdminsApi adminApi = admin.getClient(ForAdminsApi.class);
        
        UserSessionInfo userSession = adminApi.createUser(signUp).execute().body();
        assertNotNull(userSession.getId());

        // Can sign in with this user.
        SignIn signIn = new SignIn().study(admin.getStudyId()).email(email).password(password);
        ClientManager newUserClientManager = new ClientManager.Builder().withSignIn(signIn).build();
        AuthenticationApi newUserAuthApi = newUserClientManager.getClient(AuthenticationApi.class);
        newUserAuthApi.signInV4(signIn).execute();

        // Creating the user again throws an EntityAlreadyExists.
        try {
            adminApi.createUser(signUp).execute();
            fail("expected exception");
        } catch (EntityAlreadyExistsException ex) {
            // expected exception
        }

        adminApi.deleteUser(userSession.getId()).execute();

        try {
            ParticipantsApi participantsApi = researcher.getClient(ParticipantsApi.class);
            participantsApi.getParticipantById(userSession.getId(), false).execute();
            fail("Should have thrown an exception");
        } catch(EntityNotFoundException e) {
            // expected exception
        }
    }
    
    @Test
    public void canSignInAsAdminAndChangeStudy() throws Exception {
        // Unfortunately right now, we only have one study, but try all the APIs to make sure you
        // don't get any mistakes
        admin.signOut();
        
        ForAdminsApi adminApi = admin.getClient(ForAdminsApi.class);
        SignIn signIn = new SignIn().study(admin.getStudyId())
                .email(admin.getEmail()).password((admin.getPassword()));
        
        adminApi.adminSignIn(signIn).execute().body();
        Study currentStudy = admin.getClient(StudiesApi.class).getUsersStudy().execute().body();
        assertEquals(API, currentStudy.getIdentifier());
        
        adminApi = admin.getClient(ForAdminsApi.class);
        adminApi.adminChangeStudy(new SignIn().study(SHARED)).execute().body();
        
        currentStudy = admin.getClient(StudiesApi.class).getUsersStudy().execute().body();
        assertEquals(SHARED, currentStudy.getIdentifier());
        
        // now reverse the order
        admin.signOut();
        
        adminApi = admin.getClient(ForAdminsApi.class);
        signIn = new SignIn().study(SHARED)
                .email(admin.getEmail()).password((admin.getPassword()));
        
        adminApi.adminSignIn(signIn).execute().body();
        currentStudy = admin.getClient(StudiesApi.class).getUsersStudy().execute().body();
        assertEquals(SHARED, currentStudy.getIdentifier());
        
        adminApi = admin.getClient(ForAdminsApi.class);
        adminApi.adminChangeStudy(new SignIn().study(API)).execute().body();
        
        currentStudy = admin.getClient(StudiesApi.class).getUsersStudy().execute().body();
        assertEquals(API, currentStudy.getIdentifier());
    }
}
