package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import org.apache.commons.lang3.RandomStringUtils;
import org.joda.time.DateTime;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.api.ForWorkersApi;
import org.sagebionetworks.bridge.rest.api.SharedModulesApi;
import org.sagebionetworks.bridge.rest.api.UploadSchemasApi;
import org.sagebionetworks.bridge.rest.exceptions.BadRequestException;
import org.sagebionetworks.bridge.rest.exceptions.ConcurrentModificationException;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.exceptions.UnauthorizedException;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.SharedModuleMetadata;
import org.sagebionetworks.bridge.rest.model.UploadFieldDefinition;
import org.sagebionetworks.bridge.rest.model.UploadFieldType;
import org.sagebionetworks.bridge.rest.model.UploadSchema;
import org.sagebionetworks.bridge.rest.model.UploadSchemaList;
import org.sagebionetworks.bridge.rest.model.UploadSchemaType;

public class UploadSchemaTest {
    // We put spaces in the schema ID to test URL encoding.
    private static final String TEST_SCHEMA_ID_PREFIX = "integration test schema ";

    private static TestUserHelper.TestUser developer;
    private static TestUserHelper.TestUser user;
    private static TestUserHelper.TestUser worker;
    private static ForAdminsApi adminApi;
    private static UploadSchemasApi devUploadSchemasApi;
    private static ForWorkersApi workerUploadSchemasApi;
    private static SharedModulesApi sharedDeveloperModulesApi;
    private static UploadSchemasApi sharedUploadSchemasApi;
    private static UploadSchemasApi adminUploadSchemasApi;
    private static String studyIdShared;

    private String schemaId;

    @BeforeClass
    public static void beforeClass() throws Exception {
        TestUserHelper.TestUser admin = TestUserHelper.getSignedInAdmin();
        developer = TestUserHelper.createAndSignInUser(UploadSchemaTest.class, false, Role.DEVELOPER);
        user = TestUserHelper.createAndSignInUser(UploadSchemaTest.class, true);
        worker = TestUserHelper.createAndSignInUser(UploadSchemaTest.class, false, Role.WORKER);
        TestUserHelper.TestUser sharedDeveloper = TestUserHelper.getSignedInSharedDeveloper();
        sharedDeveloperModulesApi = sharedDeveloper.getClient(SharedModulesApi.class);

        adminApi = admin.getClient(ForAdminsApi.class);
        devUploadSchemasApi = developer.getClient(UploadSchemasApi.class);
        sharedUploadSchemasApi = sharedDeveloper.getClient(UploadSchemasApi.class);
        adminUploadSchemasApi = admin.getClient(UploadSchemasApi.class);
        workerUploadSchemasApi = worker.getClient(ForWorkersApi.class);

        studyIdShared = sharedDeveloper.getStudyId();
    }

    @Before
    public void before() {
        schemaId = TEST_SCHEMA_ID_PREFIX + RandomStringUtils.randomAlphabetic(4);
    }

    @After
    public void deleteSchemas() throws Exception {
        try {
            adminApi.deleteAllRevisionsOfUploadSchema(Tests.TEST_KEY, schemaId).execute();
        } catch (EntityNotFoundException ex) {
            // Suppress the exception, as the test may have already deleted the schema.
        }
    }

    @AfterClass
    public static void deleteResearcher() throws Exception {
        if (developer != null) {
            developer.signOutAndDeleteUser();
        }
    }

    @AfterClass
    public static void deleteUser() throws Exception {
        if (user != null) {
            user.signOutAndDeleteUser();
        }
    }

    @AfterClass
    public static void deleteWorker() throws Exception {
        if (worker != null) {
            worker.signOutAndDeleteUser();
        }
    }

    @Test
    public void testDeleteWithSharedModule() throws Exception {
        // create test upload schema and test shared module
        String moduleId = "integ-test-module-delete" + RandomStringUtils.randomAlphabetic(4);
        String schemaId = "integ-test-schema-delete" + RandomStringUtils.randomAlphabetic(4);

        UploadSchema uploadSchema = makeSimpleSchema(schemaId, (long) 0, 0L);
        UploadSchema retSchema = sharedUploadSchemasApi.createUploadSchema(uploadSchema).execute().body();

        SharedModuleMetadata metadataToCreate = new SharedModuleMetadata().id(moduleId).version(0)
                .name("Integ Test Schema").schemaId(retSchema.getSchemaId()).schemaRevision(retSchema.getRevision().intValue());
        sharedDeveloperModulesApi.createMetadata(metadataToCreate).execute()
                .body();

        // execute delete
        Exception thrownEx = null;
        try {
            adminApi.deleteAllRevisionsOfUploadSchema(studyIdShared, retSchema.getSchemaId()).execute();
            fail("expected exception");
        } catch (BadRequestException e) {
            thrownEx = e;
        } finally {
            // finally delete shared module and uploaded schema
            sharedDeveloperModulesApi.deleteMetadataByIdAllVersions(moduleId).execute();

            adminApi.deleteAllRevisionsOfUploadSchema(studyIdShared, retSchema.getSchemaId()).execute();
        }
        assertNotNull(thrownEx);
    }
    
    @Test
    public void test() throws Exception {
        // set up some field defs
        UploadFieldDefinition fooFieldDef = field("foo", true, UploadFieldType.STRING);
        UploadFieldDefinition barFieldDef = field("bar", false, UploadFieldType.INT);
        UploadFieldDefinition bazFieldDef = field("baz", true, UploadFieldType.BOOLEAN);

        // Step 1: Create initial version of schema.
        UploadSchema schemaV1 = schema(null, "Upload Schema Integration Tests", schemaId, UploadSchemaType.IOS_DATA,
                fooFieldDef);
        UploadSchema createdSchemaV1 = createOrUpdateSchemaAndVerify(schemaV1);

        // Step 2: Update to v2
        UploadSchema schemaV2 = schema(createdSchemaV1, "Schema Test II: The Sequel", schemaId,
                UploadSchemaType.IOS_DATA, fooFieldDef, barFieldDef);
        UploadSchema updatedSchemaV2 = createOrUpdateSchemaAndVerify(schemaV2);

        // Step 3: Another update. Having multiple versions helps test the delete API.
        UploadSchema schemaV3 = schema(updatedSchemaV2, "Schema Test v3", updatedSchemaV2.getSchemaId(), updatedSchemaV2.getSchemaType(),
                fooFieldDef, barFieldDef, bazFieldDef);
        createOrUpdateSchemaAndVerify(schemaV3);

        // Step 3b: Worker client can also get schemas, and can get schemas by study, schema, and rev.
        // This schema should be identical to updatedSchemaV2, except it also has the study ID.
        UploadSchema workerSchemaV2 = workerUploadSchemasApi.getSchemaRevisionInStudy(Tests.TEST_KEY, schemaId, 2L).execute().body();
        assertEquals(Tests.TEST_KEY, workerSchemaV2.getStudyId());
        assertSchemaFilledIn(workerSchemaV2);

        UploadSchema workerSchemaV2MinusStudyId = copy(null, workerSchemaV2);
        workerSchemaV2MinusStudyId.setStudyId(null);
        
        assertEquals(updatedSchemaV2, workerSchemaV2MinusStudyId);

        // Step 4a: Use list API to verify all 3 versions are still present
        Set<Long> foundRevSet = new HashSet<>();
        UploadSchemaList schemaList = devUploadSchemasApi.getAllRevisionsOfUploadSchema(schemaId).execute().body();
        //noinspection Convert2streamapi
        for (UploadSchema oneSchema : schemaList.getItems()) {
            assertSchemaFilledIn(oneSchema);
            if (oneSchema.getSchemaId().equals(schemaId)) {
                foundRevSet.add(oneSchema.getRevision());
            }
        }
        assertEquals(3, foundRevSet.size());
        assertTrue(foundRevSet.contains(1L));
        assertTrue(foundRevSet.contains(2L));
        assertTrue(foundRevSet.contains(3L));

        // Step 5: Delete all schemas with the test schema ID
        adminApi.deleteAllRevisionsOfUploadSchema(Tests.TEST_KEY, schemaId).execute();

        // Step 5a: Get API should throw
        Exception thrownEx = null;
        try {
            devUploadSchemasApi.getMostRecentUploadSchema(schemaId).execute();
            fail("expected exception");
        } catch (EntityNotFoundException ex) {
            thrownEx = ex;
        }
        assertNotNull(thrownEx);

        // Step 5b: Use list API to verify no schemas with this ID
        UploadSchemaList schemaList2 = devUploadSchemasApi.getMostRecentUploadSchemas().execute().body();
        //noinspection Convert2streamapi
        for (UploadSchema oneSchema : schemaList2.getItems()) {
            assertSchemaFilledIn(oneSchema);
            if (oneSchema.getSchemaId().equals(schemaId)) {
                fail("Found schema with ID " + schemaId + " even though it should have been deleted");
            }
        }
    }

    private static UploadSchema createOrUpdateSchemaAndVerify(UploadSchema schema) throws Exception {
        String schemaId = schema.getSchemaId();
        UploadSchema returnedSchema = devUploadSchemasApi.createOrUpdateUploadSchema(schema).execute().body();

        // all fields should match, except revision which is incremented
        assertEquals(schema.getFieldDefinitions(), returnedSchema.getFieldDefinitions());
        assertEquals(schema.getName(), returnedSchema.getName());
        assertEquals(schemaId, returnedSchema.getSchemaId());
        assertEquals(schema.getSchemaType(), returnedSchema.getSchemaType());

        // developer APIs never return study IDs
        assertNull(schema.getStudyId());

        long newSchemaRev;
        if (schema.getRevision() == null) {
            newSchemaRev = 1;
        } else {
            newSchemaRev = schema.getRevision().intValue() + 1;
        }
        assertEquals(newSchemaRev, returnedSchema.getRevision().longValue());

        // get by id and rev should return the same schema
        UploadSchema gettedSchema = devUploadSchemasApi.getUploadSchema(schemaId, newSchemaRev).execute().body();
        assertEquals(returnedSchema, gettedSchema);

        // get latest by id should also return the same schema
        UploadSchema gettedLatestSchema = devUploadSchemasApi.getMostRecentUploadSchema(schemaId).execute().body();
        assertEquals(returnedSchema, gettedLatestSchema);

        return returnedSchema;
    }

    @Test
    public void optionalFields() throws Exception {
        // We test every field except DDB version, as DDB version is (from the perspective of the Bridge client), an
        // opaque token that should be passed back to the server on update.

        // Server stores surveyCreatedOnMillis as epoch milliseconds, so we can't directly compare date-times.
        final DateTime surveyCreatedOn = DateTime.parse("2016-04-29T16:00:00.002-0700");
        final long surveyCreatedOnMillis = surveyCreatedOn.getMillis();

        // Maps for min/maxAppVersions.
        Map<String, Integer> maxAppVersionMap = ImmutableMap.of("integ-test", 10);
        Map<String, Integer> minAppVersionMap = ImmutableMap.of("integ-test", 2);

        // Create schema with all the fields. Note that no single field can logically have all the fields (some of
        // which are enforced server-side).
        UploadFieldDefinition def1 = new UploadFieldDefinition();
        def1.setName("multi-choice-test-field");
        def1.setType(UploadFieldType.MULTI_CHOICE);
        def1.setRequired(false);
        def1.setAllowOtherChoices(true);
        def1.setMultiChoiceAnswerList(Lists.newArrayList("foo", "bar", "baz"));
        
        UploadFieldDefinition def2 = new UploadFieldDefinition();
        def2.setName("attachment-test-field");
        def2.setType(UploadFieldType.ATTACHMENT_V2);
        def2.setFileExtension(".test");
        def2.setMimeType("text/plain");
        
        UploadFieldDefinition def3 = new UploadFieldDefinition();
        def3.setName("short-string-test-field");
        def3.setType(UploadFieldType.STRING);
        def3.setMaxLength(24);
        
        UploadFieldDefinition def4 = new UploadFieldDefinition();
        def4.setName("unbounded-string-test-field");
        def4.setType(UploadFieldType.STRING);
        def4.setUnboundedText(true);
        
        List<UploadFieldDefinition> fieldDefList = ImmutableList.of(def1, def2, def3, def4);
        UploadSchema schema = schema("Schema", schemaId, UploadSchemaType.IOS_SURVEY, fieldDefList);
        schema.setMaxAppVersions(maxAppVersionMap);
        schema.setMinAppVersions(minAppVersionMap);
        schema.setSurveyGuid("survey");
        schema.setSurveyCreatedOn(surveyCreatedOn);

        UploadSchema createdSchema = devUploadSchemasApi.createOrUpdateUploadSchema(schema).execute().body();

        assertEquals(fieldDefList, createdSchema.getFieldDefinitions());
        assertEquals(maxAppVersionMap, createdSchema.getMaxAppVersions());
        assertEquals(minAppVersionMap, createdSchema.getMinAppVersions());
        assertEquals("Schema", createdSchema.getName());
        assertEquals(1, createdSchema.getRevision().intValue());
        assertEquals(schemaId, createdSchema.getSchemaId());
        assertEquals(UploadSchemaType.IOS_SURVEY, createdSchema.getSchemaType());
        assertNull(createdSchema.getStudyId());
        assertEquals("survey", createdSchema.getSurveyGuid());
        assertEquals(surveyCreatedOnMillis, createdSchema.getSurveyCreatedOn().getMillis());
    }

    @Test
    public void newSchemaVersionConflict() throws Exception {
        testVersionConflict(null, null);
    }

    @Test
    public void createSchemaVersionConflict() throws Exception {
        // Create the schema first.
        devUploadSchemasApi.createOrUpdateUploadSchema(makeSimpleSchema(schemaId, null, null)).execute();

        // Now that the schema is created, run the update test.
        testVersionConflict(1L, null);
    }

    @Test
    public void createSchemaVersionConflictWithDdbVersion() throws Exception {
        // Create the schema first.
        devUploadSchemasApi.createOrUpdateUploadSchema(makeSimpleSchema(schemaId, null, null)).execute();

        // This time, we add the DDB version parameter (since this now exists in DDB) to make sure we don't croak when
        // this is present.
        testVersionConflict(1L, 1L);
    }

    private void testVersionConflict(Long rev, Long version) throws Exception {
        UploadSchema schema = makeSimpleSchema(schemaId, rev, version);

        // Create/update the schema and verified it was created/updated.
        UploadSchema createdSchema = devUploadSchemasApi.createOrUpdateUploadSchema(schema).execute().body();
        assertNotNull(createdSchema);

        // Create/update again. This one should throw.
        try {
            devUploadSchemasApi.createOrUpdateUploadSchema(schema).execute();
            fail("expected exception");
        } catch (ConcurrentModificationException ex) {
            // expected exception
        }
    }

    // Helper to make an upload schema with the minimum of attributes. Takes in rev and version to facilitate testing
    // create vs update and handling version conflicts.
    static UploadSchema makeSimpleSchema(String schemaId, Long rev, Long version) {
        UploadFieldDefinition fieldDef = new UploadFieldDefinition();
        fieldDef.setName("field");
        fieldDef.setType(UploadFieldType.STRING);
        
        UploadSchema schema = new UploadSchema();
        schema.setName("Schema");
        schema.setSchemaId(schemaId);
        schema.setRevision(rev);
        schema.setSchemaType(UploadSchemaType.IOS_DATA);
        schema.setVersion(version);
        schema.setFieldDefinitions(Lists.newArrayList(fieldDef));
        return schema;
    }

    @Test
    public void testV4() throws Exception {
        UploadFieldDefinition def1 = new UploadFieldDefinition();
        def1.setName("q1");
        def1.setType(UploadFieldType.MULTI_CHOICE);
        def1.setMultiChoiceAnswerList(Lists.newArrayList("foo", "bar"));
        
        UploadFieldDefinition def2 = new UploadFieldDefinition();
        def2.setName("q2");
        def2.setType(UploadFieldType.ATTACHMENT_V2);
        def2.setFileExtension(".txt");
        def2.setMimeType("text/plain");
        
        // create schema - Start with rev2 to test new v4 semantics.
        List<UploadFieldDefinition> fieldDefListV1 = ImmutableList.of(def1, def2);

        UploadSchema schemaV1 = new UploadSchema();
        schemaV1.setName("Schema");
        schemaV1.setRevision(2L);
        schemaV1.setSchemaId(schemaId);
        schemaV1.setSchemaType(UploadSchemaType.IOS_DATA);
        schemaV1.setFieldDefinitions(fieldDefListV1);
        
        UploadSchema createdSchema = devUploadSchemasApi.createUploadSchema(schemaV1).execute().body();
        assertEquals("Schema", createdSchema.getName());
        assertEquals(2, createdSchema.getRevision().intValue());
        assertEquals(schemaId, createdSchema.getSchemaId());
        assertEquals(UploadSchemaType.IOS_DATA, createdSchema.getSchemaType());
        assertNull(createdSchema.getStudyId());
        assertEquals(fieldDefListV1, createdSchema.getFieldDefinitions());

        // Get the schema back. Should match created schema.
        UploadSchema fetchedSchemaV1 = devUploadSchemasApi.getMostRecentUploadSchema(schemaId).execute().body();
        assertEquals(createdSchema, fetchedSchemaV1);

        // create it again, version conflict
        try {
            devUploadSchemasApi.createUploadSchema(schemaV1).execute();
            fail("expected exception");
        } catch (ConcurrentModificationException ex) {
            // expected exception
        }

        // update schema - you can add optional fields, reorder fields, add or reorder choices, and make non-breaking
        // changes to existing fields
        UploadFieldDefinition udef1 = new UploadFieldDefinition();
        udef1.setName("q2");
        udef1.setType(UploadFieldType.ATTACHMENT_V2);
        udef1.setFileExtension(".json");
        udef1.setMimeType("text/json");
        
        UploadFieldDefinition udef2 = new UploadFieldDefinition();
        udef2.setName("q-added");
        udef2.setType(UploadFieldType.BOOLEAN);
        udef2.setRequired(false);
        
        UploadFieldDefinition udef3 = new UploadFieldDefinition();
        udef3.setName("q1");
        udef3.setType(UploadFieldType.MULTI_CHOICE);
        udef3.setMultiChoiceAnswerList(Lists.newArrayList("bar", "baz", "foo"));
        udef3.setAllowOtherChoices(true);
        
        List<UploadFieldDefinition> fieldDefListV2 = ImmutableList.of(udef1, udef2, udef3);

        UploadSchema schemaV2 = new UploadSchema();
        schemaV2.setName("Updated Schema");
        schemaV2.setRevision(fetchedSchemaV1.getRevision());
        schemaV2.setSchemaId(fetchedSchemaV1.getSchemaId());
        schemaV2.setSchemaType(fetchedSchemaV1.getSchemaType());
        schemaV2.setStudyId(fetchedSchemaV1.getStudyId());
        schemaV2.setSurveyGuid(fetchedSchemaV1.getSurveyGuid());
        schemaV2.setSurveyCreatedOn(fetchedSchemaV1.getSurveyCreatedOn());
        schemaV2.setVersion(fetchedSchemaV1.getVersion());
        schemaV2.setFieldDefinitions(fieldDefListV2);
        
        UploadSchema updatedSchema = devUploadSchemasApi.updateUploadSchema(schemaId, 2L, schemaV2).execute().body();
        assertEquals("Updated Schema", updatedSchema.getName());
        assertEquals(2, updatedSchema.getRevision().intValue());
        assertEquals(schemaId, updatedSchema.getSchemaId());
        assertEquals(UploadSchemaType.IOS_DATA, updatedSchema.getSchemaType());
        assertNull(updatedSchema.getStudyId());
        assertEquals(fieldDefListV2, updatedSchema.getFieldDefinitions());

        // Get the schema back again. Should match updated schema.
        UploadSchema fetchedSchemaV2 = devUploadSchemasApi.getMostRecentUploadSchema(schemaId).execute().body();
        assertEquals(updatedSchema, fetchedSchemaV2);

        // update it again, version conflict
        try {
            devUploadSchemasApi.updateUploadSchema(schemaId, 2L, schemaV2).execute();
            fail("expected exception");
        } catch (ConcurrentModificationException ex) {
            // expected exception
        }
    }

    @Test(expected=UnauthorizedException.class)
    public void unauthorizedTest() throws Exception {
        user.getClient(UploadSchemasApi.class).getMostRecentUploadSchemas().execute();
    }

    private UploadFieldDefinition field(String name, boolean required, UploadFieldType type) {
        UploadFieldDefinition field = new UploadFieldDefinition();
        field.setName(name);
        field.setRequired(required);
        field.setType(type);
        return field;
    }
    
    private UploadSchema schema(UploadSchema source, String name, String id, UploadSchemaType type,
            UploadFieldDefinition... definitions) throws Exception {
        List<UploadFieldDefinition> defs = new ArrayList<>();
        Collections.addAll(defs, definitions);
        UploadSchema schema = new UploadSchema();
        if (source != null) {
            copy(schema, source);
        }
        schema.setSchemaId(id);
        schema.setName(name);
        schema.setSchemaType(type);
        schema.setFieldDefinitions(defs);
        return schema;
    }
    
    private UploadSchema copy(UploadSchema destination, UploadSchema source) throws Exception {
        if (destination == null) {
            destination = new UploadSchema();
        }
        destination.setFieldDefinitions(source.getFieldDefinitions());
        destination.setName(source.getName());
        destination.setRevision(source.getRevision());
        destination.setSchemaId(source.getSchemaId());
        destination.setSchemaType(source.getSchemaType());
        destination.setStudyId(source.getStudyId());
        destination.setSurveyCreatedOn(source.getSurveyCreatedOn());
        destination.setSurveyGuid(source.getSurveyGuid());
        destination.setVersion(source.getVersion());
        Tests.setVariableValueInObject(destination, "type", source.getType());
        return destination;
    }
    
    private UploadSchema schema(String name, String id, UploadSchemaType type, List<UploadFieldDefinition> definitions) {
        UploadSchema schema = new UploadSchema();
        schema.setSchemaId(id);
        schema.setName(name);
        schema.setSchemaType(type);
        schema.setFieldDefinitions(definitions);
        return schema;
    }

    // Verifies the schema is filled in. This ensures that we're correctly getting all schema fields, and not just
    // index keys.
    private static void assertSchemaFilledIn(UploadSchema schema) {
        assertNotNull(schema.getName());
        assertFalse(schema.getFieldDefinitions().isEmpty());
    }
}
