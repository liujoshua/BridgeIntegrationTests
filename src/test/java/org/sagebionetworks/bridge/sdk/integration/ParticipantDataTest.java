package org.sagebionetworks.bridge.sdk.integration;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.rest.api.ForWorkersApi;
import org.sagebionetworks.bridge.rest.api.UsersApi;
import org.sagebionetworks.bridge.rest.exceptions.BadRequestException;
import org.sagebionetworks.bridge.rest.model.ForwardCursorStringList;
import org.sagebionetworks.bridge.rest.model.ParticipantData;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.sagebionetworks.bridge.rest.model.Role.WORKER;

public class ParticipantDataTest {

    private static TestUser admin;
    private static TestUser worker;

    private TestUser user;
    private String identifier1;
    private String identifier2;
    private String identifier3;
    private String OFFSET_KEY = null;
    private Integer PAGE_SIZE = 10;


    @Before
    public void before() throws Exception {
        admin = TestUserHelper.getSignedInAdmin();
        worker = TestUserHelper.createAndSignInUser(ParticipantDataTest.class, false, WORKER);
        user = TestUserHelper.createAndSignInUser(ParticipantDataTest.class, true);

        identifier1 = Tests.randomIdentifier(ParticipantDataTest.class);
        identifier2 = Tests.randomIdentifier(ParticipantDataTest.class);
        identifier3 = Tests.randomIdentifier(ParticipantDataTest.class);

    }

    @After
    public void after() throws Exception {
        ForAdminsApi adminsApi = admin.getClient(ForAdminsApi.class);
        adminsApi.deleteAllParticipantDataForAdmin(user.getAppId(), user.getUserId());

        if (user != null) {
            user.signOutAndDeleteUser();
        }
        if (worker != null) {
            worker.signOutAndDeleteUser();
        }
    }

    @Test
    public void userCanCrudSelfData() throws IOException {
        UsersApi userApi = user.getClient(UsersApi.class);

        ParticipantData participantData1 = createParticipantData("foo", "A");
        ParticipantData participantData2 = createParticipantData("bar", "B");
        ParticipantData participantData3 = createParticipantData("baz", "C");

        // user can save participant data
        userApi.saveDataForSelf(identifier1, participantData1).execute();
        userApi.saveDataForSelf(identifier2, participantData2).execute();
        userApi.saveDataForSelf(identifier3, participantData3).execute();

        // user can get all the participant data identifiers
        ForwardCursorStringList results = userApi.getAllDataForSelf(OFFSET_KEY, PAGE_SIZE).execute().body();

        assertEquals(3, results.getItems().size());
        ImmutableSet<String> expectedIdentifiers = ImmutableSet.of(identifier1, identifier2, identifier3);
        HashSet<String> actualIdentifiers = new HashSet<>(results.getItems());
        assertEquals(expectedIdentifiers, actualIdentifiers);

        // user can get participant data by identifier
        ParticipantData actualData1 = userApi.getDataByIdentifierForSelf(identifier1).execute().body();
        ParticipantData actualData2 = userApi.getDataByIdentifierForSelf(identifier2).execute().body();
        ParticipantData actualData3 = userApi.getDataByIdentifierForSelf(identifier3).execute().body();
        assertParticipantData("foo", "A", actualData1);
        assertParticipantData("bar", "B", actualData2);
        assertParticipantData("baz", "C", actualData3);

        // user can delete data by identifier
        userApi.deleteDataByIdentifier(identifier1).execute();
        results = userApi.getAllDataForSelf(OFFSET_KEY, PAGE_SIZE).execute().body();
        assertEquals(results.getItems().size(), 2);

        // admin can delete data by identifier
        ForAdminsApi adminsApi = admin.getClient(ForAdminsApi.class);
        adminsApi.deleteDataForAdmin(user.getAppId(), user.getUserId(), identifier2).execute();
        results = userApi.getAllDataForSelf(OFFSET_KEY, PAGE_SIZE).execute().body();
        assertEquals(results.getItems().size(), 1);

        // admin can delete all data
        adminsApi.deleteAllParticipantDataForAdmin(user.getAppId(), user.getUserId()).execute();
        results = userApi.getAllDataForSelf(OFFSET_KEY, PAGE_SIZE).execute().body();
        assertTrue(results.getItems().isEmpty());
    }

    @Test
    public void workerCanCrudParticipantData() throws IOException {
        String userId= user.getUserId();
        String appId = user.getAppId();

        ForWorkersApi workersApi = worker.getClient(ForWorkersApi.class);

        // worker can create participant data
        workersApi.saveDataForAdminWorker(appId, userId, identifier1, createParticipantData("foo", "A")).execute();
        workersApi.saveDataForAdminWorker(appId, userId, identifier2, createParticipantData("bar", "B")).execute();
        workersApi.saveDataForAdminWorker(appId, userId, identifier3, createParticipantData("baz", "C")).execute();

        // user can get those participant data
        ForConsentedUsersApi usersApi = user.getClient(ForConsentedUsersApi.class);

        ForwardCursorStringList results = usersApi.getAllDataForSelf(OFFSET_KEY, PAGE_SIZE).execute().body();
        assertEquals(3, results.getItems().size());
        ImmutableSet<String> expectedIdentifiers = ImmutableSet.of(identifier1, identifier2, identifier3);
        HashSet<String> actualIdentifiers = new HashSet<>(results.getItems());
        assertEquals(expectedIdentifiers, actualIdentifiers);

        // worker can get those participant data
        results = workersApi.getAllDataForAdminWorker(appId, userId, OFFSET_KEY, PAGE_SIZE).execute().body();
        assertEquals(3, results.getItems().size());
        actualIdentifiers = new HashSet<>(results.getItems());
        assertEquals(expectedIdentifiers, actualIdentifiers);

        // worker can get the participant data by identifier
        ParticipantData actualData1 = workersApi.getDataByIdentifierForAdminWorker(appId, userId, identifier1).execute().body();
        ParticipantData actualData2 = workersApi.getDataByIdentifierForAdminWorker(appId, userId, identifier1).execute().body();
        ParticipantData actualData3 = workersApi.getDataByIdentifierForAdminWorker(appId, userId, identifier1).execute().body();

        // user can get the participant data by identifier (also tested in userCanCrudSelfData)
        ParticipantData userParticipantData1 = usersApi.getDataByIdentifierForSelf(identifier1).execute().body();
        ParticipantData userParticipantData2 = usersApi.getDataByIdentifierForSelf(identifier1).execute().body();
        ParticipantData userParticipantData3 = usersApi.getDataByIdentifierForSelf(identifier1).execute().body();

        assertEquals(actualData1, userParticipantData1);
        assertEquals(actualData2, userParticipantData2);
        assertEquals(actualData3, userParticipantData3);
    }

    @Test
    public void correctExceptionsOnBadRequest() throws Exception {
        user = TestUserHelper.createAndSignInUser(ParticipantDataTest.class, true);
        ForConsentedUsersApi usersApi = user.getClient(ForConsentedUsersApi.class);
        try {
            usersApi.getAllDataForSelf(OFFSET_KEY, 4).execute();
            fail("Should have thrown exception");
        } catch (BadRequestException e) {
            assertEquals("pageSize must be from 5-100 records", e.getMessage());
        }
        try {
            usersApi.getAllDataForSelf(OFFSET_KEY, 101).execute();
            fail("Should have thrown exception");
        } catch (BadRequestException e) {
            assertEquals("pageSize must be from 5-100 records", e.getMessage());
        }
    }

    @Test
    public void testPaginationGetAllDataForSelf() throws Exception {
        ForConsentedUsersApi usersApi = user.getClient(ForConsentedUsersApi.class);

        // create 10 participant datas and save data for self
        ParticipantData[] dataArray = new ParticipantData[PAGE_SIZE];
        for (int i = 0; i < PAGE_SIZE; i++) {
            dataArray[i] = createParticipantData("foo" + i, String.valueOf('a' + i));
            usersApi.saveDataForSelf(identifier1 + i, dataArray[i]).execute();
        }

        // get first 5 participant data
        ForwardCursorStringList pagedResults = usersApi.getAllDataForSelf(null, 5).execute().body();

        // check the first 5
        assertPages(pagedResults, identifier1, 0);
        String nextKey = pagedResults.getNextPageOffsetKey();
        assertNotNull(nextKey);

        // check the remaining 5
        pagedResults = usersApi.getAllDataForSelf(nextKey, 5).execute().body();
        assertPages(pagedResults, identifier1, 5);
        assertNull(pagedResults.getNextPageOffsetKey());
    }

    @Test
    public void testPaginationGetAllDataForAdminWorker() throws IOException {
        ForWorkersApi workersApi = worker.getClient(ForWorkersApi.class);

        // create 10 participant datas and save data for self
        ParticipantData[] dataArray = new ParticipantData[PAGE_SIZE];
        for (int i = 0; i < PAGE_SIZE; i++) {
            dataArray[i] = createParticipantData("foo" + i, String.valueOf('a' + i));
            workersApi.saveDataForAdminWorker(user.getAppId(), user.getUserId(), identifier1 + i, dataArray[i]).execute();
        }

        // get first 5 participant data
        ForwardCursorStringList pagedResults = workersApi.getAllDataForAdminWorker(
                user.getAppId(), user.getUserId(), null, 5).execute().body();

        // check the first 5
        assertPages(pagedResults, identifier1, 0);
        String nextKey = pagedResults.getNextPageOffsetKey();
        assertNotNull(nextKey);

        // check the remaining 5
        pagedResults = workersApi.getAllDataForAdminWorker(user.getAppId(), user.getUserId(), nextKey, 5).execute().body();
        assertPages(pagedResults, identifier1, 5);
        assertNull(pagedResults.getNextPageOffsetKey());
    }

    private static void assertPages(ForwardCursorStringList pagedResults, String expectedIdentifier, int start) {
        assertEquals(5, pagedResults.getItems().size());
        for (int i = start; i < 5 + start; i++) {
            assertEquals(expectedIdentifier + i, pagedResults.getItems().get(i - start));
        }
    }

    private static void assertParticipantData(String expectedKey, String expectedValue, ParticipantData participantData) {
        assertNotNull(participantData);
        Map<String, String> data = (Map<String, String>) participantData.getData();
        assertEquals(1, data.size());
        assertEquals(expectedValue, data.get(expectedKey));
    }

    private static ParticipantData createParticipantData(String fieldValue1, String fieldValue2) {
        ParticipantData participantData = new ParticipantData();
        participantData.setData(ImmutableMap.of(fieldValue1, fieldValue2));
        return participantData;
    }
}
