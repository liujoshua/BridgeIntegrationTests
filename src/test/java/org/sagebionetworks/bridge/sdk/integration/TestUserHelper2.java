package org.sagebionetworks.bridge.sdk.integration;

import static com.google.common.base.Preconditions.checkNotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.sagebionetworks.bridge.sdk.ClientManager;
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

    private static final ClientManager MANAGER = new ClientManager();
    private static final EmptyPayload EMPTY_PAYLOAD = new EmptyPayload();
    private static final String PASSWORD = "P4ssword";
    
    public static class TestUser {
        private UserSessionInfo userSession;

        public TestUser(UserSessionInfo session) {
            this.userSession = session;
        }
        public UserSessionInfo getSession() {
            return userSession;
        }
        public String getEmail() {
            return userSession.getEmail();
        }
        public String getPassword() {
            return PASSWORD;
        }
        public List<Role> getRoles() {
            return userSession.getRoles();
        }
        public String getDefaultSubpopulation() {
            return Tests.TEST_KEY;
        }
        public final <T> T getAuthenticatedClient(Class<T> service) {
            return MANAGER.getAuthenticatedClient(service, getSignIn());
        }
        public UserSessionInfo signInAgain() {
            AuthenticationApi authApi = MANAGER.getUnauthenticatedClient(AuthenticationApi.class);
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
            AuthenticationApi authApi = MANAGER.getAuthenticatedClient(AuthenticationApi.class, getSignIn());
            authApi.signOut(EMPTY_PAYLOAD);
            userSession.setAuthenticated(false);
        }
        public void signOutAndDeleteUser() {
            this.signOut();

            ForAdminsApi adminsApi = MANAGER.getAuthenticatedClient(ForAdminsApi.class,
                    MANAGER.getConfig().getAdminSignIn());
            adminsApi.deleteUser(userSession.getId());
        }
        public SignIn getSignIn() {
            return new SignIn().study(Tests.TEST_KEY).email(userSession.getEmail()).password(PASSWORD);
        }
    }
    public static TestUser getSignedInAdmin() {
        SignIn authSignIn = MANAGER.getConfig().getAdminSignIn();
        AuthenticationApi authApi = MANAGER.getUnauthenticatedClient(AuthenticationApi.class);
        try {
            UserSessionInfo session = authApi.signIn(authSignIn).execute().body();
            return new TestUser(session);
        } catch(IOException ioe) {
            throw new BridgeSDKException(ioe.getMessage(), ioe);
        }
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
        
        ForAdminsApi adminsApi = MANAGER.getAuthenticatedClient(ForAdminsApi.class,
                MANAGER.getConfig().getAdminSignIn());

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

        SignIn signIn = new SignIn().study(Tests.TEST_KEY).email(emailAddress).password(PASSWORD);
        UserSessionInfo userSession = null;
        try {
            try {
                AuthenticationApi authApi = MANAGER.getAuthenticatedClient(AuthenticationApi.class, signIn);
                userSession = authApi.signIn(signIn).execute().body();
                //AuthenticationApi authApi = MANAGER.getUnauthenticatedClient(AuthenticationApi.class);
                //userSession = authApi.signIn(signIn).execute().body();
            } catch (ConsentRequiredException e) {
                userSession = e.getSession();
                if (consentUser) {
                    // If there's no consent but we're expecting one, that's an error.
                    throw e;
                }
            }
            return new TestUser(userSession);
        } catch (RuntimeException ex) {
            // Clean up the account, so we don't end up with a bunch of leftover accounts.
            if (userSession != null) {
                adminsApi.deleteUser(userSession.getId());
            }
            throw new BridgeSDKException(ex.getMessage(), ex);
        }
    }
}
