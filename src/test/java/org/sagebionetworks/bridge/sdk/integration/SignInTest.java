package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;

import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.sagebionetworks.bridge.sdk.ClientProvider;
import org.sagebionetworks.bridge.sdk.Roles;
import org.sagebionetworks.bridge.sdk.Session;
import org.sagebionetworks.bridge.sdk.integration.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.sdk.UserClient;
import org.sagebionetworks.bridge.sdk.models.accounts.StudyParticipant;

import com.google.common.collect.Sets;

@Category(IntegrationSmokeTest.class)
public class SignInTest {

    private TestUser developer;
    private TestUser user;
    
    @Before
    public void before() {
        developer = TestUserHelper.createAndSignInUser(SignInTest.class, true, Roles.DEVELOPER);
        user = TestUserHelper.createAndSignInUser(SignInTest.class, true);
    }
    
    @After
    public void after() {
        if (user != null) {
            user.signOutAndDeleteUser();
        }
        if (developer != null) {
            developer.signOutAndDeleteUser();
        }
    }

    @Test
    public void canGetDataGroups(){
        Set<String> dataGroups = Sets.newHashSet("sdk-int-1");
        UserClient client = user.getSession().getUserClient();
        
        StudyParticipant participant = new StudyParticipant.Builder()
                .withDataGroups(dataGroups).build();
        client.saveStudyParticipant(participant);
        
        user.getSession().signOut();
        
        Session session = ClientProvider.signIn(user.getSignInCredentials());
        assertEquals(dataGroups, session.getStudyParticipant().getDataGroups());
    }
    
}
