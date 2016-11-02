package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.sagebionetworks.bridge.sdk.integration.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.rest.api.AuthenticationApi;
import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.rest.api.ParticipantsApi;
import org.sagebionetworks.bridge.rest.model.AccountSummary;
import org.sagebionetworks.bridge.rest.model.AccountSummaryList;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.SharingScope;
import org.sagebionetworks.bridge.rest.model.SignUp;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.rest.model.UserSessionInfo;

import java.util.List;
import java.util.Map;

@Category(IntegrationSmokeTest.class)
public class SignInTest {

    private TestUser researcher;
    private TestUser user;
    
    @Before
    public void before() throws Exception {
        researcher = TestUserHelper.createAndSignInUser(SignInTest.class, true, Role.RESEARCHER);
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
    
    @Test
    public void createComplexUser() throws Exception {
        AuthenticationApi authApi = researcher.getClient(AuthenticationApi.class);
        
        Map<String,String> map = Maps.newHashMap();
        map.put("phone", "123-345-5768");

        SignUp signUp = new SignUp();
        signUp.setStudy(researcher.getStudyId());
        signUp.setFirstName("First Name");
        signUp.setLastName("Last Name");
        signUp.setEmail(Tests.makeEmail(SignInTest.class));
        signUp.setPassword("P@ssword`1");
        signUp.setExternalId("external ID");
        signUp.setSharingScope(SharingScope.ALL_QUALIFIED_RESEARCHERS);
        signUp.setNotifyByEmail(true);
        signUp.setDataGroups(Lists.newArrayList("group1"));
        signUp.setLanguages(Lists.newArrayList("en"));
        signUp.setAttributes(map);
                
        authApi.signUp(signUp).execute();

        ParticipantsApi participantsApi = researcher.getClient(ParticipantsApi.class);
        
        AccountSummaryList summaries = participantsApi.getParticipants(0, 10, signUp.getEmail()).execute().body();
        assertEquals(1, summaries.getItems().size());
        
        AccountSummary summary = summaries.getItems().get(0);
        StudyParticipant retrieved = participantsApi.getParticipant(summary.getId()).execute().body();
        assertEquals("First Name", retrieved.getFirstName());
        assertEquals("Last Name", retrieved.getLastName());
        assertEquals("external ID", retrieved.getExternalId());
        assertEquals(signUp.getEmail(), retrieved.getEmail());
        assertEquals(SharingScope.ALL_QUALIFIED_RESEARCHERS, retrieved.getSharingScope());
        assertTrue(retrieved.getNotifyByEmail());
        assertEquals(Lists.newArrayList("group1"), retrieved.getDataGroups());
        assertEquals(Lists.newArrayList("en"), retrieved.getLanguages());
        assertEquals("123-345-5768", retrieved.getAttributes().get("phone"));
        
        TestUser admin = TestUserHelper.getSignedInAdmin();
        admin.getClient(ForAdminsApi.class).deleteUser(retrieved.getId()).execute();
    }
}
