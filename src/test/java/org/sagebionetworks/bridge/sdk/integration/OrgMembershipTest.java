package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.sagebionetworks.bridge.rest.model.Role.DEVELOPER;
import static org.sagebionetworks.bridge.rest.model.Role.ORG_ADMIN;
import static org.sagebionetworks.bridge.rest.model.Role.RESEARCHER;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.api.ForOrgAdminsApi;
import org.sagebionetworks.bridge.rest.api.ForSuperadminsApi;
import org.sagebionetworks.bridge.rest.api.OrganizationsApi;
import org.sagebionetworks.bridge.rest.exceptions.ConstraintViolationException;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.model.Account;
import org.sagebionetworks.bridge.rest.model.AccountSummaryList;
import org.sagebionetworks.bridge.rest.model.AccountSummarySearch;
import org.sagebionetworks.bridge.rest.model.Organization;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.util.IntegTestUtils;

public class OrgMembershipTest {
    private TestUser admin;
    private TestUser orgAdmin;
    private String orgId;
    private String userId;
    
    @Before
    public void before() throws Exception {
        admin = TestUserHelper.getSignedInAdmin();
        orgAdmin = TestUserHelper.createAndSignInUser(OrgMembershipTest.class, false, ORG_ADMIN);

        OrganizationsApi orgApi = admin.getClient(OrganizationsApi.class);
        orgId = Tests.randomIdentifier(OrgMembershipTest.class);
        Organization org = new Organization();
        org.setIdentifier(orgId);
        org.setName(orgId);
        org = orgApi.createOrganization(org).execute().body();
        
        // Put the orgAdmin in the test organization 
        admin.getClient(ForOrgAdminsApi.class).addMember(orgId, orgAdmin.getUserId()).execute();
    }
    
    @After
    public void after() throws Exception {
        if (userId != null) {
            admin.getClient(ForAdminsApi.class).deleteUser(userId).execute();
        }
        orgAdmin.signOutAndDeleteUser();
        if (orgId != null) {
            admin.getClient(ForSuperadminsApi.class).deleteOrganization(orgId).execute();
        }
    }

    @Test
    public void testMembership() throws Exception {
        String email = IntegTestUtils.makeEmail(OrgMembershipTest.class);
        ForOrgAdminsApi orgAdminApi = orgAdmin.getClient(ForOrgAdminsApi.class);
        
        // Create a test user. OrgId is automatically set correctly through this API
        Account account = new Account().email(email).roles(ImmutableList.of(DEVELOPER));        
        userId = orgAdminApi.createAccount(account).execute().body().getIdentifier();
        
        // User can be retrieved singularly
        Account retrieved = orgAdminApi.getAccount(userId).execute().body();
        assertEquals(orgId, retrieved.getOrgMembership());
        
        // User can be retrieved in a list. User search scoped to the user's email list
        AccountSummarySearch search = new AccountSummarySearch().emailFilter(email);
        AccountSummaryList list = orgAdminApi.getMembers(orgId, search).execute().body();
        assertEquals(userId, list.getItems().get(0).getId());
        
        // User can be udpated
        retrieved.setFirstName("Olaf");
        retrieved.setRoles(ImmutableList.of(DEVELOPER, RESEARCHER));
        orgAdminApi.updateAccount(userId, retrieved).execute();
        
        retrieved = orgAdminApi.getAccount(userId).execute().body();
        assertEquals("Olaf", retrieved.getFirstName());
        assertEquals(ImmutableSet.of(DEVELOPER, RESEARCHER), ImmutableSet.copyOf(retrieved.getRoles()));
        
        // User can be removed from the organization
        orgAdminApi.removeMember(orgId, userId).execute().body();
        
        list = orgAdminApi.getMembers(orgId, search).execute().body();
        assertTrue(list.getItems().isEmpty());
        
        // These should now fail because it's not in the target organization of the caller
        try {
            orgAdminApi.deleteAccount(userId).execute();
            fail("Should have thrown an exception");
        } catch(EntityNotFoundException e) {
        }
        try {
            orgAdminApi.updateAccount(userId, account).execute();
            fail("Should have thrown an exception");
        } catch(EntityNotFoundException e) {
        }
        try {
            orgAdminApi.getAccount(userId).execute().body();
            fail("Should have thrown exception");
        } catch(EntityNotFoundException e) {
        }
        
        // Put the account back
        orgAdminApi.addMember(orgId, userId).execute().body();
        list = orgAdminApi.getMembers(orgId, search).execute().body();
        assertEquals(list.getTotal(), Integer.valueOf(1));
        
        // Test deletion of the ORGANIZATION when there is an account still associated to the
        // organization. It should fail.
        try {
            admin.getClient(ForSuperadminsApi.class).deleteOrganization(orgId).execute();
            fail("Should have thrown exception");
        } catch(ConstraintViolationException e) {
            assertEquals("Cannot delete organization (it currently contains one or more accounts).", e.getMessage());
        }
        
        // Test deletion
        orgAdminApi.deleteAccount(userId).execute();
        
        list = orgAdminApi.getMembers(orgId, search).execute().body();
        assertTrue(list.getItems().isEmpty());
    }
}
