package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.sagebionetworks.bridge.rest.model.Role.DEVELOPER;
import static org.sagebionetworks.bridge.sdk.integration.Tests.API_SIGNIN;
import static org.sagebionetworks.bridge.sdk.integration.Tests.SHARED_SIGNIN;
import static org.sagebionetworks.bridge.sdk.integration.Tests.SUBSTUDY_ID_1;
import static org.sagebionetworks.bridge.sdk.integration.Tests.randomIdentifier;
import static org.sagebionetworks.bridge.util.IntegTestUtils.STUDY_ID;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.common.collect.ImmutableList;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.api.AssessmentsApi;
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
    private static String MARKER_TAG = "markerTag";

    private TestUser developer;
    
    private String id;
    
    @Before
    public void before() throws IOException {
        developer = TestUserHelper.createAndSignInUser(AssessmentTest.class, false, DEVELOPER);
        
        TestUser admin = TestUserHelper.getSignedInAdmin();
        SubstudiesApi subApi = admin.getClient(SubstudiesApi.class);
        
        // Getting ahead of our skis here, as we haven't refactored substudies to be organizations
        // and we're already using them that way.
        Substudy org = subApi.getSubstudy(SUBSTUDY_ID_1).execute().body();
        if (org == null) {
            Substudy substudy = new Substudy().id(SUBSTUDY_ID_1).name(SUBSTUDY_ID_1);
            subApi.createSubstudy(substudy).execute();
        }
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
        id = randomIdentifier(AssessmentTest.class);
        
        // This isn't usable until the configuration is implemented, but 
        // verify it is persisted corrrectly
        Map<String, List<String>> customizationFields = new HashMap<>();
        customizationFields.put("node1", ImmutableList.of("field1", "field2"));
        customizationFields.put("node2", ImmutableList.of("field3", "field4"));
        
        AssessmentsApi api = developer.getClient(AssessmentsApi.class);
        
        Assessment unsavedAssessment = new Assessment()
                .identifier(id)
                .title("Title")
                .summary("Summary")
                .validationStatus("Not validated")
                .normingStatus("Not normed")
                .osName("Android")
                .ownerId(SUBSTUDY_ID_1)
                .tags(ImmutableList.of(MARKER_TAG))
                .categories(CATEGORIES)
                .customizationFields(customizationFields);
        
        Assessment firstRevision = api.createAssessment(unsavedAssessment).execute().body();
        
        assertEquals(id, firstRevision.getIdentifier());
        assertEquals("Title", firstRevision.getTitle());
        assertEquals("Summary", firstRevision.getSummary());
        assertEquals("Not validated", firstRevision.getValidationStatus());
        assertEquals("Not normed", firstRevision.getNormingStatus());
        assertEquals("Android", firstRevision.getOsName());
        assertEquals(SUBSTUDY_ID_1, firstRevision.getOwnerId());
        assertTrue(firstRevision.getTags().contains(MARKER_TAG));
        assertTrue(firstRevision.getCategories().contains("cat1"));
        assertTrue(firstRevision.getCategories().contains("cat2"));
        assertEquals(customizationFields, firstRevision.getCustomizationFields());
        assertEquals(Long.valueOf(1), firstRevision.getRevision());
        assertEquals(Long.valueOf(1L), firstRevision.getVersion());
        
        // Creating again will fail on the fact that the identifier exists
        try {
            api.createAssessment(unsavedAssessment).execute().body();
            fail("Should have thrown an exception");
        } catch(EntityAlreadyExistsException e) {
        }
        
        // This will fail because the substudy doesn't exist
        unsavedAssessment.setOwnerId("not-real");
        unsavedAssessment.setIdentifier(randomIdentifier(AssessmentTest.class));
        try {
            api.createAssessment(unsavedAssessment).execute();
            fail("Should have thrown an exception");
        } catch(UnauthorizedException e) {
        }
        
        // Get by GUID
        Assessment retValueByGuid = api.getAssessmentByGUID(firstRevision.getGuid()).execute().body();
        assertEquals(firstRevision.getGuid(), retValueByGuid.getGuid());
        
        // Get by ID
        Assessment retValueById = api.getAssessmentById(id, firstRevision.getRevision()).execute().body();
        assertEquals(firstRevision.getGuid(), retValueById.getGuid());
        
        // Create a second revision
        firstRevision.setIdentifier(id);
        firstRevision.setOwnerId(SUBSTUDY_ID_1);
        firstRevision.setTitle("Title 2");
        firstRevision.setRevision(2L);
        // Note that the GUIDs here don't matter at all, which makes this an odd API. Should enforce this?
        Assessment secondRevision = api.createAssessmentRevision(firstRevision.getGuid(), firstRevision).execute().body();
        
        // Get all revisions by the GUID of a single revision
        AssessmentList list = api.getAssessmentRevisionsByGUID(secondRevision.getGuid(), null, null, false).execute().body();
        assertEquals(2, list.getItems().size());
        assertEquals(Integer.valueOf(2), list.getTotal());
        
        // The latest revision is returned by these methods...
        Assessment latest = api.getLatestAssessmentRevision(id).execute().body();
        assertEquals(secondRevision.getGuid(), latest.getGuid());
        assertEquals(secondRevision.getTitle(), latest.getTitle());
        
        AssessmentList allAssessments = api.getAssessments(null, null, null, ImmutableList.of(MARKER_TAG), false)
                .execute().body();
        assertEquals(1, allAssessments.getItems().size());
        assertEquals(Integer.valueOf(1), allAssessments.getTotal());
        assertEquals(secondRevision.getGuid(), allAssessments.getItems().get(0).getGuid());
        
        // Update a revision
        secondRevision.setTitle("Title 3");
        Assessment secondRevUpdated = api.updateAssessment(secondRevision.getGuid(), secondRevision).execute().body();
        assertEquals(secondRevision.getTitle(), secondRevUpdated.getTitle());
        assertTrue(secondRevision.getVersion() < secondRevUpdated.getVersion());
        
        // Logically delete the latest revision
        api.deleteAssessment(secondRevision.getGuid(), false).execute();
        
        // Now the first version is the latest
        latest = api.getLatestAssessmentRevision(id).execute().body();
        assertEquals(firstRevision.getGuid(), latest.getGuid());
        // We have to use unsaved because we modified the first draft to create the second draft.
        assertEquals(unsavedAssessment.getTitle(), latest.getTitle());
        
        allAssessments = api.getAssessments(null, null, null, ImmutableList.of(MARKER_TAG), false).execute().body();
        assertEquals(1, allAssessments.getItems().size());
        assertEquals(Integer.valueOf(1), allAssessments.getTotal());
        assertEquals(firstRevision.getGuid(), allAssessments.getItems().get(0).getGuid());
        
        // Shared Assessments
        firstRevision = api.publishAssessment(firstRevision.getGuid()).execute().body();
        
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
        // The revision of this thing should be 3 because there are 2 copies (one deleted, but it doesn't matter)
        assertEquals(Long.valueOf(3L), newAssessment.getRevision());
        
        // Clean up API study assessments
        AssessmentList revisions = api.getAssessmentRevisionsById(id, null, null, true).execute().body();
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
        list = api.getAssessments(null, null, null, null, true).execute().body();
        assertTrue(list.getItems().isEmpty());
    }
}
