package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.rest.api.ForWorkersApi;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.model.ForwardCursorStringList;
import org.sagebionetworks.bridge.rest.model.OAuthAuthorizationToken;
import org.sagebionetworks.bridge.rest.model.OAuthProvider;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.Study;
import org.sagebionetworks.bridge.rest.model.VersionHolder;
import org.sagebionetworks.bridge.sdk.integration.TestUserHelper.TestUser;

public class OAuthTest {

    private TestUser admin;
    private TestUser user;
    private TestUser worker;
    
    @Before
    public void before() throws Exception {
        admin = TestUserHelper.getSignedInAdmin();
        user = TestUserHelper.createAndSignInUser(OAuthTest.class, true);
        worker = TestUserHelper.createAndSignInUser(OAuthTest.class, true, Role.WORKER);
    }
    
    @After
    public void after() throws Exception {
        if (user != null) {
            user.signOutAndDeleteUser();
        }
        if (worker != null) {
            worker.signOutAndDeleteUser();
        }
    }
    
    @Test(expected = EntityNotFoundException.class)
    public void requestOAuthAccessTokenExists() throws Exception {
        ForConsentedUsersApi usersApi = user.getClient(ForConsentedUsersApi.class);
        
        OAuthAuthorizationToken token = new OAuthAuthorizationToken().authToken("authToken");
        usersApi.requestOAuthAccessToken("vendorId", token).execute().body();
    }
    
    @Test
    public void test() throws Exception {
        ForWorkersApi workersApi = worker.getClient(ForWorkersApi.class);
        
        try {
            workersApi.getHealthCodesGrantingOAuthAccess(worker.getStudyId(), "unused-vendor-id", null, null).execute().body();
            fail("Should have thrown exception.");
        } catch(EntityNotFoundException e) {
            assertEquals("OAuthProvider not found.", e.getMessage());
        }
        try {
            workersApi.getOAuthAccessToken(worker.getStudyId(), "unused-vendor-id", "ABC-DEF-GHI").execute().body();
            fail("Should have thrown exception.");
        } catch(EntityNotFoundException e) {
            assertEquals("OAuthProvider not found.", e.getMessage());
        }
        ForAdminsApi adminApi = admin.getClient(ForAdminsApi.class);
        Study study = adminApi.getStudy(worker.getStudyId()).execute().body();
        try {
            OAuthProvider provider = new OAuthProvider().clientId("foo").endpoint("https://webservices.sagebridge.org/")
                    .callbackUrl("https://webservices.sagebridge.org/").secret("secret");
            
            study.getOAuthProviders().put("bridge", provider);
            VersionHolder version = adminApi.updateStudy(study.getIdentifier(), study).execute().body();
            study.setVersion(version.getVersion()); 
            
            ForwardCursorStringList list = workersApi.getHealthCodesGrantingOAuthAccess(worker.getStudyId(), "bridge", null, null).execute().body();
            assertTrue(list.getItems().isEmpty());
            try {
                workersApi.getOAuthAccessToken(worker.getStudyId(), "bridge", "ABC-DEF-GHI").execute();
                fail("Should have thrown an exception");
            } catch(EntityNotFoundException e) {
                
            }
        } finally {
            study.getOAuthProviders().remove("bridge");
            adminApi.updateStudy(study.getIdentifier(), study).execute();
        }
    }
}
