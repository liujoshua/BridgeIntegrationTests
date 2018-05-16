package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;

import org.joda.time.LocalDate;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sagebionetworks.bridge.rest.ApiClientProvider;
import org.sagebionetworks.bridge.rest.ClientManager;
import org.sagebionetworks.bridge.rest.Config;
import org.sagebionetworks.bridge.rest.RestUtils;
import org.sagebionetworks.bridge.rest.api.AuthenticationApi;
import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.api.IntentToParticipateApi;
import org.sagebionetworks.bridge.rest.api.ParticipantsApi;
import org.sagebionetworks.bridge.rest.model.AccountStatus;
import org.sagebionetworks.bridge.rest.model.AccountSummaryList;
import org.sagebionetworks.bridge.rest.model.ConsentSignature;
import org.sagebionetworks.bridge.rest.model.IntentToParticipate;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.SharingScope;
import org.sagebionetworks.bridge.rest.model.SignIn;
import org.sagebionetworks.bridge.rest.model.SignUp;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.rest.model.UserSessionInfo;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.util.IntegTestUtils;

public class IntentToParticipateTest {
    private TestUser admin;
    private UserSessionInfo session;
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
    @After
    public void deleteUser() throws Exception {
        if (session != null) {
            ForAdminsApi adminApi = admin.getClient(ForAdminsApi.class);
            adminApi.deleteUser(session.getId()).execute();
        }
    }

    @Test
    public void testIntentToParticipate() throws Exception {
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
        
        AuthenticationApi authApi = provider.getClient(AuthenticationApi.class);

        String email = IntegTestUtils.makeEmail(IntentToParticipateTest.class);
        
        SignIn signIn = new SignIn()
                .phone(IntegTestUtils.PHONE)
                .study(IntegTestUtils.STUDY_ID)
                .password(Tests.PASSWORD);

        SignUp signUp = new SignUp()
                .study(IntegTestUtils.STUDY_ID)
                .phone(IntegTestUtils.PHONE)
                .email(email)
                .password(Tests.PASSWORD)
                .checkForConsent(true);
        authApi.signUp(signUp).execute().body();
        
        // We need to enable the user without verifying the phone, so the next part of the test works.
        ParticipantsApi participantsApi = researcher.getClient(ParticipantsApi.class);
        AccountSummaryList list = participantsApi.getParticipants(0, 5, email, null, null, null).execute().body();
        StudyParticipant participant = participantsApi.getParticipantById(list.getItems().get(0).getId(), false).execute().body();
        
        participant.setStatus(AccountStatus.ENABLED);
        participantsApi.updateParticipant(participant.getId(), participant).execute();
        
        session = authApi.signInV4(signIn).execute().body();
        assertEquals(SharingScope.ALL_QUALIFIED_RESEARCHERS, session.getSharingScope());
    }
    
}
