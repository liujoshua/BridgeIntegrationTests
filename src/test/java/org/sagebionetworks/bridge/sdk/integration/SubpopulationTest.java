package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.ClientManager;
import org.sagebionetworks.bridge.rest.api.AppsApi;
import org.sagebionetworks.bridge.rest.api.AuthenticationApi;
import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.api.StudiesApi;
import org.sagebionetworks.bridge.rest.api.SubpopulationsApi;
import org.sagebionetworks.bridge.rest.exceptions.BadRequestException;
import org.sagebionetworks.bridge.rest.exceptions.ConsentRequiredException;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.model.App;
import org.sagebionetworks.bridge.rest.model.ClientInfo;
import org.sagebionetworks.bridge.rest.model.ConsentStatus;
import org.sagebionetworks.bridge.rest.model.Criteria;
import org.sagebionetworks.bridge.rest.model.GuidVersionHolder;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.SignIn;
import org.sagebionetworks.bridge.rest.model.Study;
import org.sagebionetworks.bridge.rest.model.Subpopulation;
import org.sagebionetworks.bridge.rest.model.SubpopulationList;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;

public class SubpopulationTest {

    private TestUser admin;
    private TestUser developer;
    private Subpopulation subpop1;
    private Subpopulation subpop2;
    private Study study;
    
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
    public void deleteEntities() throws Exception {
        // These now need to be deleted in a specific order
        if (subpop1 != null) {
            admin.getClient(ForAdminsApi.class).deleteSubpopulation(subpop1.getGuid(), true).execute();
        }
        if (subpop2 != null) {
            admin.getClient(ForAdminsApi.class).deleteSubpopulation(subpop2.getGuid(), true).execute();
        }
        if (study != null) {
            admin.getClient(StudiesApi.class).deleteStudy(study.getIdentifier(), true).execute();
        }
    }
    
    @Test
    public void canCRUD() throws Exception {
        // First, to do this test, we need to create some valid data groups and studies if they
        // don't already exist.
        AppsApi appsApi = developer.getClient(AppsApi.class);
        App app = appsApi.getUsersApp().execute().body();

        String dataGroup = Iterables.getFirst(app.getDataGroups(), null);
        List<String> dataGroupList = ImmutableList.of(dataGroup);

        // Create a study, if needed
        StudiesApi studiesApi = admin.getClient(StudiesApi.class);
        String studyId = Tests.randomIdentifier(SubpopulationTest.class);
        study = new Study().identifier(studyId).name("Study " + studyId);
        studiesApi.createStudy(study).execute().body();
        List<String> studyIds = ImmutableList.of(study.getIdentifier());
        
        // Now proceed with the subpopulation test
        SubpopulationsApi subpopulationsApi = developer.getClient(SubpopulationsApi.class);
        
        // App has a default subpopulation
        SubpopulationList subpops = subpopulationsApi.getSubpopulations(false).execute().body();
        int initialCount = subpops.getItems().size();
        assertNotNull(findByName(subpops.getItems(), "Default Consent Group"));
        
        Criteria criteria = new Criteria();
        // Set empty collections so this object is equal to the object returned by the API
        criteria.setAllOfGroups(ImmutableList.of());
        criteria.setNoneOfGroups(ImmutableList.of());
        criteria.setAllOfStudyIds(ImmutableList.of());
        criteria.setNoneOfStudyIds(ImmutableList.of());
        criteria.setMaxAppVersions(ImmutableMap.of());        
        criteria.setMinAppVersions(ImmutableMap.of("Android", 10));
        
        // Create a new one
        subpop1 = new Subpopulation();
        subpop1.setName("Later Consent Group");
        subpop1.setCriteria(criteria);
        subpop1.setStudyIdsAssignedOnConsent(studyIds);
        subpop1.setDataGroupsAssignedWhileConsented(dataGroupList);
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
        assertEquals(studyIds, retrieved.getStudyIdsAssignedOnConsent());
        assertEquals(dataGroupList, retrieved.getDataGroupsAssignedWhileConsented());
        
        // Update it
        retrieved.setDescription("Adding a description");
        retrieved.getCriteria().getMinAppVersions().put("Android", 8);
        retrieved.setStudyIdsAssignedOnConsent(null);
        retrieved.setDataGroupsAssignedWhileConsented(ImmutableList.of());
        keys = subpopulationsApi.updateSubpopulation(retrieved.getGuid(), retrieved).execute().body();
        retrieved.setGuid(keys.getGuid());
        retrieved.setVersion(keys.getVersion());
        
        // Get it again and verify it has updated
        Subpopulation retrievedAgain = subpopulationsApi.getSubpopulation(subpop1.getGuid()).execute().body();
        assertEquals("Adding a description", retrievedAgain.getDescription());
        assertEquals(new Integer(8), retrievedAgain.getCriteria().getMinAppVersions().get("Android"));
        assertTrue(retrievedAgain.getStudyIdsAssignedOnConsent().isEmpty());
        assertTrue(retrievedAgain.getDataGroupsAssignedWhileConsented().isEmpty());
        
        // Verify it is available in the list
        subpops = subpopulationsApi.getSubpopulations(false).execute().body();
        assertEquals((initialCount+1), subpops.getItems().size());
        assertNotNull(findByName(subpops.getItems(), "Default Consent Group"));
        assertNotNull(findByName(subpops.getItems(), "Later Consent Group"));

        // Delete it (logically)
        SubpopulationsApi adminSubpopApi = admin.getClient(SubpopulationsApi.class);
        adminSubpopApi.deleteSubpopulation(retrieved.getGuid(), false).execute();
        assertEquals(initialCount, subpopulationsApi.getSubpopulations(false).execute().body().getItems().size());
        assertEquals(initialCount+1, subpopulationsApi.getSubpopulations(true).execute().body().getItems().size());
        
        // Try to delete it logically again, it fails
        try {
            adminSubpopApi.deleteSubpopulation(retrieved.getGuid(), false).execute();
            fail("Should have thrown exception");
        } catch(EntityNotFoundException e) {
        }
        
        // Delete it (physically)
        adminSubpopApi.deleteSubpopulation(retrieved.getGuid(), true).execute();
        assertEquals(initialCount, subpopulationsApi.getSubpopulations(true).execute().body().getItems().size());
        subpop1 = null;
        
        try {
            subpopulationsApi.getSubpopulation(retrieved.getGuid()).execute();
            fail("Should have thrown exception");
        } catch(EntityNotFoundException e) {
        }
    }
    
    @Test
    public void cannotLogicallyDeleteDefaultSubpopulation() throws IOException {
        SubpopulationsApi subpopulationsApi = developer.getClient(SubpopulationsApi.class);
        try {
            SubpopulationList subpops = subpopulationsApi.getSubpopulations(false).execute().body();
            Subpopulation defaultSubpop = findByName(subpops.getItems(), "Default Consent Group");
            assertNotNull(defaultSubpop);
            subpopulationsApi.deleteSubpopulation(defaultSubpop.getGuid(), false).execute();
            fail("Should have thrown an exception.");
        } catch(BadRequestException e) {
            assertEquals("Cannot delete the default subpopulation for an app.", e.getMessage());
        }
    }
    
    @Test
    public void createSubpopulationsWithCriteriaAndVerifyFiltering() throws Exception {
        SubpopulationsApi subpopulationsApi = developer.getClient(SubpopulationsApi.class);
        
        TestUser user = TestUserHelper.createAndSignInUser(SubpopulationTest.class, false);
        user.signOut();
        try {
            Criteria criteria1 = new Criteria();
            criteria1.setMinAppVersions(ImmutableMap.of("Android", 0));
            criteria1.setMaxAppVersions(ImmutableMap.of("Android", 10));
            subpop1 = new Subpopulation();
            subpop1.setName("Consent Group 1");
            subpop1.setCriteria(criteria1);
            subpop1.setRequired(true);
            
            Criteria criteria2 = new Criteria();
            criteria2.setMinAppVersions(ImmutableMap.of("Android", 11));
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
        info.appName(Tests.APP_NAME);
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
