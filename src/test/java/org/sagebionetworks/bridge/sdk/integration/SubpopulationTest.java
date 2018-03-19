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

import org.sagebionetworks.bridge.rest.ClientManager;
import org.sagebionetworks.bridge.sdk.integration.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.rest.api.AuthenticationApi;
import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.api.SubpopulationsApi;
import org.sagebionetworks.bridge.rest.exceptions.BadRequestException;
import org.sagebionetworks.bridge.rest.exceptions.ConsentRequiredException;
import org.sagebionetworks.bridge.rest.model.ClientInfo;
import org.sagebionetworks.bridge.rest.model.ConsentStatus;
import org.sagebionetworks.bridge.rest.model.Criteria;
import org.sagebionetworks.bridge.rest.model.GuidVersionHolder;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.SignIn;
import org.sagebionetworks.bridge.rest.model.Subpopulation;
import org.sagebionetworks.bridge.rest.model.SubpopulationList;

public class SubpopulationTest {

    private TestUser admin;
    private TestUser developer;
    private Subpopulation subpop1;
    private Subpopulation subpop2;
    
    @Before
    public void before() throws Exception {
        admin = TestUserHelper.getSignedInAdmin();
        developer = TestUserHelper.createAndSignInUser(SubpopulationTest.class, false, Role.DEVELOPER);
    }
    
    @After
    public void after() throws Exception {
        if (developer != null) {
            developer.signOutAndDeleteUser();    
        }
    }
    
    @After
    public void deleteSubpop1() throws Exception {
        if (subpop1 != null) {
            admin.getClient(ForAdminsApi.class).deleteSubpopulation(subpop1.getGuid(), true).execute();
        }
    }
    
    @After
    public void deleteSubpop2() throws Exception {
        if (subpop2 != null) {
            admin.getClient(ForAdminsApi.class).deleteSubpopulation(subpop2.getGuid(), true).execute();
        }
    }
    
    @Test
    public void canCRUD() throws Exception {
        SubpopulationsApi subpopulationsApi = developer.getClient(SubpopulationsApi.class);
        
        // Study has a default subpopulation
        SubpopulationList subpops = subpopulationsApi.getSubpopulations().execute().body();
        int initialCount = subpops.getItems().size();
        assertNotNull(findByName(subpops.getItems(), "Default Consent Group"));
        
        Criteria criteria = new Criteria();
        criteria.getMinAppVersions().put("Android", 10);
        
        // Create a new one
        subpop1 = new Subpopulation();
        subpop1.setName("Later Consent Group");
        subpop1.setCriteria(criteria);
        GuidVersionHolder keys = subpopulationsApi.createSubpopulation(subpop1).execute().body();
        subpop1.setGuid(keys.getGuid());
        subpop1.setVersion(keys.getVersion());
        
        // Read it back
        Subpopulation retrieved = subpopulationsApi.getSubpopulation(subpop1.getGuid()).execute().body();
        assertEquals("Later Consent Group", retrieved.getName());
        Tests.setVariableValueInObject(criteria, "type", "Criteria");
        assertEquals(criteria, retrieved.getCriteria());
        assertEquals(keys.getGuid(), retrieved.getGuid());
        assertEquals(keys.getVersion(), retrieved.getVersion());
        
        // Update it
        retrieved.setDescription("Adding a description");
        retrieved.getCriteria().getMinAppVersions().put("Android", 8);
        keys = subpopulationsApi.updateSubpopulation(retrieved.getGuid(), retrieved).execute().body();
        retrieved.setGuid(keys.getGuid());
        retrieved.setVersion(keys.getVersion());
        
        // Verify it is available in the list
        subpops = subpopulationsApi.getSubpopulations().execute().body();
        assertEquals((initialCount+1), subpops.getItems().size());
        assertNotNull(findByName(subpops.getItems(), "Default Consent Group"));
        assertNotNull(findByName(subpops.getItems(), "Later Consent Group"));

        // Delete it
        SubpopulationsApi adminSubpopApi = admin.getClient(SubpopulationsApi.class);
        adminSubpopApi.deleteSubpopulation(retrieved.getGuid(), true).execute();
        assertEquals(initialCount, subpopulationsApi.getSubpopulations().execute().body().getItems().size());
        subpop1 = null;
        
        // Cannot delete the default, however:
        try {
            Subpopulation defaultSubpop = findByName(subpops.getItems(), "Default Consent Group");
            assertNotNull(defaultSubpop);
            adminSubpopApi.deleteSubpopulation(defaultSubpop.getGuid(), true).execute();
            fail("Should have thrown an exception.");
        } catch(BadRequestException e) {
            assertEquals("Cannot delete the default subpopulation for a study.", e.getMessage());
        }
    }
    
    @Test
    public void createSubpopulationsWithCriteriaAndVerifyFiltering() throws Exception {
        SubpopulationsApi subpopulationsApi = developer.getClient(SubpopulationsApi.class);
        
        TestUser user = TestUserHelper.createAndSignInUser(SubpopulationTest.class, false);
        user.signOut();
        try {
            Criteria criteria1 = new Criteria();
            criteria1.getMinAppVersions().put("Android", 0);
            criteria1.getMaxAppVersions().put("Android", 10);
            subpop1 = new Subpopulation();
            subpop1.setName("Consent Group 1");
            subpop1.setCriteria(criteria1);
            subpop1.setRequired(true);
            
            Criteria criteria2 = new Criteria();
            criteria2.getMinAppVersions().put("Android", 11);
            subpop2 = new Subpopulation();
            subpop2.setName("Consent Group 2");
            subpop2.setCriteria(criteria2);
            subpop2.setRequired(true);
            
            updateSubpopulation(subpopulationsApi, subpop1);
            updateSubpopulation(subpopulationsApi, subpop2);
            
            // Manipulate the User-Agent string and see the 412 exception contain different 
            // required subpopulations
            try {
                ClientManager manager = clientManager(user.getSignIn(), getClientInfoWithVersion("Android", 2));
                manager.getClient(AuthenticationApi.class).signInV4(user.getSignIn()).execute();
                fail("Should have thrown exception");
            } catch(ConsentRequiredException e) {
                Map<String,ConsentStatus> statuses = e.getSession().getConsentStatuses();

                assertNotNull(statuses.get(subpop1.getGuid()));
                assertNull(statuses.get(subpop2.getGuid()));
            }
            try {
                user.signOut();    
                
                ClientManager manager = null;
                manager = clientManager(user.getSignIn(), getClientInfoWithVersion("Android", 12));    
                manager.getClient(AuthenticationApi.class).signInV4(user.getSignIn()).execute();
                fail("Should have thrown exception");
            } catch(ConsentRequiredException e) {
                Map<String,ConsentStatus> statuses = e.getSession().getConsentStatuses();
                assertNull(statuses.get(subpop1.getGuid()));
                assertNotNull(statuses.get(subpop2.getGuid()));
            }
            // Finally... both are returned to an iOS client
            try {
                user.signOut();
                ClientManager manager = clientManager(user.getSignIn(), getClientInfoWithVersion("iPhone OS", 12));
                manager.getClient(AuthenticationApi.class).signInV4(user.getSignIn()).execute();
                fail("Should have thrown exception");
            } catch(ConsentRequiredException e) {
                Map<String,ConsentStatus> statuses = e.getSession().getConsentStatuses();
                assertNotNull(statuses.get(subpop1.getGuid()));
                assertNotNull(statuses.get(subpop2.getGuid()));
            }
        } finally {
            user.signOutAndDeleteUser();
        }
    }
    
    private ClientManager clientManager(SignIn signIn, ClientInfo clientInfo) {
        return new ClientManager.Builder()
                .withSignIn(signIn)
                .withClientInfo(clientInfo).build();
    }
    
    private void updateSubpopulation(SubpopulationsApi subpopulationsApi, Subpopulation subpop) throws Exception {
        GuidVersionHolder holder = subpopulationsApi.createSubpopulation(subpop).execute().body();
        subpop.setGuid(holder.getGuid());
        subpop.setVersion(holder.getVersion());
    }
    
    private ClientInfo getClientInfoWithVersion(String osName, Integer version) {
        ClientInfo info = new ClientInfo();
        info.setAppName("App");
        info.setAppVersion(version);
        info.setDeviceName("Integration Tests");
        info.setOsName(osName);
        info.setOsVersion("2.0.0");
        info.setSdkName("BridgeJavaSDK");
        info.setSdkVersion(Integer.parseInt(developer.getClientManager().getConfig().getSdkVersion()));
        return info;
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
