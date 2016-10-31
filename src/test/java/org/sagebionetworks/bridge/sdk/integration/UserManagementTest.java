package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.sdk.integration.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.sdk.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.sdk.rest.api.ParticipantsApi;
import org.sagebionetworks.bridge.sdk.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.sdk.rest.model.Role;
import org.sagebionetworks.bridge.sdk.rest.model.SignUp;
import org.sagebionetworks.bridge.sdk.rest.model.UserSessionInfo;

public class UserManagementTest {

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
        String email = Tests.makeEmail(UserManagementTest.class);
        String password = "P4ssword";

        SignUp signUp = new SignUp();
        signUp.setStudy(admin.getStudyId());
        signUp.setEmail(email);
        signUp.setConsent(true);
        signUp.setPassword(password);
        
        ForAdminsApi adminApi = admin.getClient(ForAdminsApi.class);
        
        UserSessionInfo userSession = adminApi.createUser(signUp).execute().body();
        assertNotNull(userSession.getId());
        
        adminApi.deleteUser(userSession.getId()).execute();

        try {
            ParticipantsApi participantsApi = researcher.getClient(ParticipantsApi.class);
            participantsApi.getParticipant(userSession.getId()).execute();
            fail("Should have thrown an exception");
        } catch(EntityNotFoundException e) {
            
        }
    }

}
