package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.sagebionetworks.bridge.sdk.integration.Tests.ORG_ID_1;
import static org.sagebionetworks.bridge.sdk.integration.Tests.STUDY_ID_1;
import static org.sagebionetworks.bridge.util.IntegTestUtils.TEST_APP_ID;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.joda.time.DateTime;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sagebionetworks.bridge.rest.ClientManager;
import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.api.OrganizationsApi;
import org.sagebionetworks.bridge.rest.api.ParticipantsApi;
import org.sagebionetworks.bridge.rest.api.StudiesApi;
import org.sagebionetworks.bridge.rest.exceptions.BadRequestException;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.exceptions.InvalidEntityException;
import org.sagebionetworks.bridge.rest.model.IdentifierHolder;
import org.sagebionetworks.bridge.rest.model.Organization;
import org.sagebionetworks.bridge.rest.model.OrganizationList;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.SignIn;
import org.sagebionetworks.bridge.rest.model.SignUp;
import org.sagebionetworks.bridge.rest.model.Study;
import org.sagebionetworks.bridge.rest.model.StudyList;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.rest.model.VersionHolder;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.util.IntegTestUtils;

import com.google.common.collect.ImmutableList;

public class StudyTest {
    
    private List<String> studyIdsToDelete = new ArrayList<>();
    private List<String> userIdsToDelete = new ArrayList<>();
    private TestUser testResearcher;
    private TestUser admin;
    private String tempStudyId;
    
    @Before
    public void before() throws Exception {
        admin = TestUserHelper.getSignedInAdmin();
        testResearcher = TestUserHelper.createAndSignInUser(StudyTest.class, false, Role.RESEARCHER);
        
        OrganizationsApi orgsApi = admin.getClient(OrganizationsApi.class);
        try {
            orgsApi.getOrganization(ORG_ID_1).execute();
        } catch(EntityNotFoundException e) {
            Organization org = new Organization().identifier(ORG_ID_1).name(ORG_ID_1);
            orgsApi.createOrganization(org).execute();
        }
        StudiesApi studiesApi = admin.getClient(StudiesApi.class);
        try {
            studiesApi.getStudy(STUDY_ID_1).execute();
        } catch(EntityNotFoundException e) {
            Study study = new Study().identifier(STUDY_ID_1).name(STUDY_ID_1);
            studiesApi.createStudy(study).execute();
        }
    }
    
    @After
    public void deleteResearcher() throws Exception {
        if (testResearcher != null) {
            testResearcher.signOutAndDeleteUser();
        }
    }
    
    @After
    public void after() throws Exception {
        ForAdminsApi adminsApi = admin.getClient(ForAdminsApi.class);
        for (String userId : userIdsToDelete) {
            try {
                adminsApi.deleteUser(userId).execute();
            } catch(EntityNotFoundException e) {
            }
        }
        for (String studyId : studyIdsToDelete) {
            try {
                adminsApi.deleteStudy(studyId, true).execute();    
            } catch(EntityNotFoundException e) {
            }
        }
        if (tempStudyId != null) {
            try {
                adminsApi.deleteStudy(tempStudyId, true).execute();    
            } catch(EntityNotFoundException e) {
            }
        }
    }

    @Test
    public void test() throws IOException {
        StudiesApi studiesApi = admin.getClient(StudiesApi.class);
        
        int initialCount = studiesApi.getStudies(null, null, false).execute().body().getItems().size();
        
        String id = Tests.randomIdentifier(StudyTest.class);
        Study study = new Study().identifier(id).name("Study " + id);
        
        VersionHolder holder = studiesApi.createStudy(study).execute().body();
        study.setVersion(holder.getVersion());
        studyIdsToDelete.add(id);
        
        Study retrieved = studiesApi.getStudy(id).execute().body();
        assertEquals(id, retrieved.getIdentifier());
        assertEquals("Study " + id, retrieved.getName());
        assertTrue(retrieved.getCreatedOn().isAfter(DateTime.now().minusHours(1)));
        assertTrue(retrieved.getModifiedOn().isAfter(DateTime.now().minusHours(1)));
        DateTime lastModified1 = retrieved.getModifiedOn();
        
        study.name("New test name " + id);
        VersionHolder holder2 = studiesApi.updateStudy(id, study).execute().body();
        assertNotEquals(holder.getVersion(), holder2.getVersion());
        
        Study retrieved2 = studiesApi.getStudy(id).execute().body();
        assertEquals("New test name " + id, retrieved2.getName());
        assertNotEquals(lastModified1, retrieved2.getModifiedOn());
        
        StudyList list = studiesApi.getStudies(null, null, false).execute().body();
        assertEquals(initialCount+1, list.getItems().size());
        assertFalse(list.getRequestParams().isIncludeDeleted());
        
        // logically delete it
        studiesApi.deleteStudy(id, false).execute();
        
        list = studiesApi.getStudies(null, null, false).execute().body();
        assertEquals(initialCount, list.getItems().size());
        
        list = studiesApi.getStudies(null, null, true).execute().body();
        assertEquals(initialCount+1, list.getItems().size());
        assertTrue(list.getRequestParams().isIncludeDeleted());
        
        // you can still retrieve it
        Study retrieved3 = studiesApi.getStudy(id).execute().body();
        assertNotNull(retrieved3);
        
        // physically delete it
        studiesApi.deleteStudy(id, true).execute();
        
        // Now it's really gone
        list = studiesApi.getStudies(null, null, true).execute().body();
        assertEquals(initialCount, list.getItems().size());
        
        try {
            studiesApi.getStudy(id).execute();
            fail("Should have thrown an exception");
        } catch(EntityNotFoundException e) {
        }
    }
    
    @Test
    public void usersAreTaintedByStudyAssociation() throws Exception {
        // Create a study for this test.
        
        String id1 = Tests.randomIdentifier(StudyTest.class);
        Study study1 = new Study().identifier(id1).name("Study " + id1);

        String id2 = Tests.randomIdentifier(StudyTest.class);
        Study study2 = new Study().identifier(id2).name("Study " + id2);
        
        StudiesApi studiesApi = admin.getClient(StudiesApi.class);
        studiesApi.createStudy(study1).execute();
        studyIdsToDelete.add(id1);
        studiesApi.createStudy(study2).execute();
        studyIdsToDelete.add(id2);
        
        // Create a user associated to this sub-study.
        String researcherEmail = IntegTestUtils.makeEmail(StudyTest.class);
        SignUp researcherSignUp = new SignUp().email(researcherEmail).password("P@ssword`1").appId(TEST_APP_ID);
        researcherSignUp.roles(ImmutableList.of(Role.RESEARCHER));
        researcherSignUp.studyIds(ImmutableList.of(id1));

        ForAdminsApi adminApi = admin.getClient(ForAdminsApi.class);
        String researcherId = adminApi.createUser(researcherSignUp).execute().body().getId();
        userIdsToDelete.add(researcherId);
        
        ParticipantsApi participantApi = testResearcher.getClient(ParticipantsApi.class);
        StudyParticipant researcher = participantApi.getParticipantById(researcherId, false).execute().body();
        assertEquals(id1, researcher.getStudyIds().get(0));
        
        // Cannot associate this user to a non-existent sub-study
        try {
            researcher.setStudyIds(ImmutableList.of(id1, "bad-id"));
            participantApi.updateParticipant(researcherId, researcher).execute().body();
            fail("Should have thrown exception");
        } catch(InvalidEntityException e) {
            assertEquals("studyIds[bad-id] is not a study", e.getErrors().get("studyIds[bad-id]").get(0));
        }
        
        // Sign in this researcher, verify all the rules.
        ClientManager manager = new ClientManager.Builder()
                .withSignIn(new SignIn().email(researcherEmail).password("P@ssword`1").appId(TEST_APP_ID))
                .build();
        ParticipantsApi participantsApi = manager.getClient(ParticipantsApi.class);

        String email2 = IntegTestUtils.makeEmail(StudyTest.class);
        SignUp signUp2 = new SignUp().email(email2).password("P@ssword`1").appId(TEST_APP_ID);
        
        // Cannot sign this user up because the studies include one the researcher does not possess.
        try {
            signUp2.studyIds(ImmutableList.of(id1, id2));
            participantsApi.createParticipant(signUp2).execute().body();
            fail("Should have thrown exception");
        } catch(BadRequestException e) {
            assertTrue(e.getMessage().contains("is not a study of the caller"));
        }
        
        // Assigning no studies also does not work
        try {
            signUp2.studyIds(ImmutableList.of());
            participantsApi.createParticipant(signUp2).execute().body();
            fail("Should have thrown exception");
        } catch(BadRequestException e) {
            assertTrue(e.getMessage().contains("must be assigned to one or more of these studies"));
        }
        
        // User can be created if it has at least one study from the researcher creating it
        signUp2.studyIds(ImmutableList.of(id1));
        IdentifierHolder keys = participantsApi.createParticipant(signUp2).execute().body();
        userIdsToDelete.add(keys.getIdentifier());
    }
    
    @Test
    public void testSponsorship() throws Exception {
        StudiesApi adminStudiesApi = admin.getClient(StudiesApi.class);
        
        tempStudyId = Tests.randomIdentifier(StudyTest.class);
        Study tempStudy = new Study().identifier(tempStudyId).name(tempStudyId);
        adminStudiesApi.createStudy(tempStudy).execute();
        
        adminStudiesApi.addStudySponsor(tempStudyId, ORG_ID_1).execute();
        
        OrganizationList list = adminStudiesApi.getSponsors(tempStudyId, null, null).execute().body();
        assertTrue(list.getItems().stream().anyMatch((org) -> org.getIdentifier().equals(ORG_ID_1)));

        // The organization should see this as a sponsored study
        StudyList studyList = admin.getClient(OrganizationsApi.class).getSponsoredStudies(ORG_ID_1, null, null).execute().body();
        assertTrue(studyList.getItems().stream().anyMatch((study) -> study.getIdentifier().equals(tempStudyId)));

        adminStudiesApi.deleteStudy(tempStudyId, false).execute();
        
        // Now if we ask, we should not see this as a sponsored study
        studyList = admin.getClient(OrganizationsApi.class).getSponsoredStudies(ORG_ID_1, null, null).execute().body();
        assertFalse(studyList.getItems().stream().anyMatch((study) -> study.getIdentifier().equals(STUDY_ID_1)));
        
        adminStudiesApi.removeStudySponsor(tempStudyId, ORG_ID_1).execute();

        list = adminStudiesApi.getSponsors(tempStudyId, null, null).execute().body();
        assertFalse(list.getItems().stream().anyMatch((org) -> org.getIdentifier().equals(ORG_ID_1)));
            
        adminStudiesApi.deleteStudy(tempStudyId, true).execute();
    }    
}
