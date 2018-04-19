package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sagebionetworks.bridge.rest.api.AuthenticationApi;
import org.sagebionetworks.bridge.rest.api.ExternalIdentifiersApi;
import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.exceptions.ConsentRequiredException;
import org.sagebionetworks.bridge.rest.exceptions.UnauthorizedException;
import org.sagebionetworks.bridge.rest.model.AccountStatus;
import org.sagebionetworks.bridge.rest.model.ExternalIdentifier;
import org.sagebionetworks.bridge.rest.model.ExternalIdentifierList;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.SignIn;
import org.sagebionetworks.bridge.rest.model.SignUp;
import org.sagebionetworks.bridge.rest.model.Study;
import org.sagebionetworks.bridge.rest.model.UserSessionInfo;
import org.sagebionetworks.bridge.rest.model.VersionHolder;
import org.sagebionetworks.bridge.sdk.integration.TestUserHelper.TestUser;

import com.google.common.collect.ImmutableList;

public class ExternalIdSignUpTest {
    
    private String externalId;
    private String otherExternalId;
    private TestUser admin;
    private TestUser developer;
    private ExternalIdentifiersApi devIdsClient;
    private ForAdminsApi adminClient;
    private AuthenticationApi authClient;
    private UserSessionInfo info;
    private Study study;
    
    @Before
    public void before() throws Exception {
        admin = TestUserHelper.getSignedInAdmin();
        developer = TestUserHelper.createAndSignInUser(ExternalIdSignUpTest.class, false, Role.DEVELOPER);
        externalId = Tests.randomIdentifier(ExternalIdSignUpTest.class);
        otherExternalId = Tests.randomIdentifier(ExternalIdSignUpTest.class);
        
        adminClient = admin.getClient(ForAdminsApi.class);
        devIdsClient = developer.getClient(ExternalIdentifiersApi.class);
        authClient = TestUserHelper.getNonAuthClient(AuthenticationApi.class, Tests.STUDY_ID);
        changeExternalIdValidation(false);
    }
    
    @After
    public void after() throws Exception {
        if (info != null) {
            adminClient.deleteUser(info.getId()).execute();
        }
        if (developer != null) {
            developer.signOutAndDeleteUser();
        }
    }
    
    public void changeExternalIdValidation(boolean enabled) throws Exception {
        study = adminClient.getStudy(Tests.STUDY_ID).execute().body();
        study.setExternalIdValidationEnabled(enabled);
        study.setExternalIdRequiredOnSignup(enabled);
        VersionHolder versionHolder = adminClient.updateStudy(Tests.STUDY_ID, study).execute().body();
        study.setVersion(versionHolder.getVersion());
        
        study = adminClient.getStudy(Tests.STUDY_ID).execute().body();
        if (enabled) {
            assertTrue(study.getExternalIdValidationEnabled());
            assertTrue(study.getExternalIdRequiredOnSignup());
        } else {
            assertFalse(study.getExternalIdValidationEnabled());
            assertFalse(study.getExternalIdRequiredOnSignup());
        }
    }
    
    @Test
    public void externalIdSignInTest() throws Exception {
        try {
            // Enable validation, and an account with just an external ID will succeed
            changeExternalIdValidation(true);
            devIdsClient.addExternalIds(ImmutableList.of(externalId, otherExternalId)).execute();
            
            SignUp signUp = new SignUp().study(Tests.STUDY_ID).password(Tests.PASSWORD);
            signUp.externalId(externalId);
            authClient.signUp(signUp).execute();
            
            ExternalIdentifierList list = devIdsClient.getExternalIds(null, 50, null, null).execute().body();
            for (ExternalIdentifier identifier : list.getItems()) {
                if (externalId.equals(identifier.getIdentifier())) {
                    assertTrue(identifier.getAssigned());        
                }
            }
            
            // Prove you can sign in with this account (status = enabled)
            SignIn signIn = new SignIn().externalId(externalId).study(Tests.STUDY_ID).password(Tests.PASSWORD);
            try {
                authClient.signInV4(signIn).execute().body();
                fail("Should have thrown exception.");
            } catch(ConsentRequiredException e) {
                info = e.getSession();
                assertEquals(externalId, info.getExternalId());
                assertEquals(AccountStatus.ENABLED, info.getStatus());
            }
            
            // With validation disabled, this kind of account will not succeed, even if the external ID is there
            changeExternalIdValidation(false); 
            try {
                signUp = new SignUp().study(Tests.STUDY_ID).password(Tests.PASSWORD);
                signUp.externalId(otherExternalId);
                authClient.signUp(signUp).execute();
                fail("Should have thrown exception.");
            } catch(UnauthorizedException e) {
                assertEquals("External ID management is not enabled for this study", e.getMessage());
            }
        } finally {
            devIdsClient.deleteExternalIds(ImmutableList.of(externalId, otherExternalId)).execute();
        }
    }
}
