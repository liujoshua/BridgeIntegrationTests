package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.sagebionetworks.bridge.sdk.integration.Tests.NATIONAL_PHONE_FORMAT;
import static org.sagebionetworks.bridge.sdk.integration.Tests.PASSWORD;
import static org.sagebionetworks.bridge.sdk.integration.Tests.PHONE;
import static org.sagebionetworks.bridge.util.IntegTestUtils.TEST_APP_ID;

import org.apache.commons.lang3.RandomStringUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.api.AccountsApi;
import org.sagebionetworks.bridge.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.rest.model.IdentifierUpdate;
import org.sagebionetworks.bridge.rest.model.SignUp;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.rest.model.UserSessionInfo;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;

public class UpdateIdentifiersTest {
    private TestUser user;
    
    @Before
    public void before() {
    }
    
    @After
    public void after() throws Exception {
        if (user != null) {
            user.signOutAndDeleteUser();
        }
    }
    
    @Test
    public void addPhoneViaUpdate() throws Exception {
        SignUp signUp = new SignUp().appId(TEST_APP_ID).password(PASSWORD);
        user = TestUserHelper.createAndSignInUser(UpdateIdentifiersTest.class, true, signUp);
        
        IdentifierUpdate update = new IdentifierUpdate().signIn(user.getSignIn()).phoneUpdate(PHONE);
        
        AccountsApi accountsApi = user.getClient(AccountsApi.class);
        ForConsentedUsersApi userApi = user.getClient(ForConsentedUsersApi.class);
        
        UserSessionInfo info = accountsApi.updateIdentifiersForSelf(update).execute().body();
        assertEquals(info.getPhone().getNationalFormat(), NATIONAL_PHONE_FORMAT);
        
        StudyParticipant participant = userApi.getUsersParticipantRecord(false).execute().body();
        assertEquals(participant.getPhone().getNationalFormat(), NATIONAL_PHONE_FORMAT);
    }

    @Test
    public void addSynapseUserIdViaUpdate() throws Exception {
        String synapseUserId = RandomStringUtils.randomNumeric(5);
        SignUp signUp = new SignUp().appId(TEST_APP_ID).password(PASSWORD);
        user = TestUserHelper.createAndSignInUser(UpdateIdentifiersTest.class, true, signUp);
        
        IdentifierUpdate update = new IdentifierUpdate().signIn(user.getSignIn()).synapseUserIdUpdate(synapseUserId);
        
        AccountsApi accountsApi = user.getClient(AccountsApi.class);
        ForConsentedUsersApi userApi = user.getClient(ForConsentedUsersApi.class);
        
        UserSessionInfo info = accountsApi.updateIdentifiersForSelf(update).execute().body();
        assertEquals(info.getSynapseUserId(), synapseUserId);
        
        StudyParticipant participant = userApi.getUsersParticipantRecord(false).execute().body();
        assertEquals(participant.getSynapseUserId(), synapseUserId);
    }
    
    // StudyMembershipTest covers the more complicated case of adding an external ID, due to 
    // the complicated set-up that is necessary. Email address has proven to be very hard due to authentication.
}
