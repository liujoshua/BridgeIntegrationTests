package org.sagebionetworks.bridge.sdk.integration;

import static java.util.stream.Collectors.toSet;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.sagebionetworks.bridge.rest.model.ResourceCategory.DATA_REPOSITORY;
import static org.sagebionetworks.bridge.rest.model.ResourceCategory.LICENSE;
import static org.sagebionetworks.bridge.rest.model.Role.DEVELOPER;
import static org.sagebionetworks.bridge.sdk.integration.Tests.ORG_ID_1;
import static org.sagebionetworks.bridge.sdk.integration.Tests.ORG_ID_2;
import static org.sagebionetworks.bridge.sdk.integration.Tests.randomIdentifier;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import com.google.common.collect.ImmutableList;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.api.AssessmentsApi;
import org.sagebionetworks.bridge.rest.api.OrganizationsApi;
import org.sagebionetworks.bridge.rest.api.SharedAssessmentsApi;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.exceptions.UnauthorizedException;
import org.sagebionetworks.bridge.rest.model.Assessment;
import org.sagebionetworks.bridge.rest.model.AssessmentList;
import org.sagebionetworks.bridge.rest.model.ExternalResource;
import org.sagebionetworks.bridge.rest.model.Organization;
import org.sagebionetworks.bridge.rest.model.PagedExternalResourceList;
import org.sagebionetworks.bridge.rest.model.RequestParams;
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
        id = randomIdentifier(AssessmentResourceTest.class);
        admin = TestUserHelper.getSignedInAdmin();
        OrganizationsApi orgsApi = admin.getClient(OrganizationsApi.class);

        // Getting ahead of our skis here, as we haven't refactored substudies to be organizations
        // and we're already using them that way.
        try {
            orgsApi.getOrganization(ORG_ID_1).execute().body();
        } catch(EntityNotFoundException e) {
            Organization org = new Organization().identifier(ORG_ID_1).name(ORG_ID_1);
            orgsApi.createOrganization(org).execute();
        }
        try {
            orgsApi.getOrganization(ORG_ID_2).execute().body();
        } catch(EntityNotFoundException e) {
            Organization org = new Organization().identifier(ORG_ID_2).name(ORG_ID_2);
            orgsApi.createOrganization(org).execute();
        }

        developer = new TestUserHelper.Builder(AssessmentResourceTest.class).withRoles(DEVELOPER).createAndSignInUser();
        orgsApi.addMember(ORG_ID_1, developer.getUserId()).execute();
        
        otherDeveloper = new TestUserHelper.Builder(AssessmentResourceTest.class).withRoles(DEVELOPER)
                .createAndSignInUser();
        orgsApi.addMember(ORG_ID_2, otherDeveloper.getUserId()).execute();
        
        assessmentApi = developer.getClient(AssessmentsApi.class);
        sharedAssessmentsApi = developer.getClient(SharedAssessmentsApi.class);
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
                .osName("Android").ownerId(ORG_ID_1).revision(1L);

        Assessment assessment = assessmentApi.createAssessment(unsavedAssessment).execute().body();
        
        ExternalResource resource = new ExternalResource().title(TITLE).url(URL).category(DATA_REPOSITORY)
                .minRevision(1).maxRevision(1).publishers(ImmutableList.of("Sage Bionetworks"));

        // create a resource
        resource = assessmentApi.createAssessmentResource(id, resource).execute().body();
        assertNotNull(resource.getGuid());
        assertEquals(TITLE, resource.getTitle());
        assertEquals(URL, resource.getUrl());
        assertTrue(resource.isUpToDate());
        Long version = resource.getVersion();
        
        String resourceGuid = resource.getGuid();

        // retrieve the resource and test some fields
        PagedExternalResourceList resourcesPage = assessmentApi.getAssessmentResources(
                id, null, null, null, null, null, false).execute().body();
        assertEquals(1, resourcesPage.getItems().size());
        assertEquals(Integer.valueOf(1), resourcesPage.getTotal());
        resource = resourcesPage.getItems().get(0);
        assertEquals(TITLE, resource.getTitle());
        assertEquals(URL, resource.getUrl());
        assertTrue(resource.isUpToDate());
        assertEquals(version, resource.getVersion());

        // change and update assessment the assessment
        assessment.setTitle("new title");
        assessment.setRevision(2L);
        assessment = assessmentApi.updateAssessment(assessment.getGuid(), assessment).execute().body();
        
        // verify the resource is no longer up-to-date
        resource = assessmentApi.getAssessmentResource(id, resourceGuid).execute().body();
        assertFalse(resource.isUpToDate());
        
        // update the resource. this should increment the version.
        resource.setUrl(URL + "2");
        resource = assessmentApi.updateAssessmentResource(id, resourceGuid, resource).execute().body();
        assertEquals(URL + "2", resource.getUrl());
        assertNotEquals(version, resource.getVersion());
        assertTrue(resource.isUpToDate());
        
        // Does retrieve item with right category and min/max revision set (all matching)
        resourcesPage = assessmentApi.getAssessmentResources(id, null, null,
                ImmutableList.of("data_repository"), 1, 1, null).execute().body();
        assertFalse(resourcesPage.getItems().isEmpty());

        // minRevision is too high to match
        resourcesPage = assessmentApi.getAssessmentResources(id, null, null, 
                null, 2, null, null).execute().body();
        assertTrue(resourcesPage.getItems().isEmpty());

        // maxRevision is higher and that's fine
        resourcesPage = assessmentApi.getAssessmentResources(id, null, null, 
                null, null, 2, null).execute().body();
        assertFalse(resourcesPage.getItems().isEmpty());

        // category doesn't match
        resourcesPage = assessmentApi.getAssessmentResources(id, null, null,
                ImmutableList.of("license"), null, null, null).execute().body();
        assertTrue(resourcesPage.getItems().isEmpty());
        
        RequestParams rp = resourcesPage.getRequestParams();
        assertEquals(ImmutableList.of(LICENSE), rp.getCategories());
        assertEquals(Integer.valueOf(50), rp.getPageSize());
        assertEquals(Integer.valueOf(0), rp.getOffsetBy());
        assertFalse(rp.isIncludeDeleted());

        // Other developers can see local resources, just as they can see the assessment
        resourcesPage = badDevApi.getAssessmentResources(
                id, null, null, null, null, null, null).execute().body();
        assertFalse(resourcesPage.getItems().isEmpty());
        
        // however, they cannot update these resources
        try {
            resource.setTitle("This will never be persisted");
            badDevApi.updateAssessmentResource(id, resourceGuid, resource).execute();
            fail("Should have thrown exception");
        } catch(UnauthorizedException e) {
            // expected
        }

        // publish the assessment and resource
        assessmentApi.publishAssessment(assessment.getGuid(), null).execute().body();
        assessmentApi.publishAssessmentResource(id, ImmutableList.of(resourceGuid)).execute().body();

        // Resource should be published along with the assessment.
        PagedExternalResourceList sharedResourcesPage = sharedAssessmentsApi.getSharedAssessmentResources(
                id, null, null, null, null, null, null).execute().body();
        assertEquals(1, sharedResourcesPage.getItems().size());
        assertEquals(TITLE, sharedResourcesPage.getItems().get(0).getTitle());
        
        // publish assessment resource again. the existing record should be updated
        resource.setTitle("This is a different title");
        resource = assessmentApi.updateAssessmentResource(id, resourceGuid, resource).execute().body();

        ExternalResource sharedResource = assessmentApi.publishAssessmentResource(id, 
                ImmutableList.of(resourceGuid)).execute().body().getItems().get(0);
        
        sharedResourcesPage = sharedAssessmentsApi.getSharedAssessmentResources(id, null, null, 
                null, null, null, true).execute().body();
        assertEquals(sharedResourcesPage.getItems().get(0).getTitle(), "This is a different title");
        
        // get individual shared assessment
        sharedResource = sharedAssessmentsApi.getSharedAssessmentResource(id, sharedResource.getGuid()).execute().body();
        assertNotNull(sharedResource);
        
        // import the assessment and resource back to the local context.
        sharedResource.setTitle("This is a different shared title");
        sharedResource = sharedAssessmentsApi.updateSharedAssessmentResource(
                id, resourceGuid, sharedResource).execute().body();
        sharedAssessmentsApi.importSharedAssessmentResource(id, ImmutableList.of(resourceGuid)).execute();

        resourcesPage = assessmentApi.getAssessmentResources(id, null, null, null, null, null, true).execute().body();
        
        assertEquals(resourcesPage.getItems().get(0).getTitle(), "This is a different shared title");
        
        // Okay, after these copies, there should still be one resource for the local and for the shared objects
        int localCount = assessmentApi.getAssessmentResources(id, null, null, null, null, null, false).execute().body()
                .getTotal();
        int sharedCount = sharedAssessmentsApi.getSharedAssessmentResources(id, null, null, null, null, null, false)
                .execute().body().getTotal();
        assertEquals(localCount, 1);
        assertEquals(sharedCount, 1);
        
        // logically delete the local resource
        assessmentApi.deleteAssessmentResource(id, resource.getGuid(), false).execute();
        localCount = assessmentApi.getAssessmentResources(
                id, null, null, null, null, null, false).execute().body().getTotal();
        assertEquals(localCount, 0);
        localCount = assessmentApi.getAssessmentResources(
                id, null, null, null, null, null, true).execute().body().getTotal();
        assertEquals(localCount, 1);
        // can still be retrieved with knowledge of the GUID
        resource = assessmentApi.getAssessmentResource(id, resource.getGuid()).execute().body();
        assertNotNull(resource);
        
        // logically delete the shared resource
        admin.getClient(SharedAssessmentsApi.class).deleteSharedAssessmentResource(
                id, sharedResource.getGuid(), false).execute();
        sharedCount = sharedAssessmentsApi.getSharedAssessmentResources(
                id, null, null, null, null, null, false).execute().body().getTotal();
        assertEquals(0, sharedCount);
        sharedCount = sharedAssessmentsApi.getSharedAssessmentResources(
                id, null, null, null, null, null, true).execute().body().getTotal();
        assertEquals(1, sharedCount);
        sharedResource = sharedAssessmentsApi.getSharedAssessmentResource(id, sharedResource.getGuid()).execute().body();
        assertNotNull(sharedResource);
        
        // test paging values
        for (int i=0; i < 10; i++) {
            ExternalResource res = assessmentApi.createAssessmentResource(id, resource).execute().body();;
            assessmentApi.publishAssessmentResource(id, ImmutableList.of(res.getGuid())).execute();
        }
        PagedExternalResourceList page1 = assessmentApi.getAssessmentResources(id, 0, 5, null, null, null, false)
                .execute().body();        
        PagedExternalResourceList page2 = assessmentApi.getAssessmentResources(id, 5, 5, null, null, null, false)
                .execute().body();
        PagedExternalResourceList page3 = assessmentApi.getAssessmentResources(id, 10, 5, null, null, null, false)
                .execute().body();
        // should be 10 unique GUIDs, last page notwithstanding
        Set<String> allGuids = new HashSet<>();
        allGuids.addAll(page1.getItems().stream().map(ExternalResource::getGuid).collect(toSet()));
        allGuids.addAll(page2.getItems().stream().map(ExternalResource::getGuid).collect(toSet()));
        allGuids.addAll(page3.getItems().stream().map(ExternalResource::getGuid).collect(toSet()));
        assertEquals(10, allGuids.size());
        
        page1 = sharedAssessmentsApi.getSharedAssessmentResources(id, 0, 5, null, null, null, false)
                .execute().body();        
        page2 = sharedAssessmentsApi.getSharedAssessmentResources(id, 5, 5, null, null, null, false)
                .execute().body();
        page3 = sharedAssessmentsApi.getSharedAssessmentResources(id, 10, 5, null, null, null, false)
                .execute().body();
        // should be 10 unique GUIDs, last page notwithstanding
        allGuids.clear();
        allGuids.addAll(page1.getItems().stream().map(ExternalResource::getGuid).collect(toSet()));
        allGuids.addAll(page2.getItems().stream().map(ExternalResource::getGuid).collect(toSet()));
        allGuids.addAll(page3.getItems().stream().map(ExternalResource::getGuid).collect(toSet()));
        assertEquals(10, allGuids.size());
    }
}
