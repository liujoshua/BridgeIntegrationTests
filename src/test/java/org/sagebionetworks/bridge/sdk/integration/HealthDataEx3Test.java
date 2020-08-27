package org.sagebionetworks.bridge.sdk.integration;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.api.ForSuperadminsApi;
import org.sagebionetworks.bridge.rest.api.ParticipantsApi;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.model.HealthDataRecordEx3;
import org.sagebionetworks.bridge.rest.model.HealthDataRecordEx3List;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.util.IntegTestUtils;

@SuppressWarnings("ConstantConditions")
public class HealthDataEx3Test {
    private static final String DUMMY_STUDY_ID = "dummy-study";
    private static final String TEST_CLIENT_INFO = "Integration Tests";

    private static TestUserHelper.TestUser admin;
    private static DateTime createdOn;

    private TestUserHelper.TestUser user;
    private String userHealthCode;

    @BeforeClass
    public static void beforeClass() {
        admin = TestUserHelper.getSignedInAdmin();
        createdOn = DateTime.now(DateTimeZone.UTC);
    }

    @Before
    public void before() throws Exception {
        user = TestUserHelper.createAndSignInUser(SurveyTest.class, true);
        userHealthCode = admin.getClient(ParticipantsApi.class).getParticipantById(user.getUserId(), false)
                .execute().body().getHealthCode();
    }

    @After
    public void deleteUser() throws Exception {
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
                createdOnEnd, null, null).execute().body().getItems();
        assertEquals(1, recordList.size());
        assertEquals(record, recordList.get(0));

        // List by app. There may be more than one. Filter for the one that we know about.
        recordList = superadminsApi.getRecordsEx3ForCurrentApp(createdOnStart, createdOnEnd, null, null).execute()
                .body().getItems().stream().filter(r -> r.getId().equals(recordId)).collect(Collectors.toList());
        assertEquals(1, recordList.size());
        assertEquals(record, recordList.get(0));

        // List by study. There may be more than one. Filter for the one that we know about.
        recordList = superadminsApi.getRecordsEx3ForStudy(DUMMY_STUDY_ID, createdOnStart, createdOnEnd, null, null).execute()
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
        recordList = superadminsApi.getRecordsEx3ForUser(user.getUserId(), createdOnStart, createdOnEnd, null, null).execute()
                .body().getItems();
        assertTrue(recordList.isEmpty());
    }

    @Test
    public void testPagination() throws Exception {
        ForSuperadminsApi superadminsApi = admin.getClient(ForSuperadminsApi.class);

        // Create 5 health datas for the user. healthCode and createdOn are the only values required.
        // App ID is automatically set by the server. Add study ID for tests.
        // Space out the health datas a little bit to help set up our indices.
        HealthDataRecordEx3[] recordArray = new HealthDataRecordEx3[5];
        for (int i = 0; i < recordArray.length; i++) {
            Thread.sleep(500);

            recordArray[i] = new HealthDataRecordEx3();
            recordArray[i].setCreatedOn(DateTime.now());
            recordArray[i].setHealthCode(userHealthCode);
            recordArray[i].setStudyId(DUMMY_STUDY_ID);
            recordArray[i] = superadminsApi.createOrUpdateRecordEx3(recordArray[i]).execute().body();
        }
        DateTime createdOnStart = recordArray[0].getCreatedOn();
        DateTime createdOnEnd = recordArray[4].getCreatedOn();

        // The list APIs use a Global Secondary Index. Sleep for a bit.
        Thread.sleep(2000);

        paginationHelper(recordArray, createdOnStart, createdOnEnd, nextOffsetKey -> superadminsApi
                .getRecordsEx3ForUser(user.getUserId(), createdOnStart, createdOnEnd, 2, nextOffsetKey)
                .execute().body());

        paginationHelper(recordArray, createdOnStart, createdOnEnd, nextOffsetKey -> superadminsApi
                .getRecordsEx3ForCurrentApp(createdOnStart, createdOnEnd, 2, nextOffsetKey)
                .execute().body());

        paginationHelper(recordArray, createdOnStart, createdOnEnd, nextOffsetKey -> superadminsApi
                .getRecordsEx3ForStudy(DUMMY_STUDY_ID, createdOnStart, createdOnEnd, 2, nextOffsetKey)
                .execute().body());
    }

    private void paginationHelper(HealthDataRecordEx3[] recordArray, DateTime createdOnStart, DateTime createdOnEnd,
            ThrowingFunction<String, HealthDataRecordEx3List> function) throws Exception {
        // Call pagination APIs with page size 2. There should be a minimum of 3 pages per call.
        List<HealthDataRecordEx3> recordList = new ArrayList<>();
        String nextOffsetKey = null;
        int numPages = 0;
        do {
            HealthDataRecordEx3List recordPageList = function.apply(nextOffsetKey);
            assertEquals(createdOnStart, recordPageList.getRequestParams().getStartTime());
            assertEquals(createdOnEnd, recordPageList.getRequestParams().getEndTime());
            assertEquals(2, recordPageList.getRequestParams().getPageSize().intValue());
            assertEquals(nextOffsetKey, recordPageList.getRequestParams().getOffsetKey());

            // Filter out records that aren't from our user.
            for (HealthDataRecordEx3 record : recordPageList.getItems()) {
                if (userHealthCode.equals(record.getHealthCode())) {
                    recordList.add(record);
                }
            }
            nextOffsetKey = recordPageList.getNextPageOffsetKey();

            numPages++;
            if (numPages > 10) {
                // If there this many records in the test study for this time range, something has gone wrong.
                // Short-cut and error out.
                fail("Too many health data records");
            }
        } while (nextOffsetKey != null);

        assertEquals(5, recordList.size());
        for (int i = 0; i < 5; i++) {
            assertEquals(recordArray[i].getId(), recordList.get(i).getId());
        }
    }
}
