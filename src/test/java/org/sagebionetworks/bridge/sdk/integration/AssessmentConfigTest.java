package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.sagebionetworks.bridge.rest.model.Role.DEVELOPER;
import static org.sagebionetworks.bridge.sdk.integration.Tests.ORG_ID_1;
import static org.sagebionetworks.bridge.sdk.integration.Tests.randomIdentifier;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.RestUtils;
import org.sagebionetworks.bridge.rest.api.AssessmentsApi;
import org.sagebionetworks.bridge.rest.api.OrganizationsApi;
import org.sagebionetworks.bridge.rest.api.SharedAssessmentsApi;
import org.sagebionetworks.bridge.rest.api.TagsApi;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.model.Assessment;
import org.sagebionetworks.bridge.rest.model.AssessmentConfig;
import org.sagebionetworks.bridge.rest.model.AssessmentList;
import org.sagebionetworks.bridge.rest.model.Organization;
import org.sagebionetworks.bridge.rest.model.PropertyInfo;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;

public class AssessmentConfigTest {
    private static final String ORIGINAL = "original";
    private static final String CHANGED = "changed";

    private static final Map<String, List<PropertyInfo>> CUSTOMIZATION_FIELDS = ImmutableMap.of(
            "node1", ImmutableList.of(new PropertyInfo().propName("field1").label("field label"),
                    new PropertyInfo().propName("field2").label("field2 label")),
            "node2", ImmutableList.of(new PropertyInfo().propName("field3").label("field3 label"),
                    new PropertyInfo().propName("field4").label("field4 label")));

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
        
        try {
            orgsApi.getOrganization(ORG_ID_1).execute();
        } catch (EntityNotFoundException ex) {
            Organization org = new Organization().identifier(ORG_ID_1).name(ORG_ID_1);
            orgsApi.createOrganization(org).execute();
        }
        
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
                .tags(ImmutableList.of(markerTag))
                .customizationFields(CUSTOMIZATION_FIELDS);
        Assessment assessment = assessmentApi.createAssessment(unsavedAssessment).execute().body();
        
        AssessmentConfig config = assessmentApi.getAssessmentConfig(assessment.getGuid()).execute().body();
        
        // Server expects an object... so how will this work?
        JsonArray jsonArray = new JsonParser().parse("[" + 
                "   {" + 
                "      \"identifier\":\"node1\"," + 
                "      \"field1\":\"original\"," + 
                "      \"field2\":\"original\"," + 
                "      \"field3\":\"original\"," +
                "      \"type\":\"Type\"" +
                "   }, {" + 
                "      \"identifier\":\"node2\"," + 
                "      \"field2\":\"original\"," + 
                "      \"field3\":\"original\"," + 
                "      \"field4\":\"original\"," + 
                "      \"type\":\"Type\"" +
                "   }, {" + 
                "      \"identifier\":\"node3\"," + 
                "      \"field3\":\"original\"," + 
                "      \"field4\":\"original\"," + 
                "      \"field5\":\"original\"," + 
                "      \"type\":\"Type\"" +
                "   }" + 
                "]").getAsJsonArray();
        config.setConfig(jsonArray);
        config = assessmentApi.updateAssessmentConfig(assessment.getGuid(), config).execute().body();
        
        assessmentApi.publishAssessment(assessment.getGuid(), null).execute().body();
        
        // This is starting to look like a problem in the API... you can't just get back the assessment
        // from the publication call, that's not the shared assessment, it's the modified original 
        // assessment.
        SharedAssessmentsApi sharedApi = developer.getClient(SharedAssessmentsApi.class);
        Assessment shared = sharedApi.getLatestSharedAssessmentRevision(id).execute().body();
        
        // API to get shared configuration works
        AssessmentConfig sharedConfig = sharedApi.getSharedAssessmentConfig(shared.getGuid()).execute().body();
        JsonElement el = RestUtils.toJSON(sharedConfig.getConfig());
        JsonElement item1 = el.getAsJsonArray().get(0);
        JsonObject obj1 = item1.getAsJsonObject();
        assertEquals(ORIGINAL, obj1.get("field1").getAsString());
        assertEquals(ORIGINAL, obj1.get("field2").getAsString());
        assertEquals(ORIGINAL, obj1.get("field3").getAsString());
        
        Assessment newAssessment = sharedApi.importSharedAssessment(shared.getGuid(), ORG_ID_1, null).execute().body();
        
        JsonElement textNode = RestUtils.toJSON(CHANGED);
        Map<String, JsonElement> propMap1 = ImmutableMap.of("field1", textNode, "field2", textNode, "field3", textNode);
        Map<String, JsonElement> propMap2 = ImmutableMap.of("field2", textNode, "field3", textNode, "field4", textNode);
        Map<String, JsonElement> propMap3 = ImmutableMap.of("field3", textNode, "field4", textNode, "field5", textNode);
        Map<String, Map<String, JsonElement>> nodeMap = ImmutableMap.of(
                "node1", propMap1, "node2", propMap2, "node3", propMap3);
        
        AssessmentConfig updatedConfig = assessmentApi.customizeAssessmentConfig(
                newAssessment.getGuid(), nodeMap).execute().body();
        
        el = RestUtils.toJSON(updatedConfig.getConfig());
        item1 = el.getAsJsonArray().get(0);
        obj1 = item1.getAsJsonObject();
        assertEquals(CHANGED, obj1.get("field1").getAsString());
        assertEquals(CHANGED, obj1.get("field2").getAsString());
        assertEquals(ORIGINAL, obj1.get("field3").getAsString());
        
        JsonElement item2 = el.getAsJsonArray().get(1);
        JsonObject obj2 = item2.getAsJsonObject();
        assertEquals(ORIGINAL, obj2.get("field2").getAsString());
        assertEquals(CHANGED, obj2.get("field3").getAsString());
        assertEquals(CHANGED, obj2.get("field4").getAsString());
        
        JsonElement item3 = el.getAsJsonArray().get(2);
        JsonObject obj3 = item3.getAsJsonObject();
        assertEquals(ORIGINAL, obj3.get("field3").getAsString());
        assertEquals(ORIGINAL, obj3.get("field4").getAsString());
        assertEquals(ORIGINAL, obj3.get("field5").getAsString());
    }
}
