package org.sagebionetworks.bridge.sdk.integration;

import static java.util.stream.Collectors.toSet;
import static org.junit.Assert.assertEquals;
import static org.sagebionetworks.bridge.rest.model.Role.DEVELOPER;
import static org.sagebionetworks.bridge.sdk.integration.Tests.ORG_ID_1;
import static org.sagebionetworks.bridge.sdk.integration.Tests.randomIdentifier;

import java.io.IOException;
import java.util.Set;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.api.AssessmentsApi;
import org.sagebionetworks.bridge.rest.api.OrganizationsApi;
import org.sagebionetworks.bridge.rest.api.SharedAssessmentsApi;
import org.sagebionetworks.bridge.rest.api.TagsApi;
import org.sagebionetworks.bridge.rest.model.Assessment;
import org.sagebionetworks.bridge.rest.model.AssessmentList;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;

public class AssessmentIdentifierChangeTest {
    private TestUser admin;
    private TestUser developer;
    private String id;
    private String markerTag;
    private AssessmentsApi assessmentApi;
    
    @Before
    public void before() throws Exception {
        id = randomIdentifier(AssessmentTest.class);
        markerTag = "test:" + randomIdentifier(AssessmentTest.class);

        admin = TestUserHelper.getSignedInAdmin();
        OrganizationsApi orgsApi = admin.getClient(OrganizationsApi.class);
        
        developer = new TestUserHelper.Builder(AssessmentTest.class).withRoles(DEVELOPER).createAndSignInUser();
        orgsApi.addMember(ORG_ID_1, developer.getUserId()).execute();
        assessmentApi = developer.getClient(AssessmentsApi.class);
    }
    
    @After
    public void after() throws IOException {
        if (developer != null) {
            developer.signOutAndDeleteUser();            
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
        tagsApi.deleteTag(markerTag).execute();
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
                .ownerId(ORG_ID_1)
                .tags(ImmutableList.of(markerTag));
        SharedAssessmentsApi sharedApi = developer.getClient(SharedAssessmentsApi.class);
        
        Assessment assessment = assessmentApi.createAssessment(unsavedAssessment).execute().body();
        assessmentApi.publishAssessment(assessment.getGuid(), id+"A").execute().body();
        Assessment shared = sharedApi.getLatestSharedAssessmentRevision(id+"A").execute().body();
        sharedApi.importSharedAssessment(shared.getGuid(), ORG_ID_1, id + "B").execute().body();
        
        // There should be a published assessment under (id + "A"), and two local assessments under id and (id + "B")
        AssessmentList list = sharedApi.getSharedAssessments(null, null, ImmutableList.of(markerTag), false).execute().body();
        assertEquals(ImmutableSet.of(id+"A"), getIdentifiers(list));
        assertEquals(Integer.valueOf(1), list.getTotal());
        
        list = assessmentApi.getAssessments(null, null, ImmutableList.of(markerTag), false).execute().body();
        assertEquals(ImmutableSet.of(id, id+"B"), getIdentifiers(list));
        assertEquals(Integer.valueOf(2), list.getTotal());
    }
    
    private Set<String> getIdentifiers(AssessmentList list) {
        return list.getItems().stream().map(Assessment::getIdentifier).collect(toSet());
    }
}
