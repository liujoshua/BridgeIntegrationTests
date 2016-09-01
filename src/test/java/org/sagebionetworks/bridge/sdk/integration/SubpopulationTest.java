package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.sdk.AdminClient;
import org.sagebionetworks.bridge.sdk.ClientInfo;
import org.sagebionetworks.bridge.sdk.ClientProvider;
import org.sagebionetworks.bridge.sdk.Roles;
import org.sagebionetworks.bridge.sdk.SubpopulationClient;
import org.sagebionetworks.bridge.sdk.integration.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.sdk.exceptions.BadRequestException;
import org.sagebionetworks.bridge.sdk.exceptions.ConsentRequiredException;
import org.sagebionetworks.bridge.sdk.models.Criteria;
import org.sagebionetworks.bridge.sdk.models.ResourceList;
import org.sagebionetworks.bridge.sdk.models.holders.GuidVersionHolder;
import org.sagebionetworks.bridge.sdk.models.studies.OperatingSystem;
import org.sagebionetworks.bridge.sdk.models.subpopulations.ConsentStatus;
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
        criteria.getMinAppVersions().put(OperatingSystem.ANDROID, 10);
        
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
        retrieved.getCriteria().getMinAppVersions().put(OperatingSystem.ANDROID, 8);
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
    
    @Test
    public void createSubpopulationsWithCriteriaAndVerifyFiltering() {
        SubpopulationClient subpopulationClient = developer.getSession().getSubpopulationClient();
        Subpopulation subpop1 = null;
        Subpopulation subpop2 = null;
        ClientInfo info = ClientProvider.getClientInfo();
        
        TestUser user = TestUserHelper.createAndSignInUser(SubpopulationTest.class, false);
        user.signOut();        
        try {
            Criteria criteria1 = new Criteria();
            criteria1.getMinAppVersions().put(OperatingSystem.ANDROID, 0);
            criteria1.getMaxAppVersions().put(OperatingSystem.ANDROID, 10);
            subpop1 = new Subpopulation();
            subpop1.setName("Consent Group 1");
            subpop1.setCriteria(criteria1);
            subpop1.setRequired(true);
            
            Criteria criteria2 = new Criteria();
            criteria2.getMinAppVersions().put(OperatingSystem.ANDROID, 11);
            subpop2 = new Subpopulation();
            subpop2.setName("Consent Group 2");
            subpop2.setCriteria(criteria2);
            subpop2.setRequired(true);
            
            subpop1.setHolder( subpopulationClient.createSubpopulation(subpop1) );
            subpop2.setHolder( subpopulationClient.createSubpopulation(subpop2) );
            
            // Manipulate the User-Agent string and see the 412 exception contain different 
            // required subpopulations
            try {
                ClientProvider.setClientInfo(getClientInfoWithVersion(OperatingSystem.ANDROID, 2));
                user.signInAgain();
                fail("Should have thrown exception");
            } catch(ConsentRequiredException e) {
                Map<SubpopulationGuid,ConsentStatus> statuses = e.getSession().getConsentStatuses();

                assertNotNull(statuses.get(subpop1.getGuid()));
                assertNull(statuses.get(subpop2.getGuid()));
            }
            try {
                user.signOut();
                ClientProvider.setClientInfo(getClientInfoWithVersion(OperatingSystem.ANDROID, 12));
                user.signInAgain();
                fail("Should have thrown exception");
            } catch(ConsentRequiredException e) {
                Map<SubpopulationGuid,ConsentStatus> statuses = e.getSession().getConsentStatuses();
                assertNull(statuses.get(subpop1.getGuid()));
                assertNotNull(statuses.get(subpop2.getGuid()));
            }
            // Finally... both are returned to an iOS client
            try {
                user.signOut();
                ClientProvider.setClientInfo(getClientInfoWithVersion(OperatingSystem.IOS, 12));
                user.signInAgain();
                fail("Should have thrown exception");
            } catch(ConsentRequiredException e) {
                Map<SubpopulationGuid,ConsentStatus> statuses = e.getSession().getConsentStatuses();
                assertNotNull(statuses.get(subpop1.getGuid()));
                assertNotNull(statuses.get(subpop2.getGuid()));
            }
        } finally {
            user.signOutAndDeleteUser();
            ClientProvider.setClientInfo(info);
            AdminClient adminClient = admin.getSession().getAdminClient();
            if (subpop1 != null) {
                adminClient.deleteSubpopulationPermanently(subpop1.getGuid());
            }
            if (subpop2 != null) {
                adminClient.deleteSubpopulationPermanently(subpop2.getGuid());
            }
        }
    }
    
    private ClientInfo getClientInfoWithVersion(OperatingSystem os, Integer version) {
        return new ClientInfo.Builder().withAppName("app").withAppVersion(version).withOsName(os.getOsName())
                .withDevice("Integration Tests").withOsVersion("2.0.0").build();
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
