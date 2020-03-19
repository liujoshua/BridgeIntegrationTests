package org.sagebionetworks.bridge.sdk.integration;

import static java.util.stream.Collectors.toSet;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.api.AssessmentsApi;
import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.api.ForSuperadminsApi;
import org.sagebionetworks.bridge.rest.api.SharedAssessmentsApi;
import org.sagebionetworks.bridge.rest.api.SubstudiesApi;
import org.sagebionetworks.bridge.rest.api.TagsApi;
import org.sagebionetworks.bridge.rest.exceptions.EntityAlreadyExistsException;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.exceptions.UnauthorizedException;
import org.sagebionetworks.bridge.rest.model.Assessment;
import org.sagebionetworks.bridge.rest.model.AssessmentList;
import org.sagebionetworks.bridge.rest.model.Substudy;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;

public class AssessmentTest {
    private static final String TITLE = "Title";
    private static final String TAG1 = "category:cat1";
    private static final String TAG2 = "category:cat2";
    
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
        id = randomIdentifier(AssessmentTest.class);
        markerTag = "test:" + randomIdentifier(AssessmentTest.class);

        admin = TestUserHelper.getSignedInAdmin();
        SubstudiesApi subApi = admin.getClient(SubstudiesApi.class);
        
        // Getting ahead of our skis here, as we haven't refactored substudies to be organizations
        // and we're already using them that way.
        try {
            subApi.getSubstudy(SUBSTUDY_ID_1).execute();
        } catch (EntityNotFoundException ex) {
            Substudy substudy = new Substudy().id(SUBSTUDY_ID_1).name(SUBSTUDY_ID_1);
            subApi.createSubstudy(substudy).execute();
        }
        try {
            subApi.getSubstudy(SUBSTUDY_ID_2).execute().body();
        } catch (EntityNotFoundException ex) {
            Substudy substudy = new Substudy().id(SUBSTUDY_ID_2).name(SUBSTUDY_ID_2);
            subApi.createSubstudy(substudy).execute();
        }
        
        developer = new TestUserHelper.Builder(AssessmentTest.class).withRoles(DEVELOPER)
                .withSubstudyIds(ImmutableSet.of(SUBSTUDY_ID_1)).createAndSignInUser();
        otherDeveloper = new TestUserHelper.Builder(AssessmentTest.class).withRoles(DEVELOPER)
                .withSubstudyIds(ImmutableSet.of(SUBSTUDY_ID_2)).createAndSignInUser();
        assessmentApi = developer.getClient(AssessmentsApi.class);
        badDevApi = otherDeveloper.getClient(AssessmentsApi.class);
    }
    
    @After
    public void after() throws IOException {
        if (developer != null) {
            developer.signOutAndDeleteUser();            
        }
        if (otherDeveloper != null) {
            otherDeveloper.signOutAndDeleteUser();
        }
        TestUser admin = TestUserHelper.getSignedInAdmin();
        AssessmentsApi api = admin.getClient(AssessmentsApi.class);
        SharedAssessmentsApi sharedApi = admin.getClient(SharedAssessmentsApi.class);
        
        AssessmentList assessments = api.getAssessments(
                null, null, ImmutableList.of(markerTag), true).execute().body();
        for (Assessment oneAssessment : assessments.getItems()) {
            AssessmentList revisions = api.getAssessmentRevisionsById(
                    oneAssessment.getIdentifier(), null, null, true).execute().body();
            for (Assessment revision : revisions.getItems()) {
                api.deleteAssessment(revision.getGuid(), true).execute();
            }
        }
        AssessmentList sharedAssessments = sharedApi.getSharedAssessments(
                null, null, ImmutableList.of(markerTag), true).execute().body();
        for (Assessment oneSharedAssessment : sharedAssessments.getItems()) {
            AssessmentList revisions = sharedApi.getSharedAssessmentRevisionsById(
                    oneSharedAssessment.getIdentifier(), null, null, true).execute().body();
            for (Assessment revision : revisions.getItems()) {
                sharedApi.deleteSharedAssessment(revision.getGuid(), true).execute();
            }
        }
        TagsApi tagsApi = admin.getClient(TagsApi.class);
        tagsApi.deleteTag(TAG1).execute();
        tagsApi.deleteTag(TAG2).execute();
        tagsApi.deleteTag(markerTag).execute();
    }
    
    @Test
    public void test() throws Exception {
        // createAssessment works
        Assessment unsavedAssessment = new Assessment()
                .identifier(id)
                .title(TITLE)
                .summary("Summary")
                .validationStatus("Not validated")
                .normingStatus("Not normed")
                .osName("Android")
                .ownerId(SUBSTUDY_ID_1)
                .tags(ImmutableList.of(markerTag, TAG1, TAG2))
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
                null, null, ImmutableList.of(markerTag), false).execute().body();
        assertEquals(1, allAssessments.getItems().size());
        assertEquals(Integer.valueOf(1), allAssessments.getTotal());
        assertEquals(secondRevision.getGuid(), allAssessments.getItems().get(0).getGuid());
        
        // getAssessments works without tags
        allAssessments = assessmentApi.getAssessments(
                null, null, null, false).execute().body();
        assertTrue(allAssessments.getTotal() > 0);
        
        // getAssessments works with multiple tags
        allAssessments = assessmentApi.getAssessments(
                null, null, ImmutableList.of(markerTag, TAG1, TAG2), false).execute().body();
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
        assertEquals(TITLE, latest.getTitle());
        
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

        Assessment sharedByGuid = sharedApi.getSharedAssessmentByGUID(
                shared.getGuid()).execute().body();
        assertEquals(sharedByGuid.getGuid(), shared.getGuid());
        
        Assessment sharedById = sharedApi.getSharedAssessmentById(
                shared.getIdentifier(), firstRevision.getRevision()).execute().body();
        assertEquals(sharedById.getGuid(), shared.getGuid());
        
        shared.setTitle("new title");
        shared.setSummary("new summary");
        Assessment sharedUpdated = sharedApi.updateSharedAssessment(shared.getGuid(), shared).execute().body();
        assertEquals(sharedUpdated.getTitle(), "new title");
        assertEquals(sharedUpdated.getSummary(), "new summary");
        
        // Make an assessment under the same identifier but a different owner... it cannot
        // be published back.
        Assessment otherAssessment = null;
        try {
            SharedAssessmentsApi badDevSharedApi = otherDeveloper.getClient(SharedAssessmentsApi.class);
            otherAssessment = badDevSharedApi.importSharedAssessment(
                    shared.getGuid(), SUBSTUDY_ID_2).execute().body();
            badDevApi.publishAssessment(otherAssessment.getGuid()).execute();
            fail("Should have thrown exception");
        } catch(UnauthorizedException e) {
            assertTrue(e.getMessage().contains("Assessment exists in shared library under a different owner"));
        } finally {
            if (otherAssessment != null) {
                TestUser admin = TestUserHelper.getSignedInAdmin();
                admin.getClient(AssessmentsApi.class).deleteAssessment(otherAssessment.getGuid(), true).execute();
            }
        }
        
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
                null, null, ImmutableList.of(markerTag), true).execute().body();
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
            // test logical delete of shared assessments
            adminSharedApi.deleteSharedAssessment(shared.getGuid(), false).execute().body();
            
            list = sharedApi.getSharedAssessments(null, null, ImmutableList.of(markerTag), false).execute().body();
            assertEquals(Integer.valueOf(0), list.getTotal());
            assertTrue(list.getItems().isEmpty());
            
            list = sharedApi.getSharedAssessments(null, null, ImmutableList.of(markerTag), true).execute().body();
            assertEquals(Integer.valueOf(1), list.getTotal());
            assertEquals(1, list.getItems().size());
            
            adminSharedApi.deleteSharedAssessment(shared.getGuid(), true).execute().body();
        } finally {
            superAdminApi.adminChangeStudy(API_SIGNIN).execute();
        }
        // Should all be gone...
        list = sharedApi.getSharedAssessments(null, null, ImmutableList.of(markerTag), true).execute().body();
        assertTrue(list.getItems().isEmpty());
        
        // PAGING
        
        Set<String> uniqueGuids = new HashSet<>();
        
        // Test paging (10 records with different IDs)
        for (int i=0; i < 10; i++) {
            unsavedAssessment = new Assessment()
                    .identifier(id+i)
                    .title(TITLE)
                    .osName("Android")
                    .ownerId(SUBSTUDY_ID_1)
                    .tags(ImmutableList.of(markerTag, TAG1, TAG2))
                    .customizationFields(CUSTOMIZATION_FIELDS);
            assessmentApi.createAssessment(unsavedAssessment).execute();
        }
        
        AssessmentList page1 = assessmentApi.getAssessments(0, 5, ImmutableList.of(markerTag), false).execute().body();
        assertEquals(Integer.valueOf(10), page1.getTotal());
        assertEquals(5, page1.getItems().size());
        assertEquals(id+"9", page1.getItems().get(0).getIdentifier());
        assertEquals(id+"8", page1.getItems().get(1).getIdentifier());
        assertEquals(id+"7", page1.getItems().get(2).getIdentifier());
        assertEquals(id+"6", page1.getItems().get(3).getIdentifier());
        assertEquals(id+"5", page1.getItems().get(4).getIdentifier());
        uniqueGuids.addAll(page1.getItems().stream().map(Assessment::getGuid).collect(toSet()));
        
        AssessmentList page2 = assessmentApi.getAssessments(5, 5, ImmutableList.of(markerTag), false).execute().body();
        assertEquals(Integer.valueOf(10), page2.getTotal());
        assertEquals(5, page2.getItems().size());
        assertEquals(id+"4", page2.getItems().get(0).getIdentifier());
        assertEquals(id+"3", page2.getItems().get(1).getIdentifier());
        assertEquals(id+"2", page2.getItems().get(2).getIdentifier());
        assertEquals(id+"1", page2.getItems().get(3).getIdentifier());
        assertEquals(id+"0", page2.getItems().get(4).getIdentifier());
        uniqueGuids.addAll(page2.getItems().stream().map(Assessment::getGuid).collect(toSet()));
        
        assertEquals(10, uniqueGuids.size());
        
        Set<String> uniqueRevisionGuidsById = new HashSet<>();
        Set<String> uniqueRevisionGuidsByGuid = new HashSet<>();
        
        // Test paging (10 revisions of the an assessment in the list of 10)
        String parentGuid = page1.getItems().get(0).getGuid();
        String parentId = page1.getItems().get(0).getIdentifier();
        unsavedAssessment = new Assessment()
                .identifier(parentId)
                .title(TITLE)
                .osName("Android")
                .ownerId(SUBSTUDY_ID_1)
                .tags(ImmutableList.of(markerTag, TAG1, TAG2))
                .customizationFields(CUSTOMIZATION_FIELDS);
        for (int i=0; i < 10; i++) {
            unsavedAssessment.setRevision(Long.valueOf(i+2));
            assessmentApi.createAssessmentRevision(parentGuid, unsavedAssessment).execute().body();
        }
        
        AssessmentList page3 = assessmentApi.getAssessmentRevisionsById(parentId, 0, 10, true).execute().body();
        // 11 = the original, plus ten additional revisions
        assertRequestParams(page3, 0, 10, 11, true, 10);
        uniqueRevisionGuidsById.addAll(page3.getItems().stream()
                .map(Assessment::getGuid).collect(toSet()));
        
        AssessmentList page4 = assessmentApi.getAssessmentRevisionsById(parentId, 10, 10, true).execute().body();
        assertRequestParams(page4, 10, 10, 11, true, 1);
        uniqueRevisionGuidsById.addAll(page4.getItems().stream()
                .map(Assessment::getGuid).collect(toSet()));
        
        page3 = assessmentApi.getAssessmentRevisionsByGUID(parentGuid, 0, 10, true).execute().body();
        // 11 = the original, plus ten additional revisions
        assertRequestParams(page3, 0, 10, 11, true, 10);
        uniqueRevisionGuidsByGuid.addAll(page3.getItems().stream()
                .map(Assessment::getGuid).collect(toSet()));
        
        page4 = assessmentApi.getAssessmentRevisionsByGUID(parentGuid, 10, 10, true).execute().body();
        assertRequestParams(page4, 10, 10, 11, true, 1);
        uniqueRevisionGuidsByGuid.addAll(page4.getItems().stream()
                .map(Assessment::getGuid).collect(toSet()));
        
        // There are 11 revisions
        assertEquals(11, uniqueRevisionGuidsById.size());
        assertEquals(11, uniqueRevisionGuidsByGuid.size());
        assertEquals(uniqueRevisionGuidsById, uniqueRevisionGuidsByGuid);
        // so, only one overlapping item between the two sets (the most recent revision returned from 
        // both APIs).
        assertEquals(1, Sets.intersection(uniqueGuids, uniqueRevisionGuidsById).size());
        assertEquals(1, Sets.intersection(uniqueGuids, uniqueRevisionGuidsByGuid).size());
        
        // Publish all of these to shared folder so we can test shared assessment paging. We're 
        // publishing 10 distinct identifiers in one revision (page1 and pag2) and then we're 
        // publishing 10 revisions of one identifier (page3 and page4), so we can test all the 
        // shared paging APIs.
        
        Set<String> allGuids = new HashSet<>();
        allGuids.addAll(uniqueGuids);
        allGuids.addAll(uniqueRevisionGuidsById);
        
        // clear these to verify that the items from the shared APIs are unique
        uniqueGuids.clear();
        uniqueRevisionGuidsById.clear();
        uniqueRevisionGuidsByGuid.clear();
        
        for (String guid : allGuids) {
            assessmentApi.publishAssessment(guid).execute();
        }
        
        AssessmentList sharedPage1 = sharedApi.getSharedAssessments(
                0, 5, ImmutableList.of(markerTag), false).execute().body();
        assertRequestParams(sharedPage1, 0, 5, 10, false, 5);
        uniqueGuids.addAll(sharedPage1.getItems().stream()
                .map(Assessment::getGuid).collect(toSet()));
        
        AssessmentList sharedPage2 = sharedApi.getSharedAssessments(
                5, 5, ImmutableList.of(markerTag), false).execute().body();
        assertRequestParams(sharedPage2, 5, 5, 10, false, 5);
        uniqueGuids.addAll(sharedPage2.getItems().stream()
                .map(Assessment::getGuid).collect(toSet()));
        
        AssessmentList sharedPage3 = sharedApi.getSharedAssessmentRevisionsById(
                parentId, 0, 5, true).execute().body();
        assertRequestParams(sharedPage3, 0, 5, 11, true, 5);
        uniqueRevisionGuidsById.addAll(sharedPage3.getItems().stream()
                .map(Assessment::getGuid).collect(toSet()));
        
        AssessmentList sharedPage4 = sharedApi.getSharedAssessmentRevisionsById(
                parentId, 5, 5, true).execute().body();
        assertRequestParams(sharedPage4, 5, 5, 11, true, 5);
        uniqueRevisionGuidsById.addAll(sharedPage4.getItems().stream()
                .map(Assessment::getGuid).collect(toSet()));
        
        // get that one last item...
        AssessmentList sharedPage5 = sharedApi.getSharedAssessmentRevisionsById(
                parentId, 10, 5, true).execute().body();
        assertRequestParams(sharedPage5, 10, 5, 11, true, 1);
        uniqueRevisionGuidsById.addAll(sharedPage5.getItems().stream()
                .map(Assessment::getGuid).collect(toSet()));
        
        String sharedGuid = sharedPage3.getItems().get(0).getGuid();

        AssessmentList sharedPage6 = sharedApi.getSharedAssessmentRevisionsByGUID(
                sharedGuid, 0, 5, true).execute().body();
        assertRequestParams(sharedPage6, 0, 5, 11, true, 5);
        // Should not change the count, it's the same stuff...
        uniqueRevisionGuidsByGuid.addAll(sharedPage6.getItems().stream()
                .map(Assessment::getGuid).collect(toSet()));
        
        AssessmentList sharedPage7 = sharedApi.getSharedAssessmentRevisionsByGUID(
                sharedGuid, 5, 5, true).execute().body();
        assertRequestParams(sharedPage7, 5, 5, 11, true, 5);
        // Should not change the count, it's the same stuff...
        uniqueRevisionGuidsByGuid.addAll(sharedPage7.getItems().stream()
                .map(Assessment::getGuid).collect(toSet()));
        
        // get that one last item...
        AssessmentList sharedPage8 = sharedApi.getSharedAssessmentRevisionsByGUID(
                sharedGuid, 10, 5, true).execute().body();
        assertRequestParams(sharedPage8, 10, 5, 11, true, 1);
        uniqueRevisionGuidsByGuid.addAll(sharedPage8.getItems().stream()
                .map(Assessment::getGuid).collect(toSet()));
        
        assertEquals(10, uniqueGuids.size());
        assertEquals(11, uniqueRevisionGuidsById.size());
        assertEquals(11, uniqueRevisionGuidsByGuid.size());
    }

    private void assertRequestParams(AssessmentList list, int offsetBy, int pageSize, int total,
            boolean includeDeleted, int actualSize) {
        assertEquals(Integer.valueOf(total), list.getTotal());
        assertEquals(Integer.valueOf(offsetBy), list.getRequestParams().getOffsetBy());
        assertEquals(Integer.valueOf(pageSize), list.getRequestParams().getPageSize());
        assertEquals(includeDeleted, list.getRequestParams().isIncludeDeleted());
        assertEquals(actualSize, list.getItems().size());
        // all the GUIDs are unique.
        Set<String> guids = list.getItems().stream().map(Assessment::getGuid).collect(toSet());
        assertEquals(actualSize, guids.size());
    }
    
    private void assertFields(Assessment assessment) {
        assertEquals(id, assessment.getIdentifier());
        assertEquals(TITLE, assessment.getTitle());
        assertEquals("Summary", assessment.getSummary());
        assertEquals("Not validated", assessment.getValidationStatus());
        assertEquals("Not normed", assessment.getNormingStatus());
        assertEquals("Android", assessment.getOsName());
        assertEquals(SUBSTUDY_ID_1, assessment.getOwnerId());
        assertTrue(assessment.getTags().contains(markerTag));
        assertTrue(assessment.getTags().contains(TAG1));
        assertTrue(assessment.getTags().contains(TAG2));
        assertEquals(CUSTOMIZATION_FIELDS, assessment.getCustomizationFields());
        assertEquals(Long.valueOf(1), assessment.getRevision());
        assertEquals(Long.valueOf(1L), assessment.getVersion());
    }
}
