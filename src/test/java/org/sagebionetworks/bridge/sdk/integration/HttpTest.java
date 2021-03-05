package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.apache.http.HttpResponse;
import org.apache.http.client.fluent.Request;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.model.Environment;
import org.sagebionetworks.bridge.user.TestUserHelper;

/**
 * Tests of headers, CORS, and other configuration at the HTTP level.
 */
public class HttpTest {
    
    public static final int TIMEOUT = 10000;
    public static final String ACCESS_CONTROL_ALLOW_HEADERS = "Access-Control-Allow-Headers";
    public static final String ACCESS_CONTROL_ALLOW_METHODS = "Access-Control-Allow-Methods";
    public static final String ACCESS_CONTROL_ALLOW_ORIGIN = "Access-Control-Allow-Origin";
    public static final String ACCESS_CONTROL_REQUEST_HEADERS = "Access-Control-Request-Headers";
    public static final String ACCESS_CONTROL_REQUEST_METHOD = "Access-Control-Request-Method";
    public static final String ORIGIN = "Origin";
    public static final String REFERER = "Referer";
    public static final String X_FORWARDED_PROTO = "X-Forwarded-Proto";
    
    private String testBaseUrl;
    
    @Before
    public void before() {
        this.testBaseUrl = TestUserHelper.getSignedInAdmin().getClientManager().getHostUrl();
    }

    @Test
    public void testPreflight() throws Exception {
        HttpResponse response = Request.Options(testBaseUrl+"/v1/apps?summary=true")
            .setHeader(ACCESS_CONTROL_REQUEST_HEADERS, "accept, content-type")
            .setHeader(ACCESS_CONTROL_REQUEST_METHOD, "POST")
            .setHeader(ORIGIN, "https://some.remote.server.org")
            .connectTimeout(TIMEOUT).execute().returnResponse();
        assertEquals(200, response.getStatusLine().getStatusCode());
        
        assertEquals("Should echo back the origin",
                "*", response.getFirstHeader(ACCESS_CONTROL_ALLOW_ORIGIN).getValue());
        assertEquals("Should echo back the access-control-allow-methods",
                "POST", response.getFirstHeader(ACCESS_CONTROL_ALLOW_METHODS).getValue());
        assertEquals("Should echo back the access-control-allow-headers",
                "accept, content-type", response.getFirstHeader(ACCESS_CONTROL_ALLOW_HEADERS).getValue());
    }

    @Test
    public void testCors() throws Exception {
        HttpResponse response = Request.Get(testBaseUrl+"/")
                .setHeader(ORIGIN, "https://some.remote.server.org")
                .setHeader(REFERER, "https://some.remote.server.org")
                .connectTimeout(TIMEOUT)
                .execute().returnResponse();
        assertEquals(200, response.getStatusLine().getStatusCode());
    }

    // HTTPS Redirect is temporarily disabled for dev and staging for the AWS Migration.
    @Ignore
    @Test
    public void testHttpRedirect() throws Exception {
        // This test only makes sense on servers supporting https redirection, and that's not
        // localhost. For all other environments, run this test.
        if (TestUserHelper.getSignedInAdmin().getClientManager().getConfig().getEnvironment() != Environment.LOCAL) {
            
            // You can't use the fluent API because it doesn't allow you to observe redirects.
            CloseableHttpClient httpclient = HttpClients.custom().disableRedirectHandling().build();
            
            HttpGet httpGet = new HttpGet(testBaseUrl.replace("https","http")+"/");
            CloseableHttpResponse response = httpclient.execute(httpGet);
            try {
                assertEquals(301, response.getStatusLine().getStatusCode());
                assertNotNull(response.getFirstHeader("location").getValue());
                assertTrue(response.getFirstHeader("location").getValue().startsWith("https://"));
            } finally {
                response.close();
            }
        }
    }
}
