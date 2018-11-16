package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.joda.time.DateTime;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sagebionetworks.bridge.rest.ClientManager;
import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.api.ParticipantsApi;
import org.sagebionetworks.bridge.rest.api.SubstudiesApi;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.exceptions.InvalidEntityException;
import org.sagebionetworks.bridge.rest.model.IdentifierHolder;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.SignIn;
import org.sagebionetworks.bridge.rest.model.SignUp;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.rest.model.Substudy;
import org.sagebionetworks.bridge.rest.model.SubstudyList;
import org.sagebionetworks.bridge.rest.model.VersionHolder;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.util.IntegTestUtils;

import com.google.common.collect.ImmutableList;

public class SubstudyTest {
    
    private List<String> substudyIdsToDelete = new ArrayList<>();
    private List<String> userIdsToDelete = new ArrayList<>();
    private TestUser testResearcher;
    
    @Before
    public void before() throws Exception { 
        testResearcher = TestUserHelper.createAndSignInUser(SubstudyTest.class, false, Role.RESEARCHER);
    }
    
    @After
    public void deleteResearcher() throws Exception {
        if (testResearcher != null) {
            testResearcher.signOutAndDeleteUser();
        }
    }
    
    @After
    public void after() throws Exception {
        TestUser admin = TestUserHelper.getSignedInAdmin();
        ForAdminsApi adminsApi = admin.getClient(ForAdminsApi.class);
        for (String userId : userIdsToDelete) {
            try {
                adminsApi.deleteUser(userId).execute();
            } catch(EntityNotFoundException e) {
            }
        }
        for (String substudyId : substudyIdsToDelete) {
            try {
                adminsApi.deleteSubstudy(substudyId, true).execute();    
            } catch(EntityNotFoundException e) {
            }
        }
    }

    @Test
    public void test() throws IOException {
        TestUser admin = TestUserHelper.getSignedInAdmin();
        
        SubstudiesApi substudiesApi = admin.getClient(SubstudiesApi.class);
        
        String id = Tests.randomIdentifier(SubstudyTest.class);
        Substudy substudy = new Substudy().id(id).name("Substudy Test");
        
        VersionHolder holder = substudiesApi.createSubstudy(substudy).execute().body();
        substudy.setVersion(holder.getVersion());
        substudyIdsToDelete.add(id);
        
        Substudy retrieved = substudiesApi.getSubstudy(id).execute().body();
        assertEquals(id, retrieved.getId());
        assertEquals("Substudy Test", retrieved.getName());
        assertTrue(retrieved.getCreatedOn().isAfter(DateTime.now().minusHours(1)));
        assertTrue(retrieved.getModifiedOn().isAfter(DateTime.now().minusHours(1)));
        DateTime lastModified1 = retrieved.getModifiedOn();
        
        substudy.name("New test name");
        VersionHolder holder2 = substudiesApi.updateSubstudy(id, substudy).execute().body();
        assertNotEquals(holder.getVersion(), holder2.getVersion());
        
        Substudy retrieved2 = substudiesApi.getSubstudy(id).execute().body();
        assertEquals("New test name", retrieved2.getName());
        assertNotEquals(lastModified1, retrieved2.getModifiedOn());
        
        SubstudyList list = substudiesApi.getSubstudies(false).execute().body();
        assertEquals(1, list.getItems().size());
        assertFalse(list.getRequestParams().isIncludeDeleted());
        
        // logically delete it
        substudiesApi.deleteSubstudy(id, false).execute();
        
        list = substudiesApi.getSubstudies(false).execute().body();
        assertTrue(list.getItems().isEmpty());
        
        list = substudiesApi.getSubstudies(true).execute().body();
        assertEquals(1, list.getItems().size());
        assertTrue(list.getRequestParams().isIncludeDeleted());
        
        // you can still retrieve it
        Substudy retrieved3 = substudiesApi.getSubstudy(id).execute().body();
        assertNotNull(retrieved3);
        
        // physically delete it
        substudiesApi.deleteSubstudy(id, true).execute();
        
        // Now it's really gone
        list = substudiesApi.getSubstudies(true).execute().body();
        assertTrue(list.getItems().isEmpty());
        
        try {
            substudiesApi.getSubstudy(id).execute();
            fail("Should have thrown an exception");
        } catch(EntityNotFoundException e) {
        }
    }
    
    @Test
    public void usersAreTaintedBySubstudyAssociation() throws Exception {
        // Create a substudy for this test.
        TestUser admin = TestUserHelper.getSignedInAdmin();
        
        String id1 = Tests.randomIdentifier(SubstudyTest.class);
        Substudy substudy1 = new Substudy().id(id1).name("Substudy 1 Test");

        String id2 = Tests.randomIdentifier(SubstudyTest.class);
        Substudy substudy2 = new Substudy().id(id2).name("Substudy 2 Test");
        
        SubstudiesApi substudiesApi = admin.getClient(SubstudiesApi.class);
        substudiesApi.createSubstudy(substudy1).execute();
        substudyIdsToDelete.add(id1);
        substudiesApi.createSubstudy(substudy2).execute();
        substudyIdsToDelete.add(id2);
        
        // Create a user associated to this sub-study.
        String researcherEmail = IntegTestUtils.makeEmail(SubstudyTest.class);
        SignUp researcherSignUp = new SignUp().email(researcherEmail).password("P@ssword`1").study(IntegTestUtils.STUDY_ID);
        researcherSignUp.roles(ImmutableList.of(Role.RESEARCHER));
        researcherSignUp.substudyIds(ImmutableList.of(id1));

        ForAdminsApi adminApi = admin.getClient(ForAdminsApi.class);
        String researcherId = adminApi.createUser(researcherSignUp).execute().body().getId();
        userIdsToDelete.add(researcherId);
        
        ParticipantsApi participantApi = testResearcher.getClient(ParticipantsApi.class);
        StudyParticipant researcher = participantApi.getParticipantById(researcherId, false).execute().body();
        assertEquals(id1, researcher.getSubstudyIds().get(0));
        
        // Cannot associate this user to a non-existent sub-study
        try {
            researcher.setSubstudyIds(ImmutableList.of(id1, "bad-id"));
            participantApi.updateParticipant(researcherId, researcher).execute().body();
            fail("Should have thrown exception");
        } catch(InvalidEntityException e) {
            assertEquals("substudyIds[bad-id] is not a substudy", e.getErrors().get("substudyIds[bad-id]").get(0));
        }
        
        // Sign in this researcher, verify all the rules.
        ClientManager manager = new ClientManager.Builder()
                .withSignIn(new SignIn().email(researcherEmail).password("P@ssword`1").study(IntegTestUtils.STUDY_ID))
                .build();
        ParticipantsApi participantsApi = manager.getClient(ParticipantsApi.class);

        String email2 = IntegTestUtils.makeEmail(SubstudyTest.class);
        SignUp signUp2 = new SignUp().email(email2).password("P@ssword`1").study(IntegTestUtils.STUDY_ID);
        
        // Cannot sign this user up because the substudies include one the researcher does not possess.
        try {
            signUp2.substudyIds(ImmutableList.of(id1, id2));
            participantsApi.createParticipant(signUp2).execute().body();
            fail("Should have thrown exception");
        } catch(InvalidEntityException e) {
            assertTrue(e.getMessage().contains("is not a substudy of the caller"));
        }
        
        // Assigning no substudies also does not work
        try {
            signUp2.substudyIds(ImmutableList.of());
            participantsApi.createParticipant(signUp2).execute().body();
            fail("Should have thrown exception");
        } catch(InvalidEntityException e) {
            assertTrue(e.getMessage().contains("must be assigned to this participant"));
        }
        
        // User can be created if it has at least one substudy from the researcher creating it
        signUp2.substudyIds(ImmutableList.of(id1));
        IdentifierHolder keys = participantsApi.createParticipant(signUp2).execute().body();
        userIdsToDelete.add(keys.getIdentifier());
    }
}
