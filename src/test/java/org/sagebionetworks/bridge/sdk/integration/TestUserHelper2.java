package org.sagebionetworks.bridge.sdk.integration;

import static com.google.common.base.Preconditions.checkNotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.sagebionetworks.bridge.sdk.ClientManager;
import org.sagebionetworks.bridge.sdk.Config;
import org.sagebionetworks.bridge.sdk.rest.api.AuthenticationApi;
import org.sagebionetworks.bridge.sdk.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.sdk.rest.exceptions.BridgeSDKException;
import org.sagebionetworks.bridge.sdk.rest.exceptions.ConsentRequiredException;
import org.sagebionetworks.bridge.sdk.rest.model.EmptyPayload;
import org.sagebionetworks.bridge.sdk.rest.model.Role;
import org.sagebionetworks.bridge.sdk.rest.model.SignIn;
import org.sagebionetworks.bridge.sdk.rest.model.SignUp;
import org.sagebionetworks.bridge.sdk.rest.model.UserSessionInfo;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

public class TestUserHelper2 {

    private static final SignIn ADMIN_SIGN_IN = new Config().getAdminSignIn();
    private static final EmptyPayload EMPTY_PAYLOAD = new EmptyPayload();
    private static final String PASSWORD = "P4ssword";
    
    public static class TestUser {
        private SignIn signIn;
        private ClientManager manager;
        private UserSessionInfo userSession;

        public TestUser(SignIn signIn) {
            this.signIn = signIn;
            this.manager = new ClientManager.Builder().withSignIn(signIn).build();
        }
        public UserSessionInfo getSession() {
            return userSession;
        }
        public String getEmail() {
            return signIn.getEmail();
        }
        public String getPassword() {
            return signIn.getPassword();
        }
        public List<Role> getRoles() {
            return userSession.getRoles();
        }
        public String getDefaultSubpopulation() {
            return signIn.getStudy();
        }
        public final <T> T getAuthenticatedClient(Class<T> service) {
            return manager.getAuthenticatedClient(service);
        }
        public UserSessionInfo signInAgain() {
            AuthenticationApi authApi = manager.getUnauthenticatedClient(AuthenticationApi.class);
            try {
                userSession = authApi.signIn(getSignIn()).execute().body();
            } catch (ConsentRequiredException e) {
                userSession = e.getSession();
                throw e;
            } catch(IOException ioe) {
                throw new BridgeSDKException(ioe.getMessage(), ioe);
            }
            return userSession;
        }
        public void signOut() {
            AuthenticationApi authApi = manager.getAuthenticatedClient(AuthenticationApi.class);
            authApi.signOut(EMPTY_PAYLOAD);
            userSession.setAuthenticated(false);
        }
        public void signOutAndDeleteUser() {
            this.signOut();

            ClientManager adminManager = new ClientManager.Builder().withSignIn(ADMIN_SIGN_IN).build();
            ForAdminsApi adminsApi = adminManager.getAuthenticatedClient(ForAdminsApi.class);
            adminsApi.deleteUser(userSession.getId());
        }
        public SignIn getSignIn() {
            return signIn;
        }
    }
    public static TestUser getSignedInAdmin() {
        TestUser adminUser = new TestUser(ADMIN_SIGN_IN);
        adminUser.signInAgain();
        return adminUser;
    }
    public static TestUser createAndSignInUser(Class<?> cls, boolean consentUser, Role... roles) throws IOException {
        SignUp signUp = new SignUp();
        List<Role> rolesList = Lists.newArrayList();
        if (roles != null) {
            for (Role role : roles) {
                rolesList.add(role);
            }
        }
        signUp.setRoles(rolesList);
        
        return createAndSignInUser(cls, consentUser, signUp);
    }
    public static TestUser createAndSignInUser(Class<?> cls, boolean consentUser, SignUp signUp) throws IOException {
        checkNotNull(cls);
        
        ClientManager adminManager = new ClientManager.Builder().withSignIn(ADMIN_SIGN_IN).build();
        ForAdminsApi adminsApi = adminManager.getAuthenticatedClient(ForAdminsApi.class);

        Set<Role> rolesList = Sets.newHashSet();
        if (signUp != null && signUp.getRoles() != null) {
            rolesList.addAll(signUp.getRoles());
        }

        // For email address, we don't want consent emails to bounce or SES will get mad at us. All test user email
        // addresses should be in the form bridge-testing+[semi-unique token]@sagebase.org. This directs all test
        // email to bridge-testing@sagebase.org.
        String emailAddress = Tests.makeEmail(cls);

        if (signUp == null) {
            signUp = new SignUp();
        }
        if (signUp.getEmail() == null) {
            signUp.email(emailAddress);
        }
        signUp.setRoles(new ArrayList<>(rolesList));
        signUp.setPassword(PASSWORD);
        signUp.setConsent(consentUser);
        adminsApi.createUser(signUp).execute().body();

        SignIn signIn = new SignIn().study(signUp.getStudy()).email(signUp.getEmail()).password(signUp.getPassword());
        TestUser testUser = new TestUser(signIn);
        
        UserSessionInfo userSession = null;
        try {
            try {
                userSession = testUser.signInAgain();
            } catch (ConsentRequiredException e) {
                userSession = e.getSession();
                if (consentUser) {
                    // If there's no consent but we're expecting one, that's an error.
                    throw e;
                }
            }
            return testUser;
        } catch (RuntimeException ex) {
            // Clean up the account, so we don't end up with a bunch of leftover accounts.
            if (userSession != null) {
                adminsApi.deleteUser(userSession.getId());
            }
            throw new BridgeSDKException(ex.getMessage(), ex);
        }
    }
}
