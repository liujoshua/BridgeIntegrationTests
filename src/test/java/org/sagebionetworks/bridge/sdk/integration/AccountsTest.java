package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.sagebionetworks.bridge.rest.model.Role.DEVELOPER;
import static org.sagebionetworks.bridge.rest.model.Role.ORG_ADMIN;
import static org.sagebionetworks.bridge.sdk.integration.Tests.PASSWORD;
import static org.sagebionetworks.bridge.util.IntegTestUtils.PHONE;
import static org.sagebionetworks.bridge.util.IntegTestUtils.SAGE_ID;
import static org.sagebionetworks.bridge.util.IntegTestUtils.TEST_APP_ID;

import com.google.common.collect.ImmutableList;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.api.AccountsApi;
import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.api.ForOrgAdminsApi;
import org.sagebionetworks.bridge.rest.api.ForSuperadminsApi;
import org.sagebionetworks.bridge.rest.model.Account;
import org.sagebionetworks.bridge.rest.model.App;
import org.sagebionetworks.bridge.rest.model.IdentifierUpdate;
import org.sagebionetworks.bridge.rest.model.SignIn;
import org.sagebionetworks.bridge.rest.model.SignUp;
import org.sagebionetworks.bridge.rest.model.UserSessionInfo;
import org.sagebionetworks.bridge.rest.model.VersionHolder;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.util.IntegTestUtils;

public class AccountsTest {
    private TestUser admin;
    private TestUser developer;
    private TestUser orgAdmin;
    private String phoneUserId;
    private String emailUserId;

    @Before
    public void before() throws Exception {
        admin = TestUserHelper.getSignedInAdmin();
        developer = TestUserHelper.createAndSignInUser(AccountsTest.class, false, DEVELOPER);
        orgAdmin = TestUserHelper.createAndSignInUser(AccountsTest.class, true, ORG_ADMIN);

        IntegTestUtils.deletePhoneUser();

        ForSuperadminsApi superadminApi = admin.getClient(ForSuperadminsApi.class);
        App app = superadminApi.getApp(TEST_APP_ID).execute().body();
        if (!app.isPhoneSignInEnabled() || !app.isEmailSignInEnabled()) {
            app.setPhoneSignInEnabled(true);
            app.setEmailSignInEnabled(true);
            VersionHolder keys = superadminApi.updateApp(app.getIdentifier(), app).execute().body();
            app.version(keys.getVersion());
        }
    }

    @After
    public void after() throws Exception {
        if (developer != null) {
            developer.signOutAndDeleteUser();
        }
        if (orgAdmin != null) {
            orgAdmin.signOutAndDeleteUser();
        }
        if (phoneUserId != null) {
            admin.getClient(ForAdminsApi.class).deleteUser(phoneUserId).execute();
        }
        if (emailUserId != null) {
            admin.getClient(ForAdminsApi.class).deleteUser(emailUserId).execute();
        }
    }

    @Test
    public void addEmailToPhoneUser() throws Exception {
        ForOrgAdminsApi orgAdminApi = orgAdmin.getClient(ForOrgAdminsApi.class);
        
        SignUp signUp = new SignUp().appId(TEST_APP_ID).phone(PHONE).password(PASSWORD)
                .orgMembership(SAGE_ID).roles(ImmutableList.of(DEVELOPER));
        phoneUserId = admin.getClient(ForAdminsApi.class).createUser(signUp).execute().body().getId();
        
        SignIn signIn = new SignIn().appId(TEST_APP_ID).phone(signUp.getPhone()).password(signUp.getPassword());
        TestUser phoneUser = TestUserHelper.getSignedInUser(signIn);
        
        String email = IntegTestUtils.makeEmail(AccountsTest.class);
        IdentifierUpdate identifierUpdate = new IdentifierUpdate().signIn(signIn).emailUpdate(email);
        
        AccountsApi accountsApi = phoneUser.getClient(AccountsApi.class);
        UserSessionInfo info = accountsApi.updateIdentifiersForSelf(identifierUpdate).execute().body();
        assertEquals(email, info.getEmail());

        Account retrieved = orgAdminApi.getAccount(phoneUserId).execute().body();
        assertEquals(email, retrieved.getEmail());

        // But if you do it again, it should not work
        String newEmail = IntegTestUtils.makeEmail(AccountsTest.class);
        identifierUpdate = new IdentifierUpdate().signIn(signIn).emailUpdate(newEmail);

        info = accountsApi.updateIdentifiersForSelf(identifierUpdate).execute().body();
        assertEquals(email, info.getEmail()); // unchanged
    }

    @Test
    public void addPhoneToEmailUser() throws Exception {
        ForOrgAdminsApi orgAdminApi = orgAdmin.getClient(ForOrgAdminsApi.class);
        
        String email = IntegTestUtils.makeEmail(AccountsTest.class);
        SignUp signUp = new SignUp().appId(TEST_APP_ID).email(email).password(PASSWORD)
                .orgMembership(SAGE_ID).roles(ImmutableList.of(DEVELOPER));
        
        emailUserId = admin.getClient(ForAdminsApi.class).createUser(signUp).execute().body().getId();
        
        SignIn signIn = new SignIn().appId(TEST_APP_ID).email(signUp.getEmail()).password(signUp.getPassword());
        TestUser emailUser = TestUserHelper.getSignedInUser(signIn);
        
        IdentifierUpdate identifierUpdate = new IdentifierUpdate().signIn(signIn).phoneUpdate(PHONE);

        AccountsApi accountsApi = emailUser.getClient(AccountsApi.class);
        UserSessionInfo info = accountsApi.updateIdentifiersForSelf(identifierUpdate).execute().body();
        assertEquals(PHONE.getNumber(), info.getPhone().getNumber());

        Account retrieved = orgAdminApi.getAccount(emailUserId).execute().body();
        assertEquals(PHONE.getNumber(), retrieved.getPhone().getNumber());

        // But if you do it again, it should not work
        info = accountsApi.updateIdentifiersForSelf(identifierUpdate).execute().body();
        assertEquals(PHONE.getNumber(), info.getPhone().getNumber()); // unchanged
    }
}
