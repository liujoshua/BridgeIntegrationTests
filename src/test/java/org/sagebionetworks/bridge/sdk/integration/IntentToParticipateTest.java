package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.joda.time.LocalDate;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sagebionetworks.bridge.rest.ApiClientProvider;
import org.sagebionetworks.bridge.rest.ClientManager;
import org.sagebionetworks.bridge.rest.Config;
import org.sagebionetworks.bridge.rest.RestUtils;
import org.sagebionetworks.bridge.rest.api.AuthenticationApi;
import org.sagebionetworks.bridge.rest.api.IntentToParticipateApi;
import org.sagebionetworks.bridge.rest.model.ConsentSignature;
import org.sagebionetworks.bridge.rest.model.ConsentStatus;
import org.sagebionetworks.bridge.rest.model.IntentToParticipate;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.SharingScope;
import org.sagebionetworks.bridge.rest.model.SignUp;
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
        researcher = TestUserHelper.createAndSignInUser(IntentToParticipateTest.class, false, Role.ADMIN, Role.RESEARCHER);
        IntegTestUtils.deletePhoneUser(researcher);
    }
    
    @After
    public void deleteResearcher() throws Exception {
        if (researcher != null) {
            researcher.signOutAndDeleteUser();
        }
    }

    @Test
    public void testIntentToParticipate() throws Exception {
        TestUser user = null;
        try {
            ConsentSignature sig = new ConsentSignature()
                    .name("Test User")
                    .scope(SharingScope.ALL_QUALIFIED_RESEARCHERS)
                    .birthdate(LocalDate.parse("1980-01-01"));
            
            IntentToParticipate intent = new IntentToParticipate()
                    .phone(IntegTestUtils.PHONE)
                    .studyId(IntegTestUtils.STUDY_ID)
                    .subpopGuid(IntegTestUtils.STUDY_ID)
                    .osName("iPhone")
                    .consentSignature(sig);
            
            Config config = new Config();
            String baseUrl = ClientManager.getUrl(config.getEnvironment());
            String clientInfo = RestUtils.getUserAgent(admin.getClientManager().getClientInfo());
            String lang = RestUtils.getAcceptLanguage(admin.getClientManager().getAcceptedLanguages());
            
            ApiClientProvider provider = new ApiClientProvider(baseUrl, clientInfo, lang, IntegTestUtils.STUDY_ID);
            
            IntentToParticipateApi intentApi = provider.getClient(IntentToParticipateApi.class);
            intentApi.submitIntentToParticipate(intent).execute();
            
            SignUp signUp = new SignUp()
                    .study(IntegTestUtils.STUDY_ID)
                    .phone(IntegTestUtils.PHONE)
                    .password(Tests.PASSWORD)
                    .checkForConsent(true);
            user = new TestUserHelper.Builder(IntentToParticipate.class)
                .withSignUp(signUp)
                .withConsentUser(false) // important, the ITP must do this.
                .createUser();
            
            user.signOut();
            AuthenticationApi authApi = provider.getClient(AuthenticationApi.class);
            
            // This does not throw a consent exception.
            UserSessionInfo session = authApi.signInV4(user.getSignIn()).execute().body();
            assertEquals(SharingScope.ALL_QUALIFIED_RESEARCHERS, session.getSharingScope());
            
            ConsentStatus status = session.getConsentStatuses().get(IntegTestUtils.STUDY_ID);
            assertTrue(status.isConsented());
        } finally {
            if (user != null) {
                user.signOutAndDeleteUser();
            }
        }
    }
    
}
