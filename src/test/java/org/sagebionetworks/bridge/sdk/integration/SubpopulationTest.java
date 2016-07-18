package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.sdk.Roles;
import org.sagebionetworks.bridge.sdk.SubpopulationClient;
import org.sagebionetworks.bridge.sdk.integration.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.sdk.exceptions.BadRequestException;
import org.sagebionetworks.bridge.sdk.models.Criteria;
import org.sagebionetworks.bridge.sdk.models.ResourceList;
import org.sagebionetworks.bridge.sdk.models.holders.GuidVersionHolder;
import org.sagebionetworks.bridge.sdk.models.subpopulations.Subpopulation;
import org.sagebionetworks.bridge.sdk.models.subpopulations.SubpopulationGuid;

public class SubpopulationTest {

    private TestUser admin;
    private TestUser developer;
    private SubpopulationGuid guid;
    
    @Before
    public void before() {
        admin = TestUserHelper.getSignedInAdmin();
        developer = TestUserHelper.createAndSignInUser(SubpopulationTest.class, false, Roles.DEVELOPER);
    }
    
    @After
    public void after() {
        if (guid != null) {
            admin.getSession().getSubpopulationClient().deleteSubpopulationPermanently(guid);    
        }
        if (developer != null) {
            developer.signOutAndDeleteUser();    
        }
    }
    
    @Test
    public void canCRUD() {
        SubpopulationClient subpopulationClient = developer.getSession().getSubpopulationClient();
        
        // Study has a default subpopulation
        ResourceList<Subpopulation> subpops = subpopulationClient.getAllSubpopulations();
        int initialCount = subpops.getTotal();
        assertNotNull(findByName(subpops.getItems(), "Default Consent Group"));
        
        Criteria criteria = new Criteria();
        criteria.setMinAppVersion(10);
        
        // Create a new one
        Subpopulation subpop = new Subpopulation();
        subpop.setName("Later Consent Group");
        subpop.setCriteria(criteria);
        GuidVersionHolder keys = subpopulationClient.createSubpopulation(subpop);
        subpop.setHolder(keys);
        
        guid = subpop.getGuid();
        
        // Read it back
        Subpopulation retrieved = subpopulationClient.getSubpopulation(subpop.getGuid());
        assertEquals("Later Consent Group", retrieved.getName());
        assertEquals(criteria, retrieved.getCriteria());
        assertEquals(keys.getGuid(), retrieved.getGuid().getGuid());
        assertEquals(keys.getVersion(), retrieved.getVersion());
        
        // Update it
        retrieved.setDescription("Adding a description");
        retrieved.getCriteria().setMinAppVersion(8);
        keys = subpopulationClient.updateSubpopulation(retrieved);
        retrieved.setHolder(keys);
        
        // Verify it is available in the list
        subpops = subpopulationClient.getAllSubpopulations();
        assertEquals(initialCount+1, subpops.getTotal());
        assertNotNull(findByName(subpops.getItems(), "Default Consent Group"));
        assertNotNull(findByName(subpops.getItems(), "Later Consent Group"));

        // Delete it
        subpopulationClient.deleteSubpopulation(retrieved.getGuid());
        assertEquals(initialCount, subpopulationClient.getAllSubpopulations().getTotal());
        
        // Cannot delete the default, however:
        try {
            Subpopulation defaultSubpop = findByName(subpops.getItems(), "Default Consent Group");
            assertNotNull(defaultSubpop);
            subpopulationClient.deleteSubpopulation(defaultSubpop.getGuid());
            fail("Should have thrown an exception.");
        } catch(BadRequestException e) {
            assertEquals("Cannot delete the default subpopulation for a study.", e.getMessage());
        }
    }
    
    private Subpopulation findByName(List<Subpopulation> subpops, String name) {
        for (Subpopulation subpop : subpops) {
            if (subpop.getName().equals(name)) {
                return subpop;
            }
        }
        return null;
    }
    
}
