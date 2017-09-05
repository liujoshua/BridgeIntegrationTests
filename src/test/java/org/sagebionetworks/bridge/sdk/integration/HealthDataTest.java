package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.apache.commons.lang3.RandomStringUtils;
import org.joda.time.DateTime;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.sagebionetworks.bridge.rest.RestUtils;
import org.sagebionetworks.bridge.rest.api.HealthDataApi;
import org.sagebionetworks.bridge.rest.api.ParticipantsApi;
import org.sagebionetworks.bridge.rest.api.UploadSchemasApi;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.model.HealthDataRecord;
import org.sagebionetworks.bridge.rest.model.HealthDataSubmission;
import org.sagebionetworks.bridge.rest.model.SharingScope;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.rest.model.UploadFieldDefinition;
import org.sagebionetworks.bridge.rest.model.UploadFieldType;
import org.sagebionetworks.bridge.rest.model.UploadSchema;
import org.sagebionetworks.bridge.rest.model.UploadSchemaType;

@Category(IntegrationSmokeTest.class)
@SuppressWarnings("unchecked")
public class HealthDataTest {
    private static final String APP_VERSION = "version 1.0.0, build 2";
    private static final DateTime CREATED_ON = DateTime.parse("2017-08-24T14:38:57.340+0900");
    private static final String CREATED_ON_TIMEZONE = "+0900";
    private static final String PHONE_INFO = "Integration Tests";
    private static final String SCHEMA_ID = "health-data-integ-test-schema";
    private static final long SCHEMA_REV = 1L;

    private static String externalId;
    private static TestUserHelper.TestUser user;

    @BeforeClass
    public static void beforeClass() throws Exception {
        // Ensure schema exists, so we have something to submit against.
        TestUserHelper.TestUser developer = TestUserHelper.getSignedInApiDeveloper();
        UploadSchemasApi uploadSchemasApi = developer.getClient(UploadSchemasApi.class);
        UploadSchema schema = null;
        try {
            schema = uploadSchemasApi.getUploadSchema(SCHEMA_ID, SCHEMA_REV).execute().body();
        } catch (EntityNotFoundException ex) {
            // no-op
        }
        if (schema == null) {
            UploadFieldDefinition fooField = new UploadFieldDefinition();
            fooField.setName("foo");
            fooField.setType(UploadFieldType.STRING);
            fooField.setMaxLength(24);

            UploadFieldDefinition barField = new UploadFieldDefinition();
            barField.setName("bar");
            barField.setType(UploadFieldType.ATTACHMENT_V2);
            barField.setMimeType("text/json");
            barField.setFileExtension(".json");

            schema = new UploadSchema();
            schema.setSchemaId(SCHEMA_ID);
            schema.setRevision(SCHEMA_REV);
            schema.setName("Health Data Integ Test Schema");
            schema.setSchemaType(UploadSchemaType.IOS_DATA);
            schema.setFieldDefinitions(ImmutableList.of(fooField, barField));
            uploadSchemasApi.createUploadSchema(schema).execute();
        }

        // Set up user with data groups, external ID, and sharing scope.
        user = TestUserHelper.createAndSignInUser(UploadTest.class, true);
        externalId = RandomStringUtils.randomAlphabetic(4);
        ParticipantsApi participantsApi = user.getClient(ParticipantsApi.class);

        StudyParticipant participant = participantsApi.getUsersParticipantRecord().execute().body();
        participant.setDataGroups(ImmutableList.of("group1"));
        participant.setExternalId(externalId);
        participant.setSharingScope(SharingScope.SPONSORS_AND_PARTNERS);
        participantsApi.updateUsersParticipantRecord(participant).execute();
    }

    @AfterClass
    public static void deleteUser() throws Exception {
        if (user != null) {
            user.signOutAndDeleteUser();
        }
    }

    @Test
    public void test() throws Exception {
        // make health data to submit - Use a map instead of a Jackson JSON node, because mixing JSON libraries causes
        // bad things to happen.
        Map<String, String> data = ImmutableMap.<String, String>builder().put("foo", "foo value")
                .put("bar", "This is an attachment").build();
        HealthDataSubmission submission = new HealthDataSubmission().appVersion(APP_VERSION).createdOn(CREATED_ON)
                .data(data).phoneInfo(PHONE_INFO).schemaId(SCHEMA_ID).schemaRevision(SCHEMA_REV);

        // submit and validate
        HealthDataRecord record = user.getClient(HealthDataApi.class).submitHealthData(submission).execute().body();
        assertEquals(APP_VERSION, record.getAppVersion());
        assertNotNull(record.getId());
        assertEquals(PHONE_INFO, record.getPhoneInfo());
        assertEquals(SCHEMA_ID, record.getSchemaId());
        assertEquals(SCHEMA_REV, record.getSchemaRevision().longValue());
        assertNotNull(record.getUploadDate());
        assertNotNull(record.getUploadedOn());
        assertEquals(SharingScope.SPONSORS_AND_PARTNERS, record.getUserSharingScope());
        assertEquals(externalId, record.getUserExternalId());
        assertEquals(ImmutableList.of("group1"), record.getUserDataGroups());

        // createdOn is flattened to UTC server side.
        assertEquals(CREATED_ON.getMillis(), record.getCreatedOn().getMillis());
        assertEquals(CREATED_ON_TIMEZONE, record.getCreatedOnTimeZone());

        // Data has foo "foo value" and bar is an attachment.
        Map<String, String> returnedDataMap = RestUtils.toType(record.getData(), Map.class);
        assertEquals(2, returnedDataMap.size());
        assertEquals("foo value", returnedDataMap.get("foo"));
        assertTrue(returnedDataMap.containsKey("bar"));
        assertNotEquals("This is an attachment", returnedDataMap.get("bar"));

        // Metadata just contains app version and phone info
        Map<String, String> returnedMetadataMap = RestUtils.toType(record.getMetadata(), Map.class);
        assertEquals(2, returnedMetadataMap.size());
        assertEquals(APP_VERSION, returnedMetadataMap.get("appVersion"));
        assertEquals(PHONE_INFO, returnedMetadataMap.get("phoneInfo"));
    }
}
