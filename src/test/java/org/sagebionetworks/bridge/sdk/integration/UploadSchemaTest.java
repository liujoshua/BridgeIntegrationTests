package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;

import com.google.common.collect.ImmutableList;
import org.apache.commons.lang3.RandomStringUtils;
import org.joda.time.DateTime;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.sagebionetworks.bridge.sdk.Roles;
import org.sagebionetworks.bridge.sdk.UploadSchemaClient;
import org.sagebionetworks.bridge.sdk.exceptions.ConcurrentModificationException;
import org.sagebionetworks.bridge.sdk.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.sdk.exceptions.UnauthorizedException;
import org.sagebionetworks.bridge.sdk.models.ResourceList;
import org.sagebionetworks.bridge.sdk.models.upload.UploadFieldDefinition;
import org.sagebionetworks.bridge.sdk.models.upload.UploadFieldType;
import org.sagebionetworks.bridge.sdk.models.upload.UploadSchema;
import org.sagebionetworks.bridge.sdk.models.upload.UploadSchemaType;

public class UploadSchemaTest {
    // We put spaces in the schema ID to test URL encoding.
    private static final String TEST_SCHEMA_ID_PREFIX = "integration test schema ";

    private static TestUserHelper.TestUser developer;
    private static TestUserHelper.TestUser user;
    private static TestUserHelper.TestUser worker;
    private static UploadSchemaClient devUploadSchemaClient;
    private static UploadSchemaClient workerUploadSchemaClient;

    private String schemaId;

    @BeforeClass
    public static void beforeClass() {
        developer = TestUserHelper.createAndSignInUser(UploadSchemaTest.class, false, Roles.DEVELOPER);
        user = TestUserHelper.createAndSignInUser(UploadSchemaTest.class, true);
        worker = TestUserHelper.createAndSignInUser(UploadSchemaTest.class, false, Roles.WORKER);
        devUploadSchemaClient = developer.getSession().getUploadSchemaClient();
        workerUploadSchemaClient = worker.getSession().getUploadSchemaClient();
    }

    @Before
    public void before() {
        schemaId = TEST_SCHEMA_ID_PREFIX + RandomStringUtils.randomAlphabetic(4);
    }

    @After
    public void deleteSchemas() {
        try {
            devUploadSchemaClient.deleteUploadSchemaAllRevisions(schemaId);
        } catch (EntityNotFoundException ex) {
            // Suppress the exception, as the test may have already deleted the schema.
        }
    }

    @AfterClass
    public static void deleteResearcher() {
        if (developer != null) {
            developer.signOutAndDeleteUser();
        }
    }

    @AfterClass
    public static void deleteUser() {
        if (user != null) {
            user.signOutAndDeleteUser();
        }
    }

    @AfterClass
    public static void deleteWorker() {
        if (worker != null) {
            worker.signOutAndDeleteUser();
        }
    }

    @Test
    public void test() {
        // set up some field defs
        UploadFieldDefinition fooFieldDef = new UploadFieldDefinition.Builder().withName("foo").withRequired(true)
                .withType(UploadFieldType.STRING).build();
        UploadFieldDefinition barFieldDef = new UploadFieldDefinition.Builder().withName("bar").withRequired(false)
                .withType(UploadFieldType.INT).build();
        UploadFieldDefinition bazFieldDef = new UploadFieldDefinition.Builder().withName("baz").withRequired(true)
                .withType(UploadFieldType.BOOLEAN).build();

        // Step 1: Create initial version of schema.
        UploadSchema schemaV1 = new UploadSchema.Builder().withFieldDefinitions(fooFieldDef)
                .withName("Upload Schema Integration Tests").withSchemaId(schemaId)
                .withSchemaType(UploadSchemaType.IOS_DATA).build();
        UploadSchema createdSchemaV1 = createOrUpdateSchemaAndVerify(schemaV1);

        // Step 2: Update to v2
        UploadSchema schemaV2 = new UploadSchema.Builder().copyOf(createdSchemaV1)
                .withFieldDefinitions(fooFieldDef, barFieldDef).withName("Schema Test II: The Sequel").build();
        UploadSchema updatedSchemaV2 = createOrUpdateSchemaAndVerify(schemaV2);

        // Step 3: Another update. Having multiple versions helps test the delete API.
        UploadSchema schemaV3 = new UploadSchema.Builder().copyOf(updatedSchemaV2)
                .withFieldDefinitions(fooFieldDef, barFieldDef, bazFieldDef).withName("Schema Test v3").build();
        createOrUpdateSchemaAndVerify(schemaV3);

        // Step 3b: Worker client can also get schemas, and can get schemas by study, schema, and rev.
        // This schema should be identical to updatedSchemaV2, except it also has the study ID.
        UploadSchema workerSchemaV2 = workerUploadSchemaClient.getSchema(Tests.TEST_KEY, schemaId, 2);
        assertEquals(Tests.TEST_KEY, workerSchemaV2.getStudyId());

        UploadSchema workerSchemaV2MinusStudyId = new UploadSchema.Builder().copyOf(workerSchemaV2).withStudyId(null)
                .build();
        assertEquals(updatedSchemaV2, workerSchemaV2MinusStudyId);

        // Step 4: Delete v3 and verify the getter returns v2.
        devUploadSchemaClient.deleteUploadSchema(schemaId, 3);
        UploadSchema returnedAfterDelete = devUploadSchemaClient.getMostRecentUploadSchemaRevision(schemaId);
        assertEquals(updatedSchemaV2, returnedAfterDelete);

        // Step 4a: Use list API to verify v1 and v2 are both still present
        boolean v1Found = false;
        boolean v2Found = false;
        ResourceList<UploadSchema> schemaList = devUploadSchemaClient.getUploadSchema(schemaId);
        for (UploadSchema oneSchema : schemaList) {
            if (oneSchema.getSchemaId().equals(schemaId)) {
                int rev = oneSchema.getRevision();
                if (rev == 1) {
                    assertEquals(createdSchemaV1, oneSchema);
                    v1Found = true;
                } else if (rev == 2) {
                    assertEquals(updatedSchemaV2, oneSchema);
                    v2Found = true;
                } else {
                    fail("Unexpected schema revision: " + rev);
                }
            }
        }
        assertTrue(v1Found);
        assertTrue(v2Found);

        // Step 5: Delete all schemas with the test schema ID
        devUploadSchemaClient.deleteUploadSchemaAllRevisions(schemaId);

        // Step 5a: Get API should throw
        Exception thrownEx = null;
        try {
            devUploadSchemaClient.getUploadSchema(schemaId);
            fail("expected exception");
        } catch (EntityNotFoundException ex) {
            thrownEx = ex;
        }
        assertNotNull(thrownEx);

        // Step 5b: Use list API to verify no schemas with this ID
        ResourceList<UploadSchema> schemaList2 = devUploadSchemaClient.getAllUploadSchemas();
        for (UploadSchema oneSchema : schemaList2) {
            if (oneSchema.getSchemaId().equals(schemaId)) {
                fail("Found schema with ID " + schemaId + " even though it should have been deleted");
            }
        }
    }

    private static UploadSchema createOrUpdateSchemaAndVerify(UploadSchema schema) {
        UploadSchema returnedSchema = devUploadSchemaClient.createOrUpdateUploadSchema(schema);

        // all fields should match, except revision which is incremented
        assertEquals(schema.getFieldDefinitions(), returnedSchema.getFieldDefinitions());
        assertEquals(schema.getName(), returnedSchema.getName());
        assertEquals(schema.getSchemaId(), returnedSchema.getSchemaId());
        assertEquals(schema.getSchemaType(), returnedSchema.getSchemaType());

        // developer APIs never return study IDs
        assertNull(schema.getStudyId());

        if (schema.getRevision() == null) {
            assertEquals(1, returnedSchema.getRevision().intValue());
        } else {
            assertEquals(schema.getRevision() + 1, returnedSchema.getRevision().intValue());
        }

        return returnedSchema;
    }

    @Test
    public void optionalFields() {
        // We test every field except DDB version, as DDB version is (from the perspective of the Bridge client), an
        // opaque token that should be passed back to the server on update.

        // Server stores surveyCreatedOnMillis as epoch milliseconds, so we can't directly compare date-times.
        final DateTime surveyCreatedOn = DateTime.parse("2016-04-29T16:00:00.002-0700");
        final long surveyCreatedOnMillis = surveyCreatedOn.getMillis();

        // Create schema with all the fields.
        UploadFieldDefinition fieldDef = new UploadFieldDefinition.Builder().withFileExtension(".test")
                .withMimeType("text/plain").withMinAppVersion(1).withMaxAppVersion(37).withMaxLength(24)
                .withMultiChoiceAnswerList("foo", "bar", "baz").withName("field").withRequired(false)
                .withType(UploadFieldType.STRING).build();
        List<UploadFieldDefinition> fieldListList = ImmutableList.of(fieldDef);
        UploadSchema schema = new UploadSchema.Builder().withFieldDefinitions(fieldListList).withName("Schema")
                .withSchemaId(schemaId).withSchemaType(UploadSchemaType.IOS_SURVEY).withSurveyGuid("survey")
                .withSurveyCreatedOn(surveyCreatedOn).build();
        UploadSchema createdSchema = devUploadSchemaClient.createOrUpdateUploadSchema(schema);

        assertEquals(fieldListList, createdSchema.getFieldDefinitions());
        assertEquals("Schema", createdSchema.getName());
        assertEquals(1, createdSchema.getRevision().intValue());
        assertEquals(schemaId, createdSchema.getSchemaId());
        assertEquals(UploadSchemaType.IOS_SURVEY, createdSchema.getSchemaType());
        assertNull(createdSchema.getStudyId());
        assertEquals("survey", createdSchema.getSurveyGuid());
        assertEquals(surveyCreatedOnMillis, createdSchema.getSurveyCreatedOn().getMillis());
    }

    @Test
    public void newSchemaVersionConflict() {
        testVersionConflict(null, null);
    }

    @Test
    public void createSchemaVersionConflict() {
        // Create the schema first.
        devUploadSchemaClient.createOrUpdateUploadSchema(makeSimpleSchema(schemaId, null, null));

        // Now that the schema is created, run the update test.
        testVersionConflict(1, null);
    }

    @Test
    public void createSchemaVersionConflictWithDdbVersion() {
        // Create the schema first.
        devUploadSchemaClient.createOrUpdateUploadSchema(makeSimpleSchema(schemaId, null, null));

        // This time, we add the DDB version parameter (since this now exists in DDB) to make sure we don't croak when
        // this is present.
        testVersionConflict(1, 1L);
    }

    private void testVersionConflict(Integer rev, Long version) {
        UploadSchema schema = makeSimpleSchema(schemaId, rev, version);

        // Create/update the schema and verified it was created/updated.
        UploadSchema createdSchema = devUploadSchemaClient.createOrUpdateUploadSchema(schema);
        assertNotNull(createdSchema);

        // Create/update again. This one should throw.
        try {
            devUploadSchemaClient.createOrUpdateUploadSchema(schema);
            fail("expected exception");
        } catch (ConcurrentModificationException ex) {
            // expected exception
        }
    }

    @Test
    public void testV4() {
        // Set up some fields
        UploadFieldDefinition fooField = new UploadFieldDefinition.Builder().withName("foo")
                .withType(UploadFieldType.STRING).build();
        UploadFieldDefinition fooMaxAppVersion = new UploadFieldDefinition.Builder().withName("foo")
                .withType(UploadFieldType.STRING).withMaxAppVersion(42).build();
        UploadFieldDefinition barField = new UploadFieldDefinition.Builder().withName("bar")
                .withType(UploadFieldType.STRING).build();
        UploadFieldDefinition barMaxAppVersion = new UploadFieldDefinition.Builder().withName("bar")
                .withType(UploadFieldType.STRING).withMaxAppVersion(42).build();
        UploadFieldDefinition bazField = new UploadFieldDefinition.Builder().withName("baz")
                .withType(UploadFieldType.STRING).build();

        // create schema - Start with rev2 to test new v4 semantics.
        List<UploadFieldDefinition> fieldDefListV1 = ImmutableList.of(fooField, barField);
        UploadSchema schemaV1 = new UploadSchema.Builder().withName("Schema").withRevision(2).withSchemaId(schemaId)
                .withSchemaType(UploadSchemaType.IOS_DATA).withFieldDefinitions(fieldDefListV1).build();
        UploadSchema createdSchema = devUploadSchemaClient.createSchemaRevisionV4(schemaV1);
        assertEquals("Schema", createdSchema.getName());
        assertEquals(2, createdSchema.getRevision().intValue());
        assertEquals(schemaId, createdSchema.getSchemaId());
        assertEquals(UploadSchemaType.IOS_DATA, createdSchema.getSchemaType());
        assertNull(createdSchema.getStudyId());
        assertEquals(fieldDefListV1, createdSchema.getFieldDefinitions());

        // Get the schema back. Should match created schema.
        UploadSchema fetchedSchemaV1 = devUploadSchemaClient.getMostRecentUploadSchemaRevision(schemaId);
        assertEquals(createdSchema, fetchedSchemaV1);

        // create it again, version conflict
        try {
            devUploadSchemaClient.createSchemaRevisionV4(schemaV1);
            fail("expected exception");
        } catch (ConcurrentModificationException ex) {
            // expected exception
        }

        // update schema - add some fields, re-order some fields, add maxAppVersion to some fields
        List<UploadFieldDefinition> fieldDefListV2 = ImmutableList.of(barMaxAppVersion, bazField, fooMaxAppVersion);
        UploadSchema schemaV2 = new UploadSchema.Builder().copyOf(fetchedSchemaV1).withName("Updated Schema")
                .withFieldDefinitions(fieldDefListV2).build();
        UploadSchema updatedSchema = devUploadSchemaClient.updateSchemaRevisionV4(schemaId, 2, schemaV2);
        assertEquals("Updated Schema", updatedSchema.getName());
        assertEquals(2, updatedSchema.getRevision().intValue());
        assertEquals(schemaId, updatedSchema.getSchemaId());
        assertEquals(UploadSchemaType.IOS_DATA, updatedSchema.getSchemaType());
        assertNull(updatedSchema.getStudyId());
        assertEquals(fieldDefListV2, updatedSchema.getFieldDefinitions());

        // Get the schema back again. Should match updated schema.
        UploadSchema fetchedSchemaV2 = devUploadSchemaClient.getMostRecentUploadSchemaRevision(schemaId);
        assertEquals(updatedSchema, fetchedSchemaV2);

        // update it again, version conflict
        try {
            devUploadSchemaClient.updateSchemaRevisionV4(schemaId, 2, schemaV2);
            fail("expected exception");
        } catch (ConcurrentModificationException ex) {
            // expected exception
        }
    }

    // Helper to make an upload schema with the minimum of attributes. Takes in rev and version to facilitate testing
    // create vs update and handling version conflicts.
    private static UploadSchema makeSimpleSchema(String schemaId, Integer rev, Long version) {
        UploadFieldDefinition fieldDef = new UploadFieldDefinition.Builder().withName("field")
                .withType(UploadFieldType.STRING).build();
        UploadSchema schema = new UploadSchema.Builder().withFieldDefinitions(fieldDef).withName("Schema")
                .withRevision(rev).withSchemaId(schemaId).withSchemaType(UploadSchemaType.IOS_DATA)
                .withVersion(version).build();
        return schema;
    }

    @Test(expected=UnauthorizedException.class)
    public void unauthorizedTest() {
        user.getSession().getUploadSchemaClient().getAllUploadSchemas();
    }
}
