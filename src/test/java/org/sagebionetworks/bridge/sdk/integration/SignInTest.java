package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.sagebionetworks.bridge.rest.model.Role.DEVELOPER;
import static org.sagebionetworks.bridge.rest.model.Role.RESEARCHER;
import static org.sagebionetworks.bridge.rest.model.SharingScope.ALL_QUALIFIED_RESEARCHERS;
import static org.sagebionetworks.bridge.sdk.integration.Tests.SUBSTUDY_ID_1;
import static org.sagebionetworks.bridge.util.IntegTestUtils.TEST_APP_ID;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.sagebionetworks.bridge.rest.ClientManager;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.exceptions.UnauthorizedException;
import org.sagebionetworks.bridge.rest.model.AccountStatus;
import org.sagebionetworks.bridge.rest.model.SignIn;
import org.sagebionetworks.bridge.rest.api.AuthenticationApi;
import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.rest.api.ParticipantsApi;
import org.sagebionetworks.bridge.rest.model.AccountSummary;
import org.sagebionetworks.bridge.rest.model.AccountSummaryList;
import org.sagebionetworks.bridge.rest.model.ExternalIdentifier;
import org.sagebionetworks.bridge.rest.model.SharingScope;
import org.sagebionetworks.bridge.rest.model.SignUp;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.rest.model.UserSessionInfo;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.util.IntegTestUtils;

import java.util.List;
import java.util.Map;

@Category(IntegrationSmokeTest.class)
public class SignInTest {
    private static final String PASSWORD = "P@ssword`1";
    
    private TestUser developer;
    private TestUser researcher;
    private TestUser user;
    
    @Before
    public void before() throws Exception {
        developer = TestUserHelper.createAndSignInUser(SignInTest.class, false, DEVELOPER);
        researcher = TestUserHelper.createAndSignInUser(SignInTest.class, false, RESEARCHER);
        user = TestUserHelper.createAndSignInUser(SignInTest.class, true);
    }
    
    @After
    public void after() throws Exception {
        if (user != null) {
            user.signOutAndDeleteUser();
        }
        if (researcher != null) {
            researcher.signOutAndDeleteUser();
        }
        if (developer != null) {
            developer.signOutAndDeleteUser();
        }
    }

    @Test
    public void canGetDataGroups() throws Exception {
        List<String> dataGroups = Lists.newArrayList("sdk-int-1");
        
        ForConsentedUsersApi usersApi = user.getClient(ForConsentedUsersApi.class);

        StudyParticipant participant = new StudyParticipant();
        participant.setDataGroups(dataGroups);

        usersApi.updateUsersParticipantRecord(participant).execute();
        
        user.signOut();
        user.signInAgain();
        
        UserSessionInfo session = user.getSession();
        assertEquals(dataGroups, session.getDataGroups());
    }
    
    @SuppressWarnings("deprecation")
    @Test
    public void createComplexUser() throws Exception {
        AuthenticationApi authApi = researcher.getClient(AuthenticationApi.class);
        
        ExternalIdentifier externalId = Tests.createExternalId(SignInTest.class, developer, SUBSTUDY_ID_1);
        
        Map<String,String> map = Maps.newHashMap();
        map.put("can_be_recontacted", "true");

        String email = IntegTestUtils.makeEmail(SignInTest.class);

        SignUp signUp = new SignUp();
        signUp.setAppId(researcher.getAppId());
        signUp.setFirstName("First Name");
        signUp.setLastName("Last Name");
        signUp.setEmail(email);
        signUp.setPassword(PASSWORD);
        signUp.setExternalId(externalId.getIdentifier());
        signUp.setSharingScope(ALL_QUALIFIED_RESEARCHERS);
        signUp.setNotifyByEmail(true);
        signUp.setDataGroups(Lists.newArrayList("group1"));
        signUp.setLanguages(Lists.newArrayList("en"));
        signUp.setAttributes(map);
                
        authApi.signUp(signUp).execute();

        ParticipantsApi participantsApi = researcher.getClient(ParticipantsApi.class);
        
        AccountSummaryList summaries = participantsApi.getParticipants(0, 10, signUp.getEmail(), null, null, null).execute()
                .body();
        assertEquals(1, summaries.getItems().size());
        
        AccountSummary summary = summaries.getItems().get(0);
        StudyParticipant retrieved = participantsApi.getParticipantById(summary.getId(), false).execute().body();
        assertEquals("First Name", retrieved.getFirstName());
        assertEquals("Last Name", retrieved.getLastName());
        assertTrue(retrieved.getExternalIds().values().contains(externalId.getIdentifier()));
        assertEquals(signUp.getEmail(), retrieved.getEmail());
        assertEquals(SharingScope.ALL_QUALIFIED_RESEARCHERS, retrieved.getSharingScope());
        assertTrue(retrieved.isNotifyByEmail());
        assertEquals(Lists.newArrayList("group1"), retrieved.getDataGroups());
        assertEquals(Lists.newArrayList("en"), retrieved.getLanguages());
        assertEquals("true", retrieved.getAttributes().get("can_be_recontacted"));
        
        Tests.deleteExternalId(externalId);
        
        TestUser admin = TestUserHelper.getSignedInAdmin();
        admin.getClient(ForAdminsApi.class).deleteUser(retrieved.getId()).execute();
    }

    @Test(expected = EntityNotFoundException.class)
    public void signInNoAccount() throws Exception {
        // Generate random email, but don't create the account.
        String email = IntegTestUtils.makeEmail(SignInTest.class);
        SignIn signIn = new SignIn().appId(TEST_APP_ID).email(email).password(PASSWORD);

        ClientManager newUserClientManager = new ClientManager.Builder().withSignIn(signIn).build();
        AuthenticationApi newUserAuthApi = newUserClientManager.getClient(AuthenticationApi.class);
        newUserAuthApi.signInV4(signIn).execute();
    }

    @Test(expected = EntityNotFoundException.class)
    public void signInBadPassword() throws Exception {
        // To prevent email enumeration attack, this throws a 404 not found.
        SignIn signIn = new SignIn().appId(TEST_APP_ID).email(user.getEmail()).password("This is not my password");

        ClientManager newUserClientManager = new ClientManager.Builder().withSignIn(signIn).build();
        AuthenticationApi newUserAuthApi = newUserClientManager.getClient(AuthenticationApi.class);
        newUserAuthApi.signInV4(signIn).execute();
    }

    @Test(expected = UnauthorizedException.class)
    public void signInAccountUnverified() throws Exception {
        // Mark account as unverified. In the v4 API, if sign in is first successful, we throw unauthorized exception
        TestUser admin = TestUserHelper.getSignedInAdmin();
        ParticipantsApi participantsApi = admin.getClient(ParticipantsApi.class);
        StudyParticipant participant = participantsApi.getParticipantById(user.getSession().getId(), false).execute().body();
        participant.setStatus(AccountStatus.UNVERIFIED);
        participantsApi.updateParticipant(user.getSession().getId(), participant).execute();

        // Sign in should now fail with a 404 not found.
        ClientManager newUserClientManager = new ClientManager.Builder().withSignIn(user.getSignIn()).build();
        AuthenticationApi newUserAuthApi = newUserClientManager.getClient(AuthenticationApi.class);
        newUserAuthApi.signInV4(user.getSignIn()).execute();
    }
}
