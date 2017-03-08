package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.Map;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.api.CompoundActivityDefinitionsApi;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.model.CompoundActivityDefinition;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.SchemaReference;
import org.sagebionetworks.bridge.rest.model.SurveyReference;

public class CompoundActivityDefinitionTest {
    private static final String SCHEMA_ID = "test-schema";
    private static final List<SchemaReference> SCHEMA_LIST = ImmutableList.of(new SchemaReference().id(SCHEMA_ID));

    private static final String SURVEY_ID = "test-survey";
    private static final String SURVEY_GUID = "test-guid";
    private static final List<SurveyReference> SURVEY_LIST = ImmutableList.of(new SurveyReference()
            .identifier(SURVEY_ID).guid(SURVEY_GUID));

    private static final String TASK_ID_PREFIX = "test-task-";

    private static CompoundActivityDefinitionsApi compoundActivityDefinitionsApi;
    private static TestUserHelper.TestUser developer;

    private String taskId;

    @BeforeClass
    public static void beforeClass() throws Exception {
        developer = TestUserHelper.createAndSignInUser(UploadSchemaTest.class, false, Role.DEVELOPER);
        compoundActivityDefinitionsApi = developer.getClient(CompoundActivityDefinitionsApi.class);
    }

    @Before
    public void before() {
        taskId = TASK_ID_PREFIX + RandomStringUtils.randomAlphabetic(4);
    }

    @AfterClass
    public static void deleteDeveloper() throws Exception {
        if (developer != null) {
            developer.signOutAndDeleteUser();
        }
    }

    @Test
    public void crud() throws Exception {
        // create and validate
        CompoundActivityDefinition defToCreate = new CompoundActivityDefinition().taskId(taskId)
                .schemaList(SCHEMA_LIST).surveyList(SURVEY_LIST);

        CompoundActivityDefinition createdDef = compoundActivityDefinitionsApi.createCompoundActivityDefinition(
                defToCreate).execute().body();
        assertSchemaList(createdDef.getSchemaList());
        assertSurveyList(createdDef.getSurveyList());
        assertEquals(taskId, createdDef.getTaskId());

        // get the created def and validate
        CompoundActivityDefinition gettedCreatedDef = compoundActivityDefinitionsApi.getCompoundActivityDefinition(
                taskId).execute().body();
        assertSchemaList(gettedCreatedDef.getSchemaList());
        assertSurveyList(gettedCreatedDef.getSurveyList());
        assertEquals(taskId, gettedCreatedDef.getTaskId());

        // Update the def to have no surveys.
        CompoundActivityDefinition defToUpdate = gettedCreatedDef.surveyList(ImmutableList.of());

        CompoundActivityDefinition updatedDef = compoundActivityDefinitionsApi.updateCompoundActivityDefinition(taskId,
                defToUpdate).execute().body();
        assertSchemaList(updatedDef.getSchemaList());
        assertTrue(updatedDef.getSurveyList().isEmpty());
        assertEquals(taskId, updatedDef.getTaskId());

        // get the updated def and validate
        CompoundActivityDefinition gettedUpdatedDef = compoundActivityDefinitionsApi.getCompoundActivityDefinition(
                taskId).execute().body();
        assertSchemaList(gettedUpdatedDef.getSchemaList());
        assertTrue(gettedUpdatedDef.getSurveyList().isEmpty());
        assertEquals(taskId, gettedUpdatedDef.getTaskId());

        // delete
        compoundActivityDefinitionsApi.deleteCompoundActivityDefinition(taskId).execute();

        // If you get the task, it throws.
        try {
            compoundActivityDefinitionsApi.getCompoundActivityDefinition(taskId).execute();
            fail("expected exception");
        } catch (EntityNotFoundException ex) {
            // expected exception
        }
    }

    @Test
    public void list() throws Exception {
        String taskId1 = null;
        String taskId2 = null;

        try {
            // Create two defs to test list.
            taskId1 = taskId + "1";
            CompoundActivityDefinition def1 = new CompoundActivityDefinition().taskId(taskId1).schemaList(SCHEMA_LIST)
                    .surveyList(SURVEY_LIST);
            compoundActivityDefinitionsApi.createCompoundActivityDefinition(def1).execute();

            taskId2 = taskId + "2";
            CompoundActivityDefinition def2 = new CompoundActivityDefinition().taskId(taskId2).schemaList(SCHEMA_LIST)
                    .surveyList(SURVEY_LIST);
            compoundActivityDefinitionsApi.createCompoundActivityDefinition(def2).execute();

            // Test list. Since there might be other defs from other tests, page through the defs to find the ones
            // corresponding to the test.
            List<CompoundActivityDefinition> defList = compoundActivityDefinitionsApi
                    .getAllCompoundActivityDefinitionsInStudy().execute().body().getItems();
            Map<String, CompoundActivityDefinition> defsByTaskId = Maps.uniqueIndex(defList,
                    CompoundActivityDefinition::getTaskId);

            CompoundActivityDefinition listedTask1 = defsByTaskId.get(taskId1);
            assertSchemaList(listedTask1.getSchemaList());
            assertSurveyList(listedTask1.getSurveyList());
            assertEquals(taskId1, listedTask1.getTaskId());

            CompoundActivityDefinition listedTask2 = defsByTaskId.get(taskId2);
            assertSchemaList(listedTask2.getSchemaList());
            assertSurveyList(listedTask2.getSurveyList());
            assertEquals(taskId2, listedTask2.getTaskId());
        } finally {
            // clean up defs
            try {
                compoundActivityDefinitionsApi.deleteCompoundActivityDefinition(taskId1).execute();
            } catch (RuntimeException ex) {
                // squelch error
            }

            try {
                compoundActivityDefinitionsApi.deleteCompoundActivityDefinition(taskId2).execute();
            } catch (RuntimeException ex) {
                // squelch error
            }
        }
    }

    // We can't set the "type" field for schema objects we create programmatically. However, the rest-client can. This
    // means we can't use .equals(). This is a helper method to make validation less insane.
    private static void assertSchemaList(List<SchemaReference> schemaList) {
        assertEquals(1, schemaList.size());
        assertEquals(SCHEMA_ID, schemaList.get(0).getId());
    }

    // Similarly, for survey lists.
    private static void assertSurveyList(List<SurveyReference> surveyList) {
        assertEquals(1, surveyList.size());
        assertEquals(SURVEY_ID, surveyList.get(0).getIdentifier());
        assertEquals(SURVEY_GUID, surveyList.get(0).getGuid());
    }
}
