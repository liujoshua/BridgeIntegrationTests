package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.Set;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.sagebionetworks.bridge.rest.api.SharedModulesApi;
import org.sagebionetworks.bridge.rest.exceptions.BadRequestException;
import org.sagebionetworks.bridge.rest.exceptions.BridgeSDKException;
import org.sagebionetworks.bridge.rest.exceptions.ConcurrentModificationException;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.exceptions.UnauthorizedException;
import org.sagebionetworks.bridge.rest.model.SharedModuleMetadata;
import org.sagebionetworks.bridge.rest.model.SharedModuleMetadataList;
import org.sagebionetworks.bridge.rest.model.SharedModuleType;

public class SharedModuleMetadataTest {
    private static final Logger LOG = LoggerFactory.getLogger(SharedModuleMetadataTest.class);

    private static final String MODULE_NAME = "Integ Test Module";
    private static final String NOTES = "These are some notes about a module.";
    private static final String OS = "Unix";
    private static final String SCHEMA_ID = "dummy-schema";
    private static final int SCHEMA_REV = 42;
    private static final String SURVEY_CREATED_ON_STRING = "2017-04-06T17:34:03.507Z";
    private static final String SURVEY_GUID = "dummy-survey-guid";

    // Note that this is canonically a set. However, Swagger only supports a list, so some wonkiness happens.
    private static final Set<String> TAGS = ImmutableSet.of("foo", "bar", "baz");

    private static SharedModulesApi apiDeveloperModulesApi;
    private static SharedModulesApi sharedDeveloperModulesApi;
    private static SharedModulesApi nonAuthSharedModulesApi;

    private String moduleId;

    @BeforeClass
    public static void beforeClass() {
        TestUserHelper.TestUser apiDeveloper = TestUserHelper.getSignedInApiDeveloper();
        apiDeveloperModulesApi = apiDeveloper.getClient(SharedModulesApi.class);
        TestUserHelper.TestUser sharedDeveloper = TestUserHelper.getSignedInSharedDeveloper();
        sharedDeveloperModulesApi = sharedDeveloper.getClient(SharedModulesApi.class);
        nonAuthSharedModulesApi = TestUserHelper.getNonAuthClient(SharedModulesApi.class);
    }

    @Before
    public void before() {
        moduleId = "integ-test-module-" + RandomStringUtils.randomAlphabetic(4);
    }

    @After
    public void after() throws Exception {
        try {
            sharedDeveloperModulesApi.deleteMetadataByIdAllVersions(moduleId).execute();
        } catch (EntityNotFoundException ex) {
            // Suppress the exception, as the test may have already deleted the module.
        }
    }

    @Test
    public void testNonAuthUserGetAndQueryCalls() throws Exception {
        // first create a test metadata
        SharedModuleMetadata metadataToCreate = new SharedModuleMetadata().id(moduleId).version(1)
                .name(MODULE_NAME).schemaId(SCHEMA_ID).schemaRevision(SCHEMA_REV);
        SharedModuleMetadata metadata = sharedDeveloperModulesApi.createMetadata(metadataToCreate).execute()
                .body();
        // execute query and get
        SharedModuleMetadata retMetadata = nonAuthSharedModulesApi.getMetadataByIdAndVersion(metadata.getId(), metadata.getVersion()).execute().body();
        assertEquals(metadata, retMetadata);

        retMetadata = nonAuthSharedModulesApi.getMetadataByIdLatestVersion(metadata.getId()).execute().body();
        assertEquals(metadata, retMetadata);

        SharedModuleMetadataList retMetadataList = nonAuthSharedModulesApi
                .queryAllMetadata(false, false, "id=" + "\'" + metadata.getId() + "\'", null).execute().body();
        assertEquals(1, retMetadataList.getItems().size());
        assertEquals(metadata, retMetadataList.getItems().get(0));

        retMetadataList = nonAuthSharedModulesApi.queryMetadataById(metadata.getId(), true, true, null, null).execute().body();
        assertEquals(1, retMetadataList.getItems().size());
        assertEquals(metadata, retMetadataList.getItems().get(0));
    }

    @Test
    public void crud() throws Exception {
        // Create a bunch of versions. This test various cases of version auto-incrementing and explicitly setting
        // versions.
        SharedModuleMetadata metadataV1 = testCreateGet(1, null);
        SharedModuleMetadata metadataV2 = testCreateGet(2, null);
        SharedModuleMetadata metadataV4 = testCreateGet(4, 4);
        SharedModuleMetadata metadataV6 = testCreateGet(6, 6);

        // Attempt to create v6 again. Will throw.
        try {
            sharedDeveloperModulesApi.createMetadata(metadataV6).execute();
            fail("expected exception");
        } catch (ConcurrentModificationException ex) {
            // expected exception
        }

        // Update v6. Verify the changes.
        SharedModuleMetadata metadataToUpdateV6 = new SharedModuleMetadata().id(moduleId).version(6)
                .name("Updated Module").schemaId(null).schemaRevision(null).surveyCreatedOn(SURVEY_CREATED_ON_STRING)
                .surveyGuid(SURVEY_GUID);
        SharedModuleMetadata updatedMetadataV6 = sharedDeveloperModulesApi.updateMetadata(moduleId, 6,
                metadataToUpdateV6).execute().body();
        assertEquals(moduleId, updatedMetadataV6.getId());
        assertEquals(6, updatedMetadataV6.getVersion().intValue());
        assertEquals("Updated Module", updatedMetadataV6.getName());
        assertNull(updatedMetadataV6.getSchemaId());
        assertNull(updatedMetadataV6.getSchemaRevision());
        assertEquals(SURVEY_CREATED_ON_STRING, updatedMetadataV6.getSurveyCreatedOn());
        assertEquals(SURVEY_GUID, updatedMetadataV6.getSurveyGuid());

        // Get latest. Verify it's same as the updated version.
        SharedModuleMetadata gettedUpdatedMetadataV6 = sharedDeveloperModulesApi.getMetadataByIdLatestVersion(moduleId)
                .execute().body();
        assertEquals(updatedMetadataV6, gettedUpdatedMetadataV6);

        // Get by ID and version each version. Verify it's the same as before.
        SharedModuleMetadata gettedByIdAndVersionV1 = sharedDeveloperModulesApi.getMetadataByIdAndVersion(moduleId, 1)
                .execute().body();
        assertEquals(metadataV1, gettedByIdAndVersionV1);

        SharedModuleMetadata gettedByIdAndVersionV2 = sharedDeveloperModulesApi.getMetadataByIdAndVersion(moduleId, 2)
                .execute().body();
        assertEquals(metadataV2, gettedByIdAndVersionV2);

        SharedModuleMetadata gettedByIdAndVersionV4 = sharedDeveloperModulesApi.getMetadataByIdAndVersion(moduleId, 4)
                .execute().body();
        assertEquals(metadataV4, gettedByIdAndVersionV4);

        SharedModuleMetadata gettedByIdAndVersionV6 = sharedDeveloperModulesApi.getMetadataByIdAndVersion(moduleId, 6)
                .execute().body();
        assertEquals(updatedMetadataV6, gettedByIdAndVersionV6);

        // Delete v2. Latest is still v6.
        sharedDeveloperModulesApi.deleteMetadataByIdAndVersion(moduleId, 2).execute();
        SharedModuleMetadata gettedLatestAfterDeleteV2 = sharedDeveloperModulesApi.getMetadataByIdLatestVersion(
                moduleId).execute().body();
        assertEquals(updatedMetadataV6, gettedLatestAfterDeleteV2);

        // Delete v6. Latest is now v4.
        sharedDeveloperModulesApi.deleteMetadataByIdAndVersion(moduleId, 6).execute();
        SharedModuleMetadata gettedLatestAfterDeleteV6 = sharedDeveloperModulesApi.getMetadataByIdLatestVersion(
                moduleId).execute().body();
        assertEquals(metadataV4, gettedLatestAfterDeleteV6);

        // Delete all. Query by ID now returns an empty list.
        sharedDeveloperModulesApi.deleteMetadataByIdAllVersions(moduleId).execute();
        List<SharedModuleMetadata> metadataListAfterDeleteAll = sharedDeveloperModulesApi.queryMetadataById(moduleId,
                false, false, null, null).execute().body().getItems();
        assertEquals(0, metadataListAfterDeleteAll.size());
    }

    // Test helper to test create API and verify the get APIs return the expected result. Returns the created module
    // metadata.
    private SharedModuleMetadata testCreateGet(int expectedVersion, Integer inputVersion) throws Exception {
        // create
        SharedModuleMetadata metadataToCreate = new SharedModuleMetadata().id(moduleId).version(inputVersion)
                .name(MODULE_NAME).schemaId(SCHEMA_ID).schemaRevision(SCHEMA_REV);
        SharedModuleMetadata createdMetadata = sharedDeveloperModulesApi.createMetadata(metadataToCreate).execute()
                .body();
        assertEquals(moduleId, createdMetadata.getId());
        assertEquals(expectedVersion, createdMetadata.getVersion().intValue());
        assertEquals(MODULE_NAME, createdMetadata.getName());
        assertEquals(SCHEMA_ID, createdMetadata.getSchemaId());
        assertEquals(SCHEMA_REV, createdMetadata.getSchemaRevision().intValue());

        // get latest, make sure it matches
        SharedModuleMetadata gettedLatestMetadata = sharedDeveloperModulesApi.getMetadataByIdLatestVersion(moduleId)
                .execute().body();
        assertEquals(createdMetadata, gettedLatestMetadata);

        return createdMetadata;
    }

    @Test
    public void schemaModule() throws Exception {
        SharedModuleMetadata metadataToCreate = new SharedModuleMetadata().id(moduleId).name(MODULE_NAME)
                .schemaId(SCHEMA_ID).schemaRevision(SCHEMA_REV);
        SharedModuleMetadata createdMetadata = sharedDeveloperModulesApi.createMetadata(metadataToCreate).execute()
                .body();
        assertEquals(moduleId, createdMetadata.getId());
        assertNotNull(createdMetadata.getVersion());
        assertEquals(MODULE_NAME, createdMetadata.getName());
        assertEquals(SharedModuleType.SCHEMA, createdMetadata.getModuleType());
        assertEquals(SCHEMA_ID, createdMetadata.getSchemaId());
        assertEquals(SCHEMA_REV, createdMetadata.getSchemaRevision().intValue());
    }

    @Test
    public void surveyModule() throws Exception {
        SharedModuleMetadata metadataToCreate = new SharedModuleMetadata().id(moduleId).name(MODULE_NAME)
                .surveyCreatedOn(SURVEY_CREATED_ON_STRING).surveyGuid(SURVEY_GUID);
        SharedModuleMetadata createdMetadata = sharedDeveloperModulesApi.createMetadata(metadataToCreate).execute()
                .body();
        assertEquals(moduleId, createdMetadata.getId());
        assertNotNull(createdMetadata.getVersion());
        assertEquals(MODULE_NAME, createdMetadata.getName());
        assertEquals(SharedModuleType.SURVEY, createdMetadata.getModuleType());
        assertEquals(SURVEY_CREATED_ON_STRING, createdMetadata.getSurveyCreatedOn());
        assertEquals(SURVEY_GUID, createdMetadata.getSurveyGuid());
    }

    @Test
    public void optionalParams() throws Exception {
        SharedModuleMetadata metadataToCreate = new SharedModuleMetadata().id(moduleId).version(2).name(MODULE_NAME)
                .licenseRestricted(true).notes(NOTES).os(OS).published(true).schemaId(SCHEMA_ID)
                .schemaRevision(SCHEMA_REV).tags(ImmutableList.copyOf(TAGS));
        SharedModuleMetadata createdMetadata = sharedDeveloperModulesApi.createMetadata(metadataToCreate).execute()
                .body();
        assertEquals(moduleId, createdMetadata.getId());
        assertEquals(2, createdMetadata.getVersion().intValue());
        assertEquals(MODULE_NAME, createdMetadata.getName());
        assertTrue(createdMetadata.getLicenseRestricted());
        assertEquals(SharedModuleType.SCHEMA, createdMetadata.getModuleType());
        assertEquals(NOTES, createdMetadata.getNotes());
        assertEquals(OS, createdMetadata.getOs());
        assertTrue(createdMetadata.getPublished());
        assertEquals(SCHEMA_ID, createdMetadata.getSchemaId());
        assertEquals(SCHEMA_REV, createdMetadata.getSchemaRevision().intValue());
        assertEquals(TAGS, ImmutableSet.copyOf(createdMetadata.getTags()));
        assertEquals("SharedModuleMetadata", createdMetadata.getType());
    }

    @Test
    public void queryAll() throws Exception {
        // Create a few modules for the test
        SharedModuleMetadata moduleAV1ToCreate = new SharedModuleMetadata().id(moduleId + "A").version(1)
                .name("Module A Version 1").schemaId(SCHEMA_ID).schemaRevision(SCHEMA_REV).published(true).os("iOS")
                .addTagsItem("foo");
        SharedModuleMetadata moduleAV1 = sharedDeveloperModulesApi.createMetadata(moduleAV1ToCreate).execute().body();

        SharedModuleMetadata moduleAV2ToCreate = new SharedModuleMetadata().id(moduleId + "A").version(2)
                .name("Module A Version 2").schemaId(SCHEMA_ID).schemaRevision(SCHEMA_REV).published(false).os("iOS");
        SharedModuleMetadata moduleAV2 = sharedDeveloperModulesApi.createMetadata(moduleAV2ToCreate).execute().body();

        SharedModuleMetadata moduleBV1ToCreate = new SharedModuleMetadata().id(moduleId + "B").version(1)
                .name("Module B Version 1").schemaId(SCHEMA_ID).schemaRevision(SCHEMA_REV).published(true)
                .os("Android").addTagsItem("bar");
        SharedModuleMetadata moduleBV1 = sharedDeveloperModulesApi.createMetadata(moduleBV1ToCreate).execute().body();

        SharedModuleMetadata moduleBV2ToCreate = new SharedModuleMetadata().id(moduleId + "B").version(2)
                .name("Module B Version 2").schemaId(SCHEMA_ID).schemaRevision(SCHEMA_REV).published(true)
                .os("Android");
        SharedModuleMetadata moduleBV2 = sharedDeveloperModulesApi.createMetadata(moduleBV2ToCreate).execute().body();

        try {
            // Case 1: query with both mostrecent=true and where throws exception
            try {
                sharedDeveloperModulesApi.queryAllMetadata(true, false, "licenseRestricted=true", null).execute();
                fail("expected exception");
            } catch (BadRequestException ex) {
                // expected exception
            }

            // Case 2: garbled query
            try {
                sharedDeveloperModulesApi.queryAllMetadata(false, false, "blargg", null).execute();
                fail("expected exception");
            } catch (BadRequestException ex) {
                // expected exception
            }

            // Note that since there may be other modules in the shared study, we can't rely on result counts. Instead,
            // well need to test for the presence and absence of our test modules.

            // Case 3: most recent published (returns AV1 and BV2)
            List<SharedModuleMetadata> case3MetadataList = sharedDeveloperModulesApi.queryAllMetadata(true, true, null,
                    null).execute().body().getItems();
            assertTrue(case3MetadataList.contains(moduleAV1));
            assertFalse(case3MetadataList.contains(moduleAV2));
            assertFalse(case3MetadataList.contains(moduleBV1));
            assertTrue(case3MetadataList.contains(moduleBV2));

            // Case 4: most recent (returns AV2 and BV2)
            List<SharedModuleMetadata> case4MetadataList = sharedDeveloperModulesApi.queryAllMetadata(true, false,
                    null, null).execute().body().getItems();
            assertFalse(case4MetadataList.contains(moduleAV1));
            assertTrue(case4MetadataList.contains(moduleAV2));
            assertFalse(case4MetadataList.contains(moduleBV1));
            assertTrue(case4MetadataList.contains(moduleBV2));

            // Case 5: published, where os='iOS' (returns AV1)
            List<SharedModuleMetadata> case5MetadataList = sharedDeveloperModulesApi.queryAllMetadata(false, true,
                    "os='iOS'", null).execute().body().getItems();
            assertTrue(case5MetadataList.contains(moduleAV1));
            assertFalse(case5MetadataList.contains(moduleAV2));
            assertFalse(case5MetadataList.contains(moduleBV1));
            assertFalse(case5MetadataList.contains(moduleBV2));

            // Case 6: published, no where clause (returns AV1, BV1, BV2)
            List<SharedModuleMetadata> case6MetadataList = sharedDeveloperModulesApi.queryAllMetadata(false, true,
                    null, null).execute().body().getItems();
            assertTrue(case6MetadataList.contains(moduleAV1));
            assertFalse(case6MetadataList.contains(moduleAV2));
            assertTrue(case6MetadataList.contains(moduleBV1));
            assertTrue(case6MetadataList.contains(moduleBV2));

            // Case 7: where os='Android' (returns BV1, BV2)
            List<SharedModuleMetadata> case7MetadataList = sharedDeveloperModulesApi.queryAllMetadata(false, false,
                    "os='Android'", null).execute().body().getItems();
            assertFalse(case7MetadataList.contains(moduleAV1));
            assertFalse(case7MetadataList.contains(moduleAV2));
            assertTrue(case7MetadataList.contains(moduleBV1));
            assertTrue(case7MetadataList.contains(moduleBV2));

            // Case 8: where os='Android', tags=bar (returns BV1)
            List<SharedModuleMetadata> case8MetadataList = sharedDeveloperModulesApi.queryAllMetadata(false, false,
                    "os='Android'", "bar").execute().body().getItems();
            assertFalse(case8MetadataList.contains(moduleAV1));
            assertFalse(case8MetadataList.contains(moduleAV2));
            assertTrue(case8MetadataList.contains(moduleBV1));
            assertFalse(case8MetadataList.contains(moduleBV2));

            // Case 9: multiple tags (returns AV1, BV1)
            List<SharedModuleMetadata> case9MetadataList = sharedDeveloperModulesApi.queryAllMetadata(false, false,
                    null, "foo,bar").execute().body().getItems();
            assertTrue(case9MetadataList.contains(moduleAV1));
            assertFalse(case9MetadataList.contains(moduleAV2));
            assertTrue(case9MetadataList.contains(moduleBV1));
            assertFalse(case9MetadataList.contains(moduleBV2));

            // Case 10: all results
            List<SharedModuleMetadata> case10MetadataList = sharedDeveloperModulesApi.queryAllMetadata(false, false,
                    null, null).execute().body().getItems();
            assertTrue(case10MetadataList.contains(moduleAV1));
            assertTrue(case10MetadataList.contains(moduleAV2));
            assertTrue(case10MetadataList.contains(moduleBV1));
            assertTrue(case10MetadataList.contains(moduleBV2));

            // Case 11: no results
            List<SharedModuleMetadata> case11MetadataList = sharedDeveloperModulesApi.queryAllMetadata(false, false,
                    "licenseRestricted=true", null).execute().body().getItems();
            assertFalse(case11MetadataList.contains(moduleAV1));
            assertFalse(case11MetadataList.contains(moduleAV2));
            assertFalse(case11MetadataList.contains(moduleBV1));
            assertFalse(case11MetadataList.contains(moduleBV2));
        } finally {
            try {
                sharedDeveloperModulesApi.deleteMetadataByIdAllVersions(moduleId + "A").execute();
            } catch (BridgeSDKException ex) {
                LOG.error("Error deleting module " + moduleId + "A: " + ex.getMessage(), ex);
            }

            try {
                sharedDeveloperModulesApi.deleteMetadataByIdAllVersions(moduleId + "B").execute();
            } catch (BridgeSDKException ex) {
                LOG.error("Error deleting module " + moduleId + "B: " + ex.getMessage(), ex);
            }
        }
    }

    @Test
    public void queryById() throws Exception {
        // Note that full query logic is tested in queryAll(). This tests an abbreviated set of logic to make sure
        // everything is plumbed through correctly.

        // Create a few module versions for test. Create a module with a different ID to make sure it's not also being
        // returned.
        SharedModuleMetadata moduleV1ToCreate = new SharedModuleMetadata().id(moduleId).version(1)
                .name("Test Module Version 1").schemaId(SCHEMA_ID).schemaRevision(SCHEMA_REV).published(true)
                .licenseRestricted(true);
        SharedModuleMetadata moduleV1 = sharedDeveloperModulesApi.createMetadata(moduleV1ToCreate).execute().body();

        SharedModuleMetadata moduleV2ToCreate = new SharedModuleMetadata().id(moduleId).version(2)
                .name("Test Module Version 2").schemaId(SCHEMA_ID).schemaRevision(SCHEMA_REV).published(true);
        SharedModuleMetadata moduleV2 = sharedDeveloperModulesApi.createMetadata(moduleV2ToCreate).execute().body();

        SharedModuleMetadata moduleV3ToCreate = new SharedModuleMetadata().id(moduleId).version(3)
                .name("Test Module Version 3").schemaId(SCHEMA_ID).schemaRevision(SCHEMA_REV).published(false)
                .addTagsItem("foo");
        SharedModuleMetadata moduleV3 = sharedDeveloperModulesApi.createMetadata(moduleV3ToCreate).execute().body();

        SharedModuleMetadata moduleV4ToCreate = new SharedModuleMetadata().id(moduleId).version(4)
                .name("Test Module Version 4").schemaId(SCHEMA_ID).schemaRevision(SCHEMA_REV).published(false);
        SharedModuleMetadata moduleV4 = sharedDeveloperModulesApi.createMetadata(moduleV4ToCreate).execute().body();

        SharedModuleMetadata otherModuleToCreate = new SharedModuleMetadata().id(moduleId + "other").version(1)
                .name("Other Module").schemaId(SCHEMA_ID).schemaRevision(SCHEMA_REV);
        sharedDeveloperModulesApi.createMetadata(otherModuleToCreate).execute().body();

        try {
            // Note that since we are query by module ID, and module ID is relatively unique to this test, we *can*
            // use counts to know exactly what results we expect.

            // Case 1: most recent (v4)
            List<SharedModuleMetadata> case1MetadataList = sharedDeveloperModulesApi.queryMetadataById(moduleId, true,
                    false, null, null).execute().body().getItems();
            assertEquals(1, case1MetadataList.size());
            assertTrue(case1MetadataList.contains(moduleV4));

            // Case 2: published (v1, v2)
            List<SharedModuleMetadata> case2MetadataList = sharedDeveloperModulesApi.queryMetadataById(moduleId, false,
                    true, null, null).execute().body().getItems();
            assertEquals(2, case2MetadataList.size());
            assertTrue(case2MetadataList.contains(moduleV1));
            assertTrue(case2MetadataList.contains(moduleV2));

            // Case 3: where licenseRestricted=true (v1)
            List<SharedModuleMetadata> case3MetadataList = sharedDeveloperModulesApi.queryMetadataById(moduleId, false,
                    false, "licenseRestricted=true", null).execute().body().getItems();
            assertEquals(1, case3MetadataList.size());
            assertTrue(case3MetadataList.contains(moduleV1));

            // Case 4: tags=foo (v3)
            List<SharedModuleMetadata> case4MetadataList = sharedDeveloperModulesApi.queryMetadataById(moduleId, false,
                    false, null, "foo").execute().body().getItems();
            assertEquals(1, case4MetadataList.size());
            assertTrue(case4MetadataList.contains(moduleV3));
        } finally {
            try {
                sharedDeveloperModulesApi.deleteMetadataByIdAllVersions(moduleId + "other").execute();
            } catch (BridgeSDKException ex) {
                LOG.error("Error deleting module " + moduleId + "other: " + ex.getMessage(), ex);
            }
        }
    }

    @Test(expected = EntityNotFoundException.class)
    public void deleteByIdAllVersions404() throws Exception {
        sharedDeveloperModulesApi.deleteMetadataByIdAllVersions(moduleId).execute();
    }

    @Test(expected = EntityNotFoundException.class)
    public void deleteByIdAndVersion404() throws Exception {
        sharedDeveloperModulesApi.deleteMetadataByIdAndVersion(moduleId, 1).execute();
    }

    @Test(expected = EntityNotFoundException.class)
    public void getByIdAndVersion404() throws Exception {
        sharedDeveloperModulesApi.getMetadataByIdAndVersion(moduleId, 1).execute();
    }

    @Test(expected = EntityNotFoundException.class)
    public void getByIdLatest404() throws Exception {
        sharedDeveloperModulesApi.getMetadataByIdLatestVersion(moduleId).execute();
    }

    @Test(expected = EntityNotFoundException.class)
    public void update404() throws Exception {
        SharedModuleMetadata metadata = new SharedModuleMetadata().id(moduleId).version(1).name(MODULE_NAME)
                .schemaId(SCHEMA_ID).schemaRevision(SCHEMA_REV);
        sharedDeveloperModulesApi.updateMetadata(moduleId, 1, metadata).execute();
    }

    @Test(expected = UnauthorizedException.class)
    public void nonSharedDeveloperCantCreate() throws Exception {
        SharedModuleMetadata metadata = new SharedModuleMetadata().id(moduleId).version(1).name(MODULE_NAME)
                .schemaId(SCHEMA_ID).schemaRevision(SCHEMA_REV);
        apiDeveloperModulesApi.createMetadata(metadata).execute();
    }

    @Test(expected = UnauthorizedException.class)
    public void nonSharedDeveloperCantDeleteByIdAllVersions() throws Exception {
        apiDeveloperModulesApi.deleteMetadataByIdAllVersions(moduleId).execute();
    }

    @Test(expected = UnauthorizedException.class)
    public void nonSharedDeveloperCantDeleteByIdAndVersion() throws Exception {
        apiDeveloperModulesApi.deleteMetadataByIdAndVersion(moduleId, 1).execute();
    }

    @Test(expected = UnauthorizedException.class)
    public void nonSharedDeveloperCantUpdate() throws Exception {
        SharedModuleMetadata metadata = new SharedModuleMetadata().id(moduleId).version(1).name(MODULE_NAME)
                .schemaId(SCHEMA_ID).schemaRevision(SCHEMA_REV);
        apiDeveloperModulesApi.updateMetadata(moduleId, 1, metadata).execute();
    }
}
