package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.sagebionetworks.bridge.rest.model.Role.DEVELOPER;
import static org.sagebionetworks.bridge.sdk.integration.Tests.API_SIGNIN;
import static org.sagebionetworks.bridge.sdk.integration.Tests.SHARED_SIGNIN;
import static org.sagebionetworks.bridge.sdk.integration.Tests.SUBSTUDY_ID_1;
import static org.sagebionetworks.bridge.sdk.integration.Tests.SUBSTUDY_ID_2;
import static org.sagebionetworks.bridge.sdk.integration.Tests.randomIdentifier;
import static org.sagebionetworks.bridge.util.IntegTestUtils.STUDY_ID;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.api.AssessmentsApi;
import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.api.ForSuperadminsApi;
import org.sagebionetworks.bridge.rest.api.SharedAssessmentsApi;
import org.sagebionetworks.bridge.rest.api.SubstudiesApi;
import org.sagebionetworks.bridge.rest.exceptions.EntityAlreadyExistsException;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.exceptions.UnauthorizedException;
import org.sagebionetworks.bridge.rest.model.Assessment;
import org.sagebionetworks.bridge.rest.model.AssessmentList;
import org.sagebionetworks.bridge.rest.model.Substudy;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;

public class AssessmentTest {
    private static final ImmutableList<String> CATEGORIES = ImmutableList.of("cat1", "cat2");
    
    // This isn't usable until the configuration is implemented, but 
    // verify it is persisted corrrectly
    private static final Map<String, List<String>> CUSTOMIZATION_FIELDS = ImmutableMap.of(
            "node1", ImmutableList.of("field1", "field2"),
            "node2", ImmutableList.of("field3", "field4"));

    private TestUser admin;
    private TestUser developer;
    private TestUser otherDeveloper;
    private String id;
    private String markerTag;
    private AssessmentsApi assessmentApi;
    private AssessmentsApi badDevApi;
    
    @Before
    public void before() throws Exception {
        admin = TestUserHelper.getSignedInAdmin();
        SubstudiesApi subApi = admin.getClient(SubstudiesApi.class);
        
        // Getting ahead of our skis here, as we haven't refactored substudies to be organizations
        // and we're already using them that way.
        Substudy org = subApi.getSubstudy(SUBSTUDY_ID_1).execute().body();
        if (org == null) {
            Substudy substudy = new Substudy().id(SUBSTUDY_ID_1).name(SUBSTUDY_ID_1);
            subApi.createSubstudy(substudy).execute();
        }
        org = subApi.getSubstudy(SUBSTUDY_ID_2).execute().body();
        if (org == null) {
            Substudy substudy = new Substudy().id(SUBSTUDY_ID_2).name(SUBSTUDY_ID_2);
            subApi.createSubstudy(substudy).execute();
        }
        
        developer = new TestUserHelper.Builder(AssessmentTest.class).withRoles(DEVELOPER)
                .withSubstudyIds(ImmutableSet.of(SUBSTUDY_ID_1)).createAndSignInUser();
        otherDeveloper = new TestUserHelper.Builder(AssessmentTest.class).withRoles(DEVELOPER)
                .withSubstudyIds(ImmutableSet.of(SUBSTUDY_ID_2)).createAndSignInUser();
        assessmentApi = developer.getClient(AssessmentsApi.class);
        badDevApi = otherDeveloper.getClient(AssessmentsApi.class);
        id = randomIdentifier(AssessmentTest.class);
        markerTag = randomIdentifier(AssessmentTest.class);
    }
    
    @After
    public void deleteDeveloper() throws IOException {
        if (developer != null) {
            developer.signOutAndDeleteUser();            
        }
        TestUser admin = TestUserHelper.getSignedInAdmin();
        AssessmentsApi api = admin.getClient(AssessmentsApi.class);
        
        try {
            AssessmentList revisions = api.getAssessmentRevisionsById(id, null, null, true).execute().body();
            for (Assessment revision : revisions.getItems()) {
                api.deleteAssessment(revision.getGuid(), true).execute();
            }
        } catch(EntityNotFoundException e) {
            // this is okay, the test concludes by testing deletion.
        }
    }
    
    @Test
    public void test() throws Exception {
        // createAssessment works
        Assessment unsavedAssessment = new Assessment()
                .identifier(id)
                .title("Title")
                .summary("Summary")
                .validationStatus("Not validated")
                .normingStatus("Not normed")
                .osName("Android")
                .ownerId(SUBSTUDY_ID_1)
                .tags(ImmutableList.of(markerTag))
                .categories(CATEGORIES)
                .customizationFields(CUSTOMIZATION_FIELDS);
        
        Assessment firstRevision = assessmentApi.createAssessment(unsavedAssessment).execute().body();
        assertFields(firstRevision);
        
        // createAssessment fails when identifier already exists
        try {
            assessmentApi.createAssessment(unsavedAssessment).execute().body();
            fail("Should have thrown an exception");
        } catch(EntityAlreadyExistsException e) {
        }
        
        // createAssessment fails when substudy does not exist
        unsavedAssessment.setOwnerId("not-real");
        unsavedAssessment.setIdentifier(randomIdentifier(AssessmentTest.class));
        try {
            assessmentApi.createAssessment(unsavedAssessment).execute();
            fail("Should have thrown an exception");
        } catch(UnauthorizedException e) {
            // Note: this is a strange exception to be throwing, why not ENFE?
        }
        
        // getAssessmentByGUID works
        Assessment retValueByGuid = assessmentApi.getAssessmentByGUID(firstRevision.getGuid()).execute().body();
        assertEquals(firstRevision.getGuid(), retValueByGuid.getGuid());
        
        // getAssessmentById works
        Assessment retValueById = assessmentApi.getAssessmentById(id, firstRevision.getRevision()).execute().body();
        assertEquals(firstRevision.getGuid(), retValueById.getGuid());
        // verify fields
        assertFields(retValueById);
        
        // createAssessmentRevision works
        firstRevision.setIdentifier(id);
        firstRevision.setOwnerId(SUBSTUDY_ID_1);
        firstRevision.setTitle("Title 2");
        firstRevision.setRevision(2L);
        // Note: that the GUIDs here don't matter at all, which makes this an odd API. Should enforce this?
        Assessment secondRevision = assessmentApi.createAssessmentRevision(firstRevision.getGuid(), firstRevision).execute().body();
        
        // getAssessmentRevisionsByGUID works
        AssessmentList list = assessmentApi.getAssessmentRevisionsByGUID(secondRevision.getGuid(), null, null, false).execute().body();
        assertEquals(2, list.getItems().size());
        assertEquals(Integer.valueOf(2), list.getTotal());
        
        // getAssessmentRevisionsByGUID fails correctly when GUID not found
        try {
            assessmentApi.getAssessmentRevisionsByGUID("nonsense guid", null, null, false).execute();
            fail("Should have thrown exception");
        } catch(EntityNotFoundException e) {
            assertEquals(e.getMessage(), "Assessment not found.");
        }
        
        // getLatestAssessmentRevision works
        Assessment latest = assessmentApi.getLatestAssessmentRevision(id).execute().body();
        assertEquals(secondRevision.getGuid(), latest.getGuid());
        assertEquals(secondRevision.getTitle(), latest.getTitle());
        
        // getAssessments works
        AssessmentList allAssessments = assessmentApi.getAssessments(
                null, null, null, ImmutableList.of(markerTag), false).execute().body();
        assertEquals(1, allAssessments.getItems().size());
        assertEquals(Integer.valueOf(1), allAssessments.getTotal());
        assertEquals(secondRevision.getGuid(), allAssessments.getItems().get(0).getGuid());
        
        // updateAssessment works
        secondRevision.setTitle("Title 3");
        Assessment secondRevUpdated = assessmentApi.updateAssessment(secondRevision.getGuid(), secondRevision).execute().body();
        assertEquals(secondRevision.getTitle(), secondRevUpdated.getTitle());
        assertTrue(secondRevision.getVersion() < secondRevUpdated.getVersion());
        
        // updateAssessment fails for developer who doesn't own the assessment
        try {
            secondRevision.setSummary("This will never be persisted");
            badDevApi.updateAssessment(secondRevision.getGuid(), secondRevision).execute();
            fail("Should have thrown an exception");
        } catch(UnauthorizedException e) {
        }
        
        // deleteAssessment physical=false works
        assessmentApi.deleteAssessment(secondRevision.getGuid(), false).execute();
        
        // deleteAssessment physical=false fails for developer who doesn't own the assessment
        try {
            badDevApi.deleteAssessment(secondRevision.getGuid(), false).execute();
            fail("Should have thrown an exception");
        } catch(EntityNotFoundException e) {
        }
        
        // now the first version is the latest
        latest = assessmentApi.getLatestAssessmentRevision(id).execute().body();
        assertEquals(firstRevision.getGuid(), latest.getGuid());
        // (we have to use unsaved because we modified the first draft to create the second draft)
        assertEquals(unsavedAssessment.getTitle(), latest.getTitle());
        
        // getAssessmentRevisionsByGUID works
        allAssessments = assessmentApi.getAssessmentRevisionsByGUID(firstRevision.getGuid(), null, null, false).execute().body();
        assertEquals(1, allAssessments.getItems().size());
        assertEquals(Integer.valueOf(1), allAssessments.getTotal());
        assertEquals(firstRevision.getGuid(), allAssessments.getItems().get(0).getGuid());
        
        // getAssessmentRevisionsByGUID respects the logical delete flag
        allAssessments = assessmentApi.getAssessmentRevisionsByGUID(firstRevision.getGuid(), null, null, true).execute().body();
        assertEquals(2, allAssessments.getItems().size());
        assertEquals(Integer.valueOf(2), allAssessments.getTotal());
        
        // getAssessmentRevisionsById works
        allAssessments = assessmentApi.getAssessmentRevisionsById(id, 0, 50, false).execute().body();
        assertEquals(1, allAssessments.getItems().size());
        assertEquals(Integer.valueOf(1), allAssessments.getTotal());
        assertEquals(firstRevision.getGuid(), allAssessments.getItems().get(0).getGuid());
        
        // getAssessmentRevisionsById respects the logical delete flag
        allAssessments = assessmentApi.getAssessmentRevisionsById(id, 0, 50, true).execute().body();
        assertEquals(2, allAssessments.getItems().size());
        assertEquals(Integer.valueOf(2), allAssessments.getTotal());

        // getAssessmentRevisionsById bad offset just returns an empty list
        allAssessments = assessmentApi.getAssessmentRevisionsById(id, 20, 50, true).execute().body();
        assertEquals(0, allAssessments.getItems().size());
        assertEquals(Integer.valueOf(2), allAssessments.getTotal());
        
        // getAssessmentRevisionsById bad id throws an ENFE
        try {
            assessmentApi.getAssessmentRevisionsById("bad id", 0, 50, true).execute().body();
            fail("Should have thrown exception");
        } catch(EntityNotFoundException e) {
        }
        
        // SHARED ASSESSMENTS LIFECYCLE
        
        firstRevision = assessmentApi.publishAssessment(firstRevision.getGuid()).execute().body();
        
        SharedAssessmentsApi sharedApi = developer.getClient(SharedAssessmentsApi.class);
        Assessment shared = sharedApi.getLatestSharedAssessmentRevision(id).execute().body();
        
        assertEquals(shared.getGuid(), firstRevision.getOriginGuid());
        assertEquals(STUDY_ID+":"+SUBSTUDY_ID_1, shared.getOwnerId());
        
        assertNotEquals(firstRevision.getGuid(), shared.getGuid());
        assertEquals(firstRevision.getIdentifier(), shared.getIdentifier());

        TestUser admin = TestUserHelper.getSignedInAdmin();
        ForSuperadminsApi superAdminApi = admin.getClient(ForSuperadminsApi.class);
        SharedAssessmentsApi adminSharedApi = admin.getClient(SharedAssessmentsApi.class);

        // Import a shared assessment back into the study
        Assessment newAssessment = sharedApi.importSharedAssessment(shared.getGuid(), SUBSTUDY_ID_1).execute().body();        
        assertEquals(shared.getGuid(), newAssessment.getOriginGuid());
        assertEquals(SUBSTUDY_ID_1, newAssessment.getOwnerId());
        assertNotEquals(shared.getGuid(), newAssessment.getGuid());
        // The revision of this thing should be 3 because there are two copies in app (one is logically 
        // deleted, but this does not break things)
        assertEquals(Long.valueOf(3L), newAssessment.getRevision());
        
        // deleteAssessment physical=true works
        admin.getClient(ForAdminsApi.class).deleteAssessment(secondRevision.getGuid(), true).execute();
        list = assessmentApi.getAssessments(
                null, null, null, ImmutableList.of(markerTag), true).execute().body();
        assertEquals(1, list.getItems().size());
        assertEquals(Integer.valueOf(1), list.getTotal());
        assertEquals(Long.valueOf(3L), list.getItems().get(0).getRevision());
        
        // clean up shared assessments. You have to delete dependent assessments first or it's
        // a ConstraintViolationException
        AssessmentList revisions = assessmentApi.getAssessmentRevisionsById(id, null, null, true).execute().body();
        AssessmentsApi adminAssessmentsApi = admin.getClient(AssessmentsApi.class);
        for (Assessment revision : revisions.getItems()) {
            adminAssessmentsApi.deleteAssessment(revision.getGuid(), true).execute();
        }
        try {
            superAdminApi.adminChangeStudy(SHARED_SIGNIN).execute();
            adminSharedApi.deleteSharedAssessment(shared.getGuid(), true).execute().body();
        } finally {
            superAdminApi.adminChangeStudy(API_SIGNIN).execute();
        }
        // Should all be gone...
        list = sharedApi.getSharedAssessments(null, null, null, ImmutableList.of(markerTag), true).execute().body();
        assertTrue(list.getItems().isEmpty());
    }

    private void assertFields(Assessment firstRevision) {
        assertEquals(id, firstRevision.getIdentifier());
        assertEquals("Title", firstRevision.getTitle());
        assertEquals("Summary", firstRevision.getSummary());
        assertEquals("Not validated", firstRevision.getValidationStatus());
        assertEquals("Not normed", firstRevision.getNormingStatus());
        assertEquals("Android", firstRevision.getOsName());
        assertEquals(SUBSTUDY_ID_1, firstRevision.getOwnerId());
        assertTrue(firstRevision.getTags().contains(markerTag));
        assertTrue(firstRevision.getCategories().contains("cat1"));
        assertTrue(firstRevision.getCategories().contains("cat2"));
        assertEquals(CUSTOMIZATION_FIELDS, firstRevision.getCustomizationFields());
        assertEquals(Long.valueOf(1), firstRevision.getRevision());
        assertEquals(Long.valueOf(1L), firstRevision.getVersion());
    }
}
