package org.sagebionetworks.bridge.sdk.integration;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.stream.Collectors;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.api.ForSuperadminsApi;
import org.sagebionetworks.bridge.rest.api.ParticipantsApi;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.model.HealthDataRecordEx3;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.util.IntegTestUtils;

@SuppressWarnings("ConstantConditions")
public class HealthDataEx3Test {
    private static final String DUMMY_STUDY_ID = "dummy-study";
    private static final String TEST_CLIENT_INFO = "Integration Tests";

    private static TestUserHelper.TestUser admin;
    private static DateTime createdOn;
    private static TestUserHelper.TestUser user;
    private static String userHealthCode;

    @BeforeClass
    public static void beforeClass() throws Exception {
        admin = TestUserHelper.getSignedInAdmin();
        user = TestUserHelper.createAndSignInUser(SurveyTest.class, true);
        userHealthCode = admin.getClient(ParticipantsApi.class).getParticipantById(user.getUserId(), false)
                .execute().body().getHealthCode();

        createdOn = DateTime.now(DateTimeZone.UTC);
    }

    @AfterClass
    public static void deleteUser() throws Exception {
        if (user != null) {
            user.signOutAndDeleteUser();
        }
    }

    @Test
    public void test() throws Exception {
        ForSuperadminsApi superadminsApi = admin.getClient(ForSuperadminsApi.class);

        // Create. App ID is set automatically by Bridge. Set the remaining values for test.
        HealthDataRecordEx3 record = new HealthDataRecordEx3();
        record.setClientInfo(TEST_CLIENT_INFO);
        record.setCreatedOn(createdOn);
        record.setExported(false);
        record.setHealthCode(userHealthCode);
        record.putMetadataItem("foo", "foo-value");
        record.setStudyId(DUMMY_STUDY_ID);
        record = superadminsApi.createOrUpdateRecordEx3(record).execute().body();
        String recordId = record.getId();
        assertNotNull(recordId);

        assertEquals(IntegTestUtils.TEST_APP_ID, record.getAppId());
        assertEquals(TEST_CLIENT_INFO, record.getClientInfo());
        assertEquals(createdOn, record.getCreatedOn());
        assertFalse(record.isExported());
        assertEquals(userHealthCode, record.getHealthCode());
        assertEquals(DUMMY_STUDY_ID, record.getStudyId());
        assertEquals(1, record.getVersion().intValue());

        assertEquals(1, record.getMetadata().size());
        assertEquals("foo-value", record.getMetadata().get("foo"));

        // Test get api.
        HealthDataRecordEx3 retrievedRecord = superadminsApi.getRecordEx3(recordId).execute().body();
        assertEquals(record, retrievedRecord);

        // Make a simple update to test update API.
        record.putMetadataItem("bar", "bar-value");
        record = superadminsApi.createOrUpdateRecordEx3(record).execute().body();
        assertEquals(recordId, record.getId());
        assertEquals(2, record.getVersion().intValue());

        assertEquals(2, record.getMetadata().size());
        assertEquals("foo-value", record.getMetadata().get("foo"));
        assertEquals("bar-value", record.getMetadata().get("bar"));

        // The list APIs use a Global Secondary Index. Sleep for a bit.
        Thread.sleep(2000);

        // We only expect one record, but the start time and end time have to be different.
        DateTime createdOnStart = createdOn.minusMillis(1);
        DateTime createdOnEnd = createdOn.plusMillis(1);

        // List by user.
        List<HealthDataRecordEx3> recordList = superadminsApi.getRecordsEx3ForUser(user.getUserId(), createdOnStart,
                createdOnEnd).execute().body().getItems();
        assertEquals(1, recordList.size());
        assertEquals(record, recordList.get(0));

        // List by app. There may be more than one. Filter for the one that we know about.
        recordList = superadminsApi.getRecordsEx3ForCurrentApp(createdOnStart, createdOnEnd).execute()
                .body().getItems().stream().filter(r -> r.getId().equals(recordId)).collect(Collectors.toList());
        assertEquals(1, recordList.size());
        assertEquals(record, recordList.get(0));

        // List by study. There may be more than one. Filter for the one that we know about.
        recordList = superadminsApi.getRecordsEx3ForStudy(DUMMY_STUDY_ID, createdOnStart, createdOnEnd).execute()
                .body().getItems().stream().filter(r -> r.getId().equals(recordId)).collect(Collectors.toList());
        assertEquals(1, recordList.size());
        assertEquals(record, recordList.get(0));

        // Delete record.
        superadminsApi.deleteRecordsEx3ForUser(user.getUserId()).execute();

        // Get will now throw.
        try {
            superadminsApi.getRecordEx3(recordId).execute();
            fail("expected exception");
        } catch (EntityNotFoundException ex) {
            // expected exception
        }

        // The list APIs use a Global Secondary Index. Sleep for a bit.
        Thread.sleep(2000);

        // List by user will now return an empty list.
        recordList = superadminsApi.getRecordsEx3ForUser(user.getUserId(), createdOnStart, createdOnEnd).execute()
                .body().getItems();
        assertTrue(recordList.isEmpty());
    }
}
