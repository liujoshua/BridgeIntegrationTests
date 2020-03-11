package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.sagebionetworks.bridge.rest.model.ResourceCategory.DATA_REPOSITORY;
import static org.sagebionetworks.bridge.rest.model.ResourceCategory.LICENSE;
import static org.sagebionetworks.bridge.rest.model.Role.DEVELOPER;
import static org.sagebionetworks.bridge.sdk.integration.Tests.SUBSTUDY_ID_1;
import static org.sagebionetworks.bridge.sdk.integration.Tests.SUBSTUDY_ID_2;
import static org.sagebionetworks.bridge.sdk.integration.Tests.randomIdentifier;

import java.io.IOException;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.api.AssessmentsApi;
import org.sagebionetworks.bridge.rest.api.SharedAssessmentsApi;
import org.sagebionetworks.bridge.rest.api.SubstudiesApi;
import org.sagebionetworks.bridge.rest.exceptions.UnauthorizedException;
import org.sagebionetworks.bridge.rest.model.Assessment;
import org.sagebionetworks.bridge.rest.model.AssessmentList;
import org.sagebionetworks.bridge.rest.model.ExternalResource;
import org.sagebionetworks.bridge.rest.model.ExternalResourceList;
import org.sagebionetworks.bridge.rest.model.RequestParams;
import org.sagebionetworks.bridge.rest.model.Substudy;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;

public class AssessmentResourceTest {

    private static final String URL = "https://www.synapse.org/#!Synapse:syn4993293/wiki/247859";
    private static final String TITLE = "mPower Public Research Portal";
    private TestUser admin;
    private TestUser developer;
    private TestUser otherDeveloper;
    private String id;
    private AssessmentsApi assessmentApi;
    private SharedAssessmentsApi sharedAssessmentsApi;
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
        sharedAssessmentsApi = developer.getClient(SharedAssessmentsApi.class);
        badDevApi = otherDeveloper.getClient(AssessmentsApi.class);
        id = randomIdentifier(AssessmentTest.class);
    }

    @After
    public void after() throws IOException {
        if (developer != null) {
            developer.signOutAndDeleteUser();
        }
        TestUser admin = TestUserHelper.getSignedInAdmin();
        AssessmentsApi api = admin.getClient(AssessmentsApi.class);
        SharedAssessmentsApi sharedApi = admin.getClient(SharedAssessmentsApi.class);

        AssessmentList revisions = api.getAssessmentRevisionsById(id, 0, 10, true).execute().body();
        for (Assessment oneRevision : revisions.getItems()) {
            api.deleteAssessment(oneRevision.getGuid(), true).execute();
        }
        
        revisions = sharedApi.getSharedAssessmentRevisionsById(id, null, null, true).execute().body();
        for (Assessment oneRevision : revisions.getItems()) {
            sharedApi.deleteSharedAssessment(oneRevision.getGuid(), true).execute();
        }
    }

    @Test
    public void test() throws Exception {
        Assessment unsavedAssessment = new Assessment().identifier(id).title("AssessmentResourceTest assessment")
                .osName("Android").ownerId(SUBSTUDY_ID_1).revision(1L);

        Assessment assessment = assessmentApi.createAssessment(unsavedAssessment).execute().body();

        // create a resource
        ExternalResource resource = new ExternalResource().title(TITLE).url(URL).category(DATA_REPOSITORY)
                .minRevision(1).maxRevision(1).publishers(ImmutableList.of("Sage Bionetworks"));

        resource = assessmentApi.createAssessmentResource(assessment.getIdentifier(), resource).execute().body();
        assertNotNull(resource.getGuid());
        assertEquals(TITLE, resource.getTitle());
        assertEquals(URL, resource.getUrl());
        assertTrue(resource.isUpToDate());
        Long version = resource.getVersion();

        // retrieve the resource testing all the things
        ExternalResourceList resourcesPage = assessmentApi.getAssessmentResources(
                assessment.getIdentifier(), null, null, null, null, null, false).execute().body();
        assertEquals(1, resourcesPage.getItems().size());
        assertEquals(Integer.valueOf(1), resourcesPage.getTotal());
        resource = resourcesPage.getItems().get(0);
        assertEquals(TITLE, resource.getTitle());
        assertEquals(URL, resource.getUrl());
        assertTrue(resource.isUpToDate());
        assertEquals(version, resource.getVersion());

        // change assessment...
        assessment.setTitle("new title");
        assessment.setRevision(2L);
        assessmentApi.updateAssessment(assessment.getGuid(), assessment).execute().body();
        
        // verify the resource is no longer up-to-date
        resource = assessmentApi.getAssessmentResource(assessment.getIdentifier(), resource.getGuid()).execute()
                .body();
        assertFalse(resource.isUpToDate());
        
        // update the resource. this should increment the version.
        resource.setUrl(URL + "2");
        resource = assessmentApi.updateAssessmentResource(assessment.getIdentifier(), resource.getGuid(), resource)
                .execute().body();
        assertEquals(URL + "2", resource.getUrl());
        assertNotEquals(version, resource.getVersion());
        assertTrue(resource.isUpToDate());

        // test paging values:
        
        // Does retrieve item with right category and min/max revision set (all matching)
        resourcesPage = assessmentApi.getAssessmentResources(assessment.getIdentifier(), null, null,
                ImmutableList.of("data_repository"), 1, 1, null).execute().body();
        assertFalse(resourcesPage.getItems().isEmpty());

        // minRevision is too high to match
        resourcesPage = assessmentApi.getAssessmentResources(assessment.getIdentifier(), null, null, 
                null, 2, null, null).execute().body();
        assertTrue(resourcesPage.getItems().isEmpty());

        // maxRevision is higher and that's fine
        resourcesPage = assessmentApi.getAssessmentResources(assessment.getIdentifier(), null, null, 
                null, null, 2, null).execute().body();
        assertFalse(resourcesPage.getItems().isEmpty());

        // category doesn't match
        resourcesPage = assessmentApi.getAssessmentResources(assessment.getIdentifier(), null, null,
                ImmutableList.of("license"), null, null, null).execute().body();
        assertTrue(resourcesPage.getItems().isEmpty());
        
        RequestParams rp = resourcesPage.getRequestParams();
        assertEquals(ImmutableList.of(LICENSE), rp.getCategories());
        assertEquals(Integer.valueOf(50), rp.getPageSize());
        assertEquals(Integer.valueOf(0), rp.getOffsetBy());
        assertFalse(rp.isIncludeDeleted());

        // Other developers can see local resources, just as they can see the assessment
        resourcesPage = badDevApi.getAssessmentResources(
                assessment.getIdentifier(), null, null, null, null, null, null).execute().body();
        assertFalse(resourcesPage.getItems().isEmpty());
        
        // however, they cannot update these resources
        try {
            resource.setTitle("This will never be persisted");
            badDevApi.updateAssessmentResource(assessment.getIdentifier(), resource.getGuid(), resource).execute();
            fail("Should have thrown exception");
        } catch(UnauthorizedException e) {
            // expected
        }

        // publish the assessment, the original resource should have been deleted it
        assessmentApi.publishAssessment(assessment.getGuid()).execute().body();

        // Resource has been published along with the assessment.
        resourcesPage = sharedAssessmentsApi.getSharedAssessmentResources(id, null, 
                null, null, null, null, null).execute().body();
        assertEquals(1, resourcesPage.getItems().size());
        assertEquals(TITLE, resourcesPage.getItems().get(0).getTitle());
        
        // publish assessment again. there should be two records, one deleted and one not.
        assessmentApi.publishAssessment(assessment.getGuid()).execute().body();
        
        resourcesPage = sharedAssessmentsApi.getSharedAssessmentResources(id, null, null, 
                null, null, null, true).execute().body();
        long countDeleted = resourcesPage.getItems().stream().filter(res -> res.isDeleted()).count();
        long countNotDeleted = resourcesPage.getItems().stream().filter(res -> !res.isDeleted()).count();
        assertEquals(1, countDeleted);
        assertEquals(1, countNotDeleted);
        
        // get the shared assessment that's not deleted for this next step
        Assessment sharedAssessment = sharedAssessmentsApi.getLatestSharedAssessmentRevision(id).execute().body(); 
        
        // import the assessment back to the local context.
        sharedAssessmentsApi.importSharedAssessment(sharedAssessment.getGuid(), SUBSTUDY_ID_1).execute();

        resourcesPage = assessmentApi.getAssessmentResources(id, null, null, null, null, null, true).execute().body();
        countDeleted = resourcesPage.getItems().stream().filter(res -> res.isDeleted()).count();
        countNotDeleted = resourcesPage.getItems().stream().filter(res -> !res.isDeleted()).count();
        assertEquals(1, countDeleted);
        assertEquals(1, countNotDeleted);
        
        // delete the new resource and verify the delete flag works
        for (ExternalResource oneResource : resourcesPage.getItems()) {
            if (!oneResource.isDeleted()) {
                assessmentApi.deleteAssessmentResource(id, oneResource.getGuid(), false).execute().body();        
            }
        }
        
        resourcesPage = assessmentApi.getAssessmentResources(assessment.getIdentifier(), null, null, 
                null, null, null, true).execute().body();
        assertFalse(resourcesPage.getItems().isEmpty());

        resourcesPage = assessmentApi.getAssessmentResources(assessment.getIdentifier(), null, null, 
                null, null, null, false).execute().body();
        assertTrue(resourcesPage.getItems().isEmpty());
    }
}
