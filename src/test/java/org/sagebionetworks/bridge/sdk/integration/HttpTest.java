package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.fluent.Request;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.junit.Before;
import org.junit.Test;

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
        this.testBaseUrl = "https://webservices.sagebridge.org"; //TestUserHelper.getSignedInAdmin().getClientManager().getHostUrl();
    }
    
    @Test
    public void testPreflight() throws Exception {
        HttpResponse response = Request.Get(testBaseUrl+"/v3/studies?summary=true")
            .setHeader(ACCESS_CONTROL_REQUEST_HEADERS, "accept, content-type")
            .setHeader(ACCESS_CONTROL_REQUEST_METHOD, "POST")
            .setHeader(ORIGIN, "https://some.remote.server.org")
            .connectTimeout(TIMEOUT).execute().returnResponse();
        assertEquals(200, response.getStatusLine().getStatusCode());
        
        for (Header header : response.getAllHeaders()) {
            System.out.println(header.getName() + ": " + header.getValue());
        }
        
        assertEquals("Should echo back the origin",
                "https://some.remote.server.org", response.getFirstHeader(ACCESS_CONTROL_ALLOW_ORIGIN).getValue());
        assertEquals("Should echo back the access-control-allow-methods",
                "POST", response.getFirstHeader(ACCESS_CONTROL_ALLOW_METHODS).getValue());
        assertTrue("Should echo back the access-control-allow-request-headers",
                response.getFirstHeader(ACCESS_CONTROL_ALLOW_HEADERS).getValue().toLowerCase().contains("accept"));
        assertTrue("Should echo back the access-control-allow-request-headers",
                response.getFirstHeader(ACCESS_CONTROL_ALLOW_HEADERS).getValue().toLowerCase().contains("content-type"));
        /*
        TestUtils.runningTestServerWithSpring(() -> {
            WSRequest request = WS.url(TEST_BASE_URL + "/anything")
                    .setHeader(ACCESS_CONTROL_REQUEST_HEADERS, "accept, content-type")
                    .setHeader(ACCESS_CONTROL_REQUEST_METHOD, "POST")
                    .setHeader(ORIGIN, "https://some.remote.server.org");
            WSResponse response = request.options().get(TIMEOUT);
            assertEquals(200, response.getStatus());
            assertEquals("Should echo back the origin",
                    "https://some.remote.server.org", response.getHeader(ACCESS_CONTROL_ALLOW_ORIGIN));
            assertEquals("Should echo back the access-control-allow-methods",
                    "POST", response.getHeader(ACCESS_CONTROL_ALLOW_METHODS));
            assertTrue("Should echo back the access-control-allow-request-headers",
                    response.getHeader(ACCESS_CONTROL_ALLOW_HEADERS).toLowerCase().contains("accept"));
            assertTrue("Should echo back the access-control-allow-request-headers",
                    response.getHeader(ACCESS_CONTROL_ALLOW_HEADERS).toLowerCase().contains("content-type"));
        });
        */
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

    @Test
    public void testHttpRedirect() throws Exception {
        // You can't use the fluent API because it doesn't allow you to observe redirects.
        CloseableHttpClient httpclient = HttpClients.createMinimal();
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
