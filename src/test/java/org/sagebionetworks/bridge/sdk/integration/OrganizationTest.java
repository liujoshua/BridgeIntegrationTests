package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.sagebionetworks.bridge.rest.model.Role.ADMIN;
import static org.sagebionetworks.bridge.rest.model.Role.RESEARCHER;

import java.util.stream.Collectors;

import com.google.common.collect.ImmutableSet;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.api.OrganizationsApi;
import org.sagebionetworks.bridge.rest.api.ParticipantsApi;
import org.sagebionetworks.bridge.rest.exceptions.BadRequestException;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.exceptions.UnauthorizedException;
import org.sagebionetworks.bridge.rest.model.AccountSummary;
import org.sagebionetworks.bridge.rest.model.AccountSummaryList;
import org.sagebionetworks.bridge.rest.model.AccountSummarySearch;
import org.sagebionetworks.bridge.rest.model.Message;
import org.sagebionetworks.bridge.rest.model.Organization;
import org.sagebionetworks.bridge.rest.model.OrganizationList;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;

public class OrganizationTest {
    private TestUser admin;
    private TestUser orgAdmin;
    private TestUser user;
    
    private String orgId1;
    private Organization org1;
    
    private String orgId2;
    private Organization org2;
    
    @Before
    public void before() throws Exception {
        admin = TestUserHelper.getSignedInAdmin();    
    }

    @After
    public void deleteUser() throws Exception {
        if (user != null) {
            user.signOutAndDeleteUser();
        }
    }
    
    @After
    public void deleteOrgAdmin() throws Exception {
        if (orgAdmin != null) {
            orgAdmin.signOutAndDeleteUser();
        }
    }
    
    @After
    public void after() throws Exception {
        OrganizationsApi orgApi = admin.getClient(OrganizationsApi.class);
        if (org1 != null && orgId1 != null) {
            orgApi.deleteOrganization(orgId1).execute();    
        }
        if (org2 != null && orgId2 != null) {
            orgApi.deleteOrganization(orgId2).execute();
        }
    }
    
    @Test
    public void test() throws Exception {
        OrganizationsApi orgApi = admin.getClient(OrganizationsApi.class);
        
        orgId1 = Tests.randomIdentifier(OrganizationTest.class);

        Organization newOrg = new Organization();
        newOrg.setIdentifier(orgId1);
        newOrg.setName("Test Name");
        newOrg.setDescription("A description");
        
        org1 = orgApi.createOrganization(newOrg).execute().body();
        assertNotNull(org1.getVersion());
        assertNotNull(org1.getCreatedOn());
        assertNotNull(org1.getModifiedOn());
        
        org1.setDescription("Description updated");
        org1.setIdentifier("not-the-identifier"); // this has no effect.
        Organization updated = orgApi.updateOrganization(orgId1, org1).execute().body();
        assertEquals(orgId1, updated.getIdentifier());
        assertEquals("Description updated", updated.getDescription());
        assertTrue(updated.getVersion() > org1.getVersion());
        
        OrganizationList list = orgApi.getOrganizations(0, 50).execute().body();
        assertEquals(Integer.valueOf(0), list.getRequestParams().getOffsetBy());
        assertEquals(Integer.valueOf(50), list.getRequestParams().getPageSize());
        
        Organization found = findOrganization(list, orgId1);
        assertNull(found.getCreatedOn());
        assertNull(found.getModifiedOn());
        assertEquals("Test Name", found.getName());
        assertEquals(orgId1, found.getIdentifier());
        assertEquals("Description updated", found.getDescription());
        
        found = orgApi.getOrganization(orgId1).execute().body();
        assertNotNull(found.getCreatedOn());
        assertNotNull(found.getModifiedOn());
        assertNotNull(found.getVersion());
        assertEquals("Organization", found.getType());
        assertEquals("Test Name", found.getName());
        assertEquals(orgId1, found.getIdentifier());
        assertEquals("Description updated", found.getDescription());
        
        Message message = orgApi.deleteOrganization(orgId1).execute().body();
        org1 = null;
        assertEquals("Organization deleted.", message.getMessage());
        
        try {
            orgApi.getOrganization(orgId1).execute().body();
            fail("Should have thrown exception");
        } catch(EntityNotFoundException e) {
        }
        
        list = orgApi.getOrganizations(null, null).execute().body();
        found = findOrganization(list, orgId1);
        assertNull(found);
    }
    
    @Test
    public void testMembership() throws Exception {
        // Create an organization
        OrganizationsApi superadminOrgApi = admin.getClient(OrganizationsApi.class);
        
        orgId1 = Tests.randomIdentifier(OrganizationTest.class);
        Organization newOrg1 = new Organization();
        newOrg1.setIdentifier(orgId1);
        newOrg1.setName("Test Org 1");
        org1 = superadminOrgApi.createOrganization(newOrg1).execute().body();
        
        orgId2 = Tests.randomIdentifier(OrganizationTest.class);
        Organization newOrg2 = new Organization();
        newOrg2.setIdentifier(orgId2);
        newOrg2.setName("Test Org 2");
        org2 = superadminOrgApi.createOrganization(newOrg2).execute().body();
        
        // Create an app admin in organization 1, with researcher permissions to access the 
        // participant APIs
        orgAdmin = TestUserHelper.createAndSignInUser(OrganizationTest.class, false, ADMIN, RESEARCHER);
        superadminOrgApi.addMember(orgId1, orgAdmin.getUserId()).execute();
        OrganizationsApi appAdminOrgApi = orgAdmin.getClient(OrganizationsApi.class);
        
        // The org admin has to sign in again to see these changes...
        orgAdmin.signInAgain();
        
        // session should show organizational membership
        assertEquals(orgAdmin.getSession().getOrgMembership(), orgId1);
        
        // create a user
        user = TestUserHelper.createAndSignInUser(OrganizationTest.class, true);
        
        // cannot assign someone to an organization you are not a member of
        try {
            appAdminOrgApi.addMember(orgId2, user.getUserId()).execute();
            fail("Should have thrown exception");
        } catch(UnauthorizedException e) {
            assertTrue(e.getMessage().contains("Caller is not a member of"));
        }
        
        // cannot change organizational affiliation on an update
        ParticipantsApi participantsApi = orgAdmin.getClient(ParticipantsApi.class);
        StudyParticipant participant = participantsApi.getParticipantById(user.getUserId(), false).execute().body();
        participant.setOrgMembership(orgId2);
        participantsApi.updateParticipant(user.getUserId(), participant).execute();
        
        // Membership has not changed. It didn't throw an error, it did nothing.
        participant = participantsApi.getParticipantById(user.getUserId(), false).execute().body();
        assertNull(participant.getOrgMembership());

        // can add someone to your own org
        appAdminOrgApi.addMember(orgId1, user.getUserId()).execute();
        participant = participantsApi.getParticipantById(user.getUserId(), false).execute().body();
        assertEquals(orgId1, participant.getOrgMembership());
        
        // The account should now be listed as a member
        AccountSummaryList list = appAdminOrgApi.getMembers(orgId1, new AccountSummarySearch()).execute().body();
        assertEquals(ImmutableSet.of(user.getEmail(), orgAdmin.getEmail()), 
                list.getItems().stream().map(AccountSummary::getEmail).collect(Collectors.toSet()));
        assertEquals(Integer.valueOf(2), list.getTotal()); // the admin and the user
        for (AccountSummary summary : list.getItems()) { 
            assertEquals(summary.getOrgMembership(), orgId1);
        }
        
        // can remove someone from your org
        appAdminOrgApi.removeMember(orgId1, user.getUserId()).execute();
        
        // account is no longer listed as a member
        list = appAdminOrgApi.getMembers(orgId1, new AccountSummarySearch()).execute().body();
        assertEquals(ImmutableSet.of(orgAdmin.getEmail()), 
                list.getItems().stream().map(AccountSummary::getEmail).collect(Collectors.toSet()));
        assertEquals(Integer.valueOf(1), list.getTotal());
        
        // This still throws the appropriate exception
        try {
            appAdminOrgApi.removeMember(orgId2, user.getUserId()).execute();
            fail("Should have thrown exception");
        } catch(UnauthorizedException e) {
            assertTrue(e.getMessage().contains("Caller is not a member of"));
        }

        // Throws bad request exception
        try {
            appAdminOrgApi.removeMember(orgId1, user.getUserId()).execute();
            fail("Should have thrown exception");
        } catch(BadRequestException e) {
            assertTrue(e.getMessage().contains("Account is not a member of organization"));
        }
    }
    
    private Organization findOrganization(OrganizationList list, String id) {
        for (Organization org : list.getItems()) {
            if (org.getIdentifier().equals(id)) {
                return org;
            }
        }
        return null;
    }
    
}
