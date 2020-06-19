package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.api.OrganizationsApi;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.model.Message;
import org.sagebionetworks.bridge.rest.model.Organization;
import org.sagebionetworks.bridge.rest.model.OrganizationList;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;

public class OrganizationTest {
    private TestUser admin;
    
    private Organization org;
    
    private String orgId;
    
    @Before
    public void before() throws Exception {
        admin = TestUserHelper.getSignedInAdmin();    
    }
    
    @After
    public void after() throws Exception { 
        OrganizationsApi orgApi = admin.getClient(OrganizationsApi.class);
        if (org != null) {
            orgApi.deleteOrganization(orgId).execute();    
        }
    }
    
    @Test
    public void test() throws Exception {
        OrganizationsApi orgApi = admin.getClient(OrganizationsApi.class);
        
        orgId = Tests.randomIdentifier(OrganizationTest.class);

        Organization newOrg = new Organization();
        newOrg.setIdentifier(orgId);
        newOrg.setName("Test Name");
        newOrg.setDescription("A description");
        
        org = orgApi.createOrganization(newOrg).execute().body();
        assertNotNull(org.getVersion());
        assertNotNull(org.getCreatedOn());
        assertNotNull(org.getModifiedOn());
        
        org.setDescription("Description updated");
        org.setIdentifier("not-the-identifier"); // this has no effect.
        Organization updated = orgApi.updateOrganization(orgId, org).execute().body();
        assertEquals(orgId, updated.getIdentifier());
        assertEquals("Description updated", updated.getDescription());
        assertTrue(updated.getVersion() > org.getVersion());
        
        OrganizationList list = orgApi.getOrganizations(0, 50).execute().body();
        assertEquals(Integer.valueOf(0), list.getRequestParams().getOffsetBy());
        assertEquals(Integer.valueOf(50), list.getRequestParams().getPageSize());
        
        Organization found = findOrganization(list, orgId);
        assertNull(found.getCreatedOn());
        assertNull(found.getModifiedOn());
        assertEquals("Test Name", found.getName());
        assertEquals(orgId, found.getIdentifier());
        assertEquals("Description updated", found.getDescription());
        
        found = orgApi.getOrganization(orgId).execute().body();
        assertNotNull(found.getCreatedOn());
        assertNotNull(found.getModifiedOn());
        assertNotNull(found.getVersion());
        assertEquals("Organization", found.getType());
        assertEquals("Test Name", found.getName());
        assertEquals(orgId, found.getIdentifier());
        assertEquals("Description updated", found.getDescription());
        
        Message message = orgApi.deleteOrganization(orgId).execute().body();
        org = null;
        assertEquals("Organization deleted.", message.getMessage());
        
        try {
            orgApi.getOrganization(orgId).execute().body();
            fail("Should have thrown exception");
        } catch(EntityNotFoundException e) {
        }
        
        list = orgApi.getOrganizations(null, null).execute().body();
        found = findOrganization(list, orgId);
        assertNull(found);
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
