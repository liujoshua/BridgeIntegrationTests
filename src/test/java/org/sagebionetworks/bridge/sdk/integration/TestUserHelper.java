package org.sagebionetworks.bridge.sdk.integration;

import static com.google.common.base.Preconditions.checkNotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.common.collect.Lists;

import org.sagebionetworks.bridge.rest.ApiClientProvider;

import org.sagebionetworks.bridge.rest.ClientManager;
import org.sagebionetworks.bridge.rest.Config;
import org.sagebionetworks.bridge.rest.RestUtils;
import org.sagebionetworks.bridge.rest.api.AuthenticationApi;
import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.exceptions.BridgeSDKException;
import org.sagebionetworks.bridge.rest.exceptions.ConsentRequiredException;
import org.sagebionetworks.bridge.rest.model.ClientInfo;
import org.sagebionetworks.bridge.rest.model.Phone;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.SignIn;
import org.sagebionetworks.bridge.rest.model.SignUp;
import org.sagebionetworks.bridge.rest.model.UserSessionInfo;

public class TestUserHelper {
    private static final Config CONFIG = new Config();

    private static final List<String> LANGUAGES = Lists.newArrayList("en");
    private static final String PASSWORD = "P4ssword!";
    private static final ClientInfo CLIENT_INFO = new ClientInfo().appName(Tests.APP_NAME).appVersion(0);
    
    public static class TestUser {
        private SignIn signIn;
        private ClientManager manager;
        private String userId; // try and hold onto this for the sake of cleaning up tests

        public TestUser(SignIn signIn, ClientManager manager, String userId) {
            checkNotNull(signIn.getStudy());
            checkNotNull(manager);
            this.signIn = signIn;
            this.manager = manager;
            this.userId = userId; // if this is null, we will try and get it on a sign in
        }
        public UserSessionInfo getSession() {
            return manager.getSessionOfClients();
        }
        public String getEmail() {
            return signIn.getEmail();
        }
        public Phone getPhone() {
            return signIn.getPhone();
        }
        public String getPassword() {
            return signIn.getPassword();
        }
        public List<Role> getRoles() {
            return (getSession() == null) ? null : getSession().getRoles();
        }
        public String getDefaultSubpopulation() {
            return signIn.getStudy();
        }
        public String getStudyId() {
            return signIn.getStudy();
        }
        public <T> T getClient(Class<T> service) {
            return manager.getClient(service);
        }
        public UserSessionInfo signInAgain() {
            AuthenticationApi authApi = manager.getClient(AuthenticationApi.class);
            try {
                UserSessionInfo session = authApi.signInV4(getSignIn()).execute().body();
                userId = session.getId();
                return manager.getSessionOfClients();
            } catch(IOException ioe) {
                throw new BridgeSDKException(ioe.getMessage(), ioe);
            }
        }
        public void signOut() throws IOException {
            AuthenticationApi authApi = manager.getClient(AuthenticationApi.class);
            authApi.signOut().execute();
        }
        public void signOutAndDeleteUser() throws IOException {
            if (getSession() != null) {
                this.signOut();
            }
            if (userId != null) {
                // This should sign the admin manager in automatically.
                ClientManager adminManager = new ClientManager.Builder().withSignIn(CONFIG.getAdminSignIn())
                        .withConfig(CONFIG).withClientInfo(manager.getClientInfo()).withAcceptLanguage(LANGUAGES).build();
                ForAdminsApi adminsApi = adminManager.getClient(ForAdminsApi.class);
                adminsApi.deleteUser(userId).execute();
            }
        }
        public SignIn getSignIn() {
            return signIn;
        }
        public ClientManager getClientManager() {
            return manager;
        }
        public Config getConfig() {
            return manager.getConfig();
        }
        public void setClientInfo(ClientInfo clientInfo) {
            ClientManager man = new ClientManager.Builder()
                    .withClientInfo(clientInfo)
                    .withSignIn(signIn)
                    .withConfig(manager.getConfig())
                    .withAcceptLanguage(LANGUAGES).build();
            this.manager = man;
        }
    }
    public static TestUser getSignedInAdmin() {
        return getSignedInUser(CONFIG.getAdminSignIn());
    }

    // Returns a pre-configured Developer account in the API study.
    public static TestUser getSignedInApiDeveloper() {
        String email = CONFIG.fromProperty(Config.Props.API_DEVELOPER_EMAIL);
        String password = CONFIG.fromProperty(Config.Props.API_DEVELOPER_PASSWORD);
        String studyId = CONFIG.fromProperty(Config.Props.API_DEVELOPER_STUDY);
        SignIn signIn = new SignIn().email(email).password(password).study(studyId);
        return getSignedInUser(signIn);
    }

    // Returns a pre-configured Developer account in the Shared study.
    public static TestUser getSignedInSharedDeveloper() {
        String email = CONFIG.fromProperty(Config.Props.SHARED_DEVELOPER_EMAIL);
        String password = CONFIG.fromProperty(Config.Props.SHARED_DEVELOPER_PASSWORD);
        String studyId = CONFIG.fromProperty(Config.Props.SHARED_DEVELOPER_STUDY);
        SignIn signIn = new SignIn().email(email).password(password).study(studyId);
        return getSignedInUser(signIn);
    }

    // Returns the test user for the given sign-in credentials.
    public static TestUser getSignedInUser(SignIn signIn) {
        ClientManager manager = new ClientManager.Builder().withSignIn(signIn).withConfig(CONFIG)
                .withClientInfo(CLIENT_INFO).withAcceptLanguage(LANGUAGES).build();
        TestUser user = new TestUser(signIn, manager, null);
        user.signInAgain();
        return user;
    }

    public static <T> T getNonAuthClient(Class<T> service, String studyId) {
        ApiClientProvider provider = new ApiClientProvider(ClientManager.getUrl(CONFIG.getEnvironment()),
                RestUtils.getUserAgent(CLIENT_INFO), RestUtils.getAcceptLanguage(LANGUAGES), studyId);

        return provider.getClient(service);
    }

    public static TestUser createAndSignInUser(Class<?> cls, boolean consentUser, Role... roles) throws IOException {
        return new TestUserHelper.Builder(cls).withRoles(roles).withConsentUser(consentUser).createAndSignInUser();
    }
    public static TestUser createAndSignInUser(Class<?> cls, boolean consentUser, SignUp signUp) throws IOException {
        return new TestUserHelper.Builder(cls).withConsentUser(consentUser).withSignUp(signUp).createAndSignInUser();
    }
    
    public static class Builder {
        private Class<?> cls;
        private boolean consentUser;
        private SignUp signUp;
        private boolean setPassword = true;
        private ClientInfo clientInfo;
        private Set<Role> roles = new HashSet<>();
        
        public Builder withConsentUser(boolean consentUser) {
            this.consentUser = consentUser;
            return this;
        }
        public Builder withSignUp(SignUp signUp) {
            this.signUp = signUp;
            return this;
        }
        public Builder withClientInfo(ClientInfo clientInfo) {
            this.clientInfo = clientInfo;
            return this;
        }
        public Builder withRoles(Role...roles) {
            for (Role role : roles) {
                this.roles.add(role);
            }
            return this;
        }
        public Builder withSetPassword(boolean setPassword) {
            this.setPassword = setPassword;
            return this;
        }

        public Builder(Class<?> cls) {
            checkNotNull(cls);
            this.cls = cls;
        }
        
        public TestUser createUser() throws IOException {
            if (clientInfo == null) {
                clientInfo = CLIENT_INFO;
            }
            TestUser admin = getSignedInAdmin();
            ForAdminsApi adminsApi = admin.getClient(ForAdminsApi.class);
            
            Set<Role> rolesList = new HashSet<>();
            if (signUp != null && signUp.getRoles() != null) {
                rolesList.addAll(signUp.getRoles());
            }
            if (!roles.isEmpty()) {
                rolesList.addAll(roles);
            }

            // For email address, we don't want consent emails to bounce or SES will get mad at us. All test user email
            // addresses should be in the form bridge-testing+[semi-unique token]@sagebase.org. This directs all test
            // email to bridge-testing@sagebase.org.
            String emailAddress = Tests.makeEmail(cls);

            if (signUp == null) {
                signUp = new SignUp();
            }
            // If we haven't specified either identifier, provide an email address
            if (signUp.getEmail() == null && signUp.getPhone() == null) {
                signUp.email(emailAddress);
            }
            if (setPassword) {
                signUp.setPassword(PASSWORD);    
            }
            signUp.setStudy(Tests.STUDY_ID);
            signUp.setRoles(new ArrayList<>(rolesList));
            signUp.setConsent(consentUser);
            UserSessionInfo info = adminsApi.createUser(signUp).execute().body();

            SignIn signIn = new SignIn().study(signUp.getStudy()).phone(signUp.getPhone()).email(signUp.getEmail())
                    .password(signUp.getPassword());
            
            ClientManager manager = new ClientManager.Builder().withConfig(admin.getConfig()).withSignIn(signIn)
                    .withClientInfo(clientInfo).withAcceptLanguage(LANGUAGES).build();
            return new TestUser(signIn, manager, info.getId());
        }
        
        public TestUser createAndSignInUser() throws IOException {
            TestUser testUser = createUser();
            
            try {
                testUser.signInAgain();
            } catch (ConsentRequiredException e) {
                if (consentUser) {
                    // If there's no consent but we're expecting one, that's an error.
                    throw e;
                }
            } catch (RuntimeException ex) {
                // Clean up the account, so we don't end up with a bunch of leftover accounts.
                if (testUser.getSession() != null) {
                    TestUser admin = getSignedInAdmin();
                    ForAdminsApi adminsApi = admin.getClient(ForAdminsApi.class);
                    
                    adminsApi.deleteUser(testUser.getSession().getId()).execute();
                }
                throw new BridgeSDKException(ex.getMessage(), ex);
            }
            return testUser;
        }
    }
}
