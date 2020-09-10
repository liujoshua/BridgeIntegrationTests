package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.sagebionetworks.bridge.util.IntegTestUtils.TEST_APP_ID;

import com.google.common.collect.ImmutableMap;
import org.joda.time.DateTime;
import org.joda.time.LocalDate;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sagebionetworks.bridge.rest.ApiClientProvider;
import org.sagebionetworks.bridge.rest.ClientManager;
import org.sagebionetworks.bridge.rest.Config;
import org.sagebionetworks.bridge.rest.RestUtils;
import org.sagebionetworks.bridge.rest.api.AuthenticationApi;
import org.sagebionetworks.bridge.rest.api.ForSuperadminsApi;
import org.sagebionetworks.bridge.rest.api.IntentToParticipateApi;
import org.sagebionetworks.bridge.rest.api.InternalApi;
import org.sagebionetworks.bridge.rest.model.App;
import org.sagebionetworks.bridge.rest.model.ConsentSignature;
import org.sagebionetworks.bridge.rest.model.ConsentStatus;
import org.sagebionetworks.bridge.rest.model.IntentToParticipate;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.SharingScope;
import org.sagebionetworks.bridge.rest.model.SignUp;
import org.sagebionetworks.bridge.rest.model.SmsMessage;
import org.sagebionetworks.bridge.rest.model.SmsType;
import org.sagebionetworks.bridge.rest.model.UserSessionInfo;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.util.IntegTestUtils;

public class IntentToParticipateTest {
    private TestUser admin;
    private TestUser researcher;
    
    @Before
    public void before() throws Exception {
        admin = TestUserHelper.getSignedInAdmin();
        researcher = TestUserHelper.createAndSignInUser(IntentToParticipateTest.class, false, Role.RESEARCHER);
        IntegTestUtils.deletePhoneUser(researcher);

        // Add dummy install link to trigger Intent SMS.
        ForSuperadminsApi superadminApi = admin.getClient(ForSuperadminsApi.class);
        App app = superadminApi.getApp(TEST_APP_ID).execute().body();
        app.setInstallLinks(ImmutableMap.of("Universal", "http://example.com/"));
        superadminApi.updateApp(app.getIdentifier(), app).execute();
    }
    
    @After
    public void deleteResearcher() throws Exception {
        if (researcher != null) {
            researcher.signOutAndDeleteUser();
        }
    }

    @Test
    public void testIntentToParticipateWithPhone() throws Exception {
        TestUser user = null;
        try {
            ConsentSignature sig = new ConsentSignature()
                    .name("Test User")
                    .scope(SharingScope.ALL_QUALIFIED_RESEARCHERS)
                    .birthdate(LocalDate.parse("1980-01-01"));
            
            IntentToParticipate intent = new IntentToParticipate()
                    .phone(IntegTestUtils.PHONE)
                    .appId(TEST_APP_ID)
                    .subpopGuid(TEST_APP_ID)
                    .osName("iPhone")
                    .consentSignature(sig);
            
            Config config = new Config();
            String baseUrl = ClientManager.getUrl(config.getEnvironment());
            String clientInfo = RestUtils.getUserAgent(admin.getClientManager().getClientInfo());
            String lang = RestUtils.getAcceptLanguage(admin.getClientManager().getAcceptedLanguages());
            
            ApiClientProvider provider = new ApiClientProvider(baseUrl, clientInfo, lang, TEST_APP_ID);
            
            IntentToParticipateApi intentApi = provider.getClient(IntentToParticipateApi.class);
            intentApi.submitIntentToParticipate(intent).execute();
            
            SignUp signUp = new SignUp()
                    .appId(TEST_APP_ID)
                    .phone(IntegTestUtils.PHONE)
                    .password(Tests.PASSWORD)
                    .checkForConsent(true);
            user = new TestUserHelper.Builder(IntentToParticipate.class)
                .withSignUp(signUp)
                .withConsentUser(false) // important, the ITP must do this.
                .createUser();

            // Verify message logs contains the expected message. We do this after we create the account, but before
            // we sign-in, because intent is checked on sign-in and sends another SMS message with the consent doc (if
            // the app is configured to do so).
            SmsMessage message = admin.getClient(InternalApi.class).getMostRecentSmsMessage(user.getUserId()).execute()
                    .body();
            assertEquals(IntegTestUtils.PHONE.getNumber(), message.getPhoneNumber());
            assertNotNull(message.getMessageId());
            assertEquals(SmsType.TRANSACTIONAL, message.getSmsType());
            assertEquals(TEST_APP_ID, message.getAppId());

            // Message body isn't constrained by the test, so just check that it exists.
            assertNotNull(message.getMessageBody());

            // Clock skew on Jenkins can be known to go as high as 10 minutes. For a robust test, simply check that the
            // message was sent within the last hour.
            assertTrue(message.getSentOn().isAfter(DateTime.now().minusHours(1)));

            // Because this message was sent before the account was created, it is not tagged with a health code.
            assertNull(message.getHealthCode());

            // This does not throw a consent exception.
            AuthenticationApi authApi = user.getClient(AuthenticationApi.class);
            UserSessionInfo session = authApi.signInV4(user.getSignIn()).execute().body();
            assertEquals(SharingScope.ALL_QUALIFIED_RESEARCHERS, session.getSharingScope());
            
            ConsentStatus status = session.getConsentStatuses().get(TEST_APP_ID);
            assertTrue(status.isConsented());
        } finally {
            if (user != null) {
                user.signOutAndDeleteUser();
            }
        }
    }
    
    @Test
    public void testIntentToParticipateWithEmail() throws Exception {
        String email = IntegTestUtils.makeEmail(IntentToParticipateTest.class);
        TestUser user = null;
        try {
            ConsentSignature sig = new ConsentSignature()
                    .name("Test User")
                    .scope(SharingScope.ALL_QUALIFIED_RESEARCHERS)
                    .birthdate(LocalDate.parse("1980-01-01"));
            
            IntentToParticipate intent = new IntentToParticipate()
                    .email(email)
                    .appId(TEST_APP_ID)
                    .subpopGuid(TEST_APP_ID)
                    .osName("iPhone")
                    .consentSignature(sig);
            
            Config config = new Config();
            String baseUrl = ClientManager.getUrl(config.getEnvironment());
            String clientInfo = RestUtils.getUserAgent(admin.getClientManager().getClientInfo());
            String lang = RestUtils.getAcceptLanguage(admin.getClientManager().getAcceptedLanguages());
            
            ApiClientProvider provider = new ApiClientProvider(baseUrl, clientInfo, lang, TEST_APP_ID);
            
            IntentToParticipateApi intentApi = provider.getClient(IntentToParticipateApi.class);
            intentApi.submitIntentToParticipate(intent).execute();
            
            SignUp signUp = new SignUp()
                    .appId(TEST_APP_ID)
                    .email(email)
                    .password(Tests.PASSWORD)
                    .checkForConsent(true);
            user = new TestUserHelper.Builder(IntentToParticipate.class)
                .withSignUp(signUp)
                .withConsentUser(false) // important, the ITP must do this.
                .createUser();
            
            // This does not throw a consent exception.
            AuthenticationApi authApi = provider.getClient(AuthenticationApi.class);
            UserSessionInfo session = authApi.signInV4(user.getSignIn()).execute().body();
            assertEquals(SharingScope.ALL_QUALIFIED_RESEARCHERS, session.getSharingScope());
            
            ConsentStatus status = session.getConsentStatuses().get(TEST_APP_ID);
            assertTrue(status.isConsented());
        } finally {
            if (user != null) {
                user.signOutAndDeleteUser();
            }
        }
    }    
    
}
