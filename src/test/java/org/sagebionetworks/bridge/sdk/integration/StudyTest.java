package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.sagebionetworks.bridge.rest.model.Role.STUDY_COORDINATOR;
import static org.sagebionetworks.bridge.sdk.integration.Tests.ORG_ID_1;
import static org.sagebionetworks.bridge.sdk.integration.Tests.ORG_ID_2;
import static org.sagebionetworks.bridge.sdk.integration.Tests.PASSWORD;
import static org.sagebionetworks.bridge.sdk.integration.Tests.STUDY_ID_1;
import static org.sagebionetworks.bridge.sdk.integration.Tests.STUDY_ID_2;
import static org.sagebionetworks.bridge.util.IntegTestUtils.SAGE_ID;
import static org.sagebionetworks.bridge.util.IntegTestUtils.TEST_APP_ID;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.joda.time.DateTime;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.api.OrganizationsApi;
import org.sagebionetworks.bridge.rest.api.ParticipantsApi;
import org.sagebionetworks.bridge.rest.api.StudiesApi;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.exceptions.UnauthorizedException;
import org.sagebionetworks.bridge.rest.model.OrganizationList;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.SignUp;
import org.sagebionetworks.bridge.rest.model.Study;
import org.sagebionetworks.bridge.rest.model.StudyList;
import org.sagebionetworks.bridge.rest.model.VersionHolder;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.util.IntegTestUtils;

import com.google.common.collect.ImmutableMap;

public class StudyTest {
    
    private List<String> studyIdsToDelete = new ArrayList<>();
    private List<String> userIdsToDelete = new ArrayList<>();
    private TestUser testResearcher;
    private TestUser admin;
    
    @Before
    public void before() throws Exception {
        admin = TestUserHelper.getSignedInAdmin();
        testResearcher = TestUserHelper.createAndSignInUser(StudyTest.class, false, Role.RESEARCHER);
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
        
        OrganizationList orgList = studiesApi.getSponsors(id, 0, 100).execute().body();
        assertTrue(orgList.getItems().stream().anyMatch(org -> org.getIdentifier().equals(SAGE_ID)));
        
        DateTime lastModified1 = retrieved.getModifiedOn();
        
        study.name("New test name " + id);
        VersionHolder holder2 = studiesApi.updateStudy(id, study).execute().body();
        assertNotEquals(holder.getVersion(), holder2.getVersion());
        
        Study retrieved2 = studiesApi.getStudy(id).execute().body();
        assertEquals("New test name " + id, retrieved2.getName());
        assertNotEquals(lastModified1, retrieved2.getModifiedOn());
        
        StudyList studyList = studiesApi.getStudies(null, null, false).execute().body();
        assertEquals(initialCount+1, studyList.getItems().size());
        assertFalse(studyList.getRequestParams().isIncludeDeleted());
        
        // logically delete it
        studiesApi.deleteStudy(id, false).execute();
        
        studyList = studiesApi.getStudies(null, null, false).execute().body();
        assertEquals(initialCount, studyList.getItems().size());
        
        studyList = studiesApi.getStudies(null, null, true).execute().body();
        assertTrue(studyList.getItems().size() > initialCount);
        assertTrue(studyList.getRequestParams().isIncludeDeleted());
        
        // you can still retrieve it
        Study retrieved3 = studiesApi.getStudy(id).execute().body();
        assertNotNull(retrieved3);
        
        // physically delete it
        studiesApi.deleteStudy(id, true).execute();
        
        // Now it's really gone
        studyList = studiesApi.getStudies(null, null, true).execute().body();
        assertEquals(initialCount, studyList.getItems().size());
        
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
        admin.getClient(OrganizationsApi.class).addStudySponsorship(ORG_ID_1, id1).execute();
        admin.getClient(OrganizationsApi.class).addStudySponsorship(ORG_ID_2, id2).execute();
        
        TestUser studyCoordinator = TestUserHelper.createAndSignInUser(StudyTest.class, true, STUDY_COORDINATOR);
        userIdsToDelete.add(studyCoordinator.getUserId());
        
        admin.getClient(OrganizationsApi.class).addMember(ORG_ID_1, studyCoordinator.getUserId()).execute();
        ParticipantsApi participantApi = studyCoordinator.getClient(ParticipantsApi.class);
        
        // Cannot associate this user to a non-existent study
        try {
            OrganizationsApi orgsApi = admin.getClient(OrganizationsApi.class);
            orgsApi.addMember("bad-id", studyCoordinator.getUserId()).execute();
            fail("Should have thrown exception");
        } catch(EntityNotFoundException e) {
            assertEquals("Organization not found.", e.getMessage());
        }
        
        // Cannot sign this user up because the enrollment includes one the study coordinator does not possess.
        String email2 = IntegTestUtils.makeEmail(StudyTest.class);
        SignUp signUp2 = new SignUp().email(email2).password(PASSWORD).appId(TEST_APP_ID)
                .externalIds(ImmutableMap.of(STUDY_ID_1, Tests.randomIdentifier(StudyTest.class), 
                        STUDY_ID_2, "cannot-work"));
        try {
            participantApi.createParticipant(signUp2).execute().body();
            fail("Should have thrown exception");
        } catch(UnauthorizedException e) {
            assertEquals("Caller does not have permission to access this service.", e.getMessage());
        }
    }
    
    @Test
    public void testSponsorship() throws Exception {
        StudiesApi adminStudiesApi = admin.getClient(StudiesApi.class);
        
        String tempStudyId = Tests.randomIdentifier(StudyTest.class);
        Study tempStudy = new Study().identifier(tempStudyId).name(tempStudyId);
        adminStudiesApi.createStudy(tempStudy).execute();
        studyIdsToDelete.add(tempStudyId);
        
        adminStudiesApi.addStudySponsor(tempStudyId, ORG_ID_1).execute();
        
        OrganizationList list = adminStudiesApi.getSponsors(tempStudyId, null, null).execute().body();
        assertTrue(list.getItems().stream().anyMatch((org) -> org.getIdentifier().equals(ORG_ID_1)));

        // The organization should see this as a sponsored study
        StudyList studyList = admin.getClient(OrganizationsApi.class).getSponsoredStudies(ORG_ID_1, null, null).execute().body();
        assertTrue(studyList.getItems().stream().anyMatch((study) -> study.getIdentifier().equals(tempStudyId)));

        adminStudiesApi.deleteStudy(tempStudyId, false).execute();
        
        // Now if we ask, we should not see this as a sponsored study
        studyList = admin.getClient(OrganizationsApi.class).getSponsoredStudies(ORG_ID_1, null, null).execute().body();
        assertFalse(studyList.getItems().stream().anyMatch((study) -> study.getIdentifier().equals(tempStudyId)));
        
        adminStudiesApi.removeStudySponsor(tempStudyId, ORG_ID_1).execute();

        list = adminStudiesApi.getSponsors(tempStudyId, null, null).execute().body();
        assertFalse(list.getItems().stream().anyMatch((org) -> org.getIdentifier().equals(ORG_ID_1)));
            
        adminStudiesApi.deleteStudy(tempStudyId, true).execute();
    }    
}
