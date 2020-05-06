package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.IOException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpResponse;
import org.apache.http.client.fluent.Request;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.api.AppsApi;
import org.sagebionetworks.bridge.rest.model.App;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.user.TestUserHelper;

// This test makes raw HTTP requests, because we need to spoof the X-Forwarded-For header.
public class IpLockingTest {
    private static final ObjectMapper JSON_OBJECT_MAPPER = new ObjectMapper();

    private static TestUserHelper.TestUser basicUser;
    private static TestUserHelper.TestUser developer;
    private static String hostUrl;
    private static AppsApi appsApi;

    @BeforeClass
    public static void beforeClass() throws IOException {
        // Make test users
        basicUser = TestUserHelper.createAndSignInUser(IpLockingTest.class, true);
        developer = TestUserHelper.createAndSignInUser(IpLockingTest.class, true, Role.DEVELOPER);
        appsApi = developer.getClient(AppsApi.class);

        // Get host URL
        hostUrl = developer.getClientManager().getHostUrl();
    }

    @AfterClass
    public static void deleteBasicUser() throws Exception {
        if (basicUser != null) {
            basicUser.signOutAndDeleteUser();
        }
    }

    @AfterClass
    public static void deleteDeveloper() throws Exception {
        if (developer != null) {
            developer.signOutAndDeleteUser();
        }
    }

    @Test
    public void ipLockingForPrivilegedAccounts() throws Exception {
        test(developer, true);
    }

    @Test
    public void ipLockingForParticipantsDisabled() throws Exception {
        updateIpLockingFlag(false);
        test(basicUser, false);
    }

    @Test
    public void ipLockingForParticipantsEnabled() throws Exception {
        updateIpLockingFlag(true);
        test(basicUser, true);
    }

    private static void updateIpLockingFlag(boolean participantIpLockingEnabled) throws Exception {
        // Get app. We need an updated version of the app anyway to avoid concurrent modification errors.
        App app = appsApi.getUsersApp().execute().body();

        // Only modify the app if the flag value is different.
        if (app.isParticipantIpLockingEnabled() != participantIpLockingEnabled) {
            app.setParticipantIpLockingEnabled(participantIpLockingEnabled);
            appsApi.updateUsersApp(app).execute();
        }
    }

    private static void test(TestUserHelper.TestUser user, boolean shouldLock) throws Exception {
        // Sign in and extract session ID.
        String signInText = "{\n" +
                "   \"appId\":\"" + user.getAppId() + "\",\n" +
                "   \"email\":\"" + user.getEmail() + "\",\n" +
                "   \"password\":\"" + user.getPassword() + "\"\n" +
                "}";
        HttpResponse signInResponse = Request.Post(hostUrl + "/v3/auth/signIn")
                .setHeader("X-Forwarded-For", "same address, same load balancer")
                .body(new StringEntity(signInText)).execute().returnResponse();
        assertEquals(200, signInResponse.getStatusLine().getStatusCode());

        JsonNode signInBodyNode = JSON_OBJECT_MAPPER.readTree(EntityUtils.toString(signInResponse.getEntity()));
        String sessionId = signInBodyNode.get("sessionToken").textValue();
        assertNotNull(sessionId);

        // Sending a request with the same IP address always works.
        HttpResponse sameResponse = Request.Get(hostUrl + "/v1/activityevents")
                .setHeader("Bridge-Session", sessionId)
                .setHeader("X-Forwarded-For", "same address, same load balancer")
                .execute().returnResponse();
        assertEquals(200, sameResponse.getStatusLine().getStatusCode());

        // Different IP address may get locked.
        HttpResponse differentAddressResponse = Request.Get(hostUrl + "/v1/activityevents")
                .setHeader("Bridge-Session", sessionId)
                .setHeader("X-Forwarded-For", "different address, same load balancer")
                .execute().returnResponse();
        assertEquals(shouldLock ? 401 : 200, differentAddressResponse.getStatusLine().getStatusCode());

        // Request from a different load balancer but the same source IP always works.
        HttpResponse differentLoadBalancerResponse = Request.Get(hostUrl + "/v1/activityevents")
                .setHeader("Bridge-Session", sessionId)
                .setHeader("X-Forwarded-For", "same address, different load balancer")
                .execute().returnResponse();
        assertEquals(200, differentLoadBalancerResponse.getStatusLine().getStatusCode());
    }
}
