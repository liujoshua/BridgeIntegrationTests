package org.sagebionetworks.bridge.sdk.integration;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sagebionetworks.bridge.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.rest.api.ForWorkersApi;
import org.sagebionetworks.bridge.rest.model.ParticipantData;
import org.sagebionetworks.bridge.rest.model.StringList;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.sagebionetworks.bridge.rest.model.Role.WORKER;

public class ParticipantDataTest {

    private static final String USER_ID = "aUserId";
    private static final String IDENTIFIER = "anIdentifier";

    private static TestUser admin;
    private static TestUser worker;

    private TestUser user;
    private String identifier1;
    private String identifier2;
    private String identifier3;

    @Before
    public void before() throws Exception {
        admin = TestUserHelper.getSignedInAdmin();
        worker = TestUserHelper.createAndSignInUser(ParticipantDataTest.class, false, WORKER);
        identifier1 = Tests.randomIdentifier(ParticipantDataTest.class);
        identifier2 = Tests.randomIdentifier(ParticipantDataTest.class);
        identifier3 = Tests.randomIdentifier(ParticipantDataTest.class);
    }

    @After
    public void after() throws Exception {
        if (user != null) {
            user.signOutAndDeleteUser();
        }
        if (worker != null) {
            worker.signOutAndDeleteUser();
        }
    }

    @Test
    public void workerCanCrudParticipantData() throws IOException {
        user = TestUserHelper.createAndSignInUser(ParticipantDataTest.class, true);

        String userId= user.getUserId();
        String appId = user.getAppId();
        String offsetKey = null;
        Integer pageSize = 10;
        ForWorkersApi workersApi = worker.getClient(ForWorkersApi.class);

        // worker can create participant data
        workersApi.saveDataForAdminWorker(appId, userId, identifier1, createParticipantData("foo", "A")).execute();
        workersApi.saveDataForAdminWorker(appId, userId, identifier2, createParticipantData("bar", "B")).execute();
        workersApi.saveDataForAdminWorker(appId, userId, identifier3, createParticipantData("baz", "C")).execute();

        // user can get those participant data
        ForConsentedUsersApi usersApi = user.getClient(ForConsentedUsersApi.class);

        StringList results = usersApi.getAllDataForSelf(offsetKey, pageSize).execute().body();
        assertEquals(3, results.getItems().size());
        HashSet<String> expectedIdentifiers = new HashSet<>(Arrays.asList(identifier1, identifier2, identifier3));
        HashSet<String> actualIdentifiers = new HashSet<>(results.getItems());
        assertFalse(actualIdentifiers.retainAll(expectedIdentifiers));

        // worker can get those participant data
        results = workersApi.getAllDataForAdminWorker(appId, userId, offsetKey, pageSize).execute().body();
        assertEquals(3, results.getItems().size());
        actualIdentifiers = new HashSet<>(results.getItems());
        assertFalse(actualIdentifiers.retainAll(expectedIdentifiers));
    }

    @Test
    public void correctExceptionsOnBadRequest() {

    }

    @Test
    public void userCanCrudSelfData() {

    }

    @Test
    public void participantDataNotVisibleOutsideOfStudy() {

    }

    private static ParticipantData createParticipantData(String fieldValue1, String fieldValue2) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("field1", fieldValue1);
        node.put("field2", fieldValue2);
        ParticipantData participantData = new ParticipantData();
        participantData.setUserId(USER_ID);
        participantData.setIdentifier(IDENTIFIER);
        participantData.setData(String.valueOf(node));
        return participantData;
    }
}
