package org.sagebionetworks.bridge.sdk.integration;

import static org.apache.http.entity.ContentType.APPLICATION_JSON;
import static org.junit.Assert.assertEquals;
import static org.sagebionetworks.bridge.sdk.integration.Tests.API_SIGNIN;
import static org.sagebionetworks.bridge.util.IntegTestUtils.CONFIG;

import java.io.IOException;
import java.util.Base64;

import com.google.common.collect.ImmutableSet;

import org.apache.http.HttpResponse;
import org.apache.http.client.fluent.Request;
import org.apache.http.util.EntityUtils;
import org.hl7.fhir.dstu3.model.Appointment;
import org.hl7.fhir.dstu3.model.Identifier;
import org.hl7.fhir.dstu3.model.Observation;
import org.hl7.fhir.dstu3.model.ProcedureRequest;
import org.joda.time.LocalDate;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.ClientManager;
import org.sagebionetworks.bridge.rest.RestUtils;
import org.sagebionetworks.bridge.rest.api.ForSuperadminsApi;
import org.sagebionetworks.bridge.rest.api.ParticipantReportsApi;
import org.sagebionetworks.bridge.rest.model.Message;
import org.sagebionetworks.bridge.rest.model.ReportData;
import org.sagebionetworks.bridge.rest.model.ReportDataList;
import org.sagebionetworks.bridge.rest.model.SignIn;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.util.IntegTestUtils;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;

/**
 * These calls use HTTP Basic Authentication, so they're not done through our
 * SDK (which supports our custom header implementation).
 */
public class CRCTest {

    static final String BRIDGE_USER_ID_NS = "https://ws.sagebridge.org/#userId";
    static final String APP_ID = "czi-coronavirus";
    static final LocalDate JAN1 = LocalDate.parse("1970-01-01");
    static final LocalDate JAN2 = LocalDate.parse("1970-01-02");
    static final FhirContext CONTEXT = FhirContext.forDstu3();
    
    TestUser user;
    TestUser adminUser;
    
    String host;
    String credentials;
    
    @Before
    public void beforeMethod() throws IOException {
        adminUser = TestUserHelper.getSignedInAdmin();
        adminUser.getClient(ForSuperadminsApi.class).adminChangeApp(new SignIn().appId(APP_ID)).execute();
        
        user = new TestUserHelper.Builder(CRCTest.class)
                .withAppId(APP_ID)
                .withConsentUser(true)
                .withSubstudyIds(ImmutableSet.of("columbia"))
                .createAndSignInUser();
        
        // These are the external integration credentials for the caller.
        String externalId = IntegTestUtils.CONFIG.get("crc.external.id").trim();
        String password = IntegTestUtils.CONFIG.get("crc.password").trim();
        
        host = new ClientManager.Builder()
            .withConfig(CONFIG)
            .withSignIn(new SignIn().appId(APP_ID).externalId(externalId).password(password))
            .build().getHostUrl();
        credentials = new String(Base64.getEncoder().encode((externalId + ":" + password).getBytes()));
    }
    
    @After
    public void afterMethod() throws Exception {
        if (user != null) {
            user.signOutAndDeleteUser();    
        }
        adminUser = TestUserHelper.getSignedInAdmin();
        adminUser.getClient(ForSuperadminsApi.class).adminChangeApp(API_SIGNIN).execute();
    }
    
    @Test
    public void createAppointment() throws IOException {
        Identifier identifier = new Identifier();
        identifier.setSystem(BRIDGE_USER_ID_NS);
        identifier.setValue(user.getUserId());
        
        Appointment appointment = new Appointment();
        appointment.setId("appointmentId");
        appointment.addIdentifier(identifier);
        
        IParser parser = CONTEXT.newJsonParser();
        String body = parser.encodeResourceToString(appointment);
        
        HttpResponse response = Request.Put(host + "/v1/cuimc/appointments")
            .addHeader("Authorization", "Basic " + credentials)
            .bodyString(body, APPLICATION_JSON)
            .execute()
            .returnResponse();
        
        Message message = RestUtils.GSON.fromJson(EntityUtils.toString(response.getEntity()), Message.class);
        assertEquals("Appointment created.", message.getMessage());
        assertEquals(201, response.getStatusLine().getStatusCode());
        
        response = Request.Put(host + "/v1/cuimc/appointments")
                .addHeader("Authorization", "Basic " + credentials)
                .bodyString(body, APPLICATION_JSON)
                .execute()
                .returnResponse();
        message = RestUtils.GSON.fromJson(EntityUtils.toString(response.getEntity()), Message.class);
        assertEquals("Appointment updated.", message.getMessage());
        assertEquals(200, response.getStatusLine().getStatusCode());
        
        TestUser adminUser = TestUserHelper.getSignedInAdmin();
        adminUser.getClient(ForSuperadminsApi.class).adminChangeApp(new SignIn().appId(APP_ID)).execute();
        ParticipantReportsApi reportsApi = adminUser.getClient(ParticipantReportsApi.class);
        
        ReportDataList list = reportsApi.getUsersParticipantReportRecords(
                user.getUserId(), "appointment", JAN1, JAN2).execute().body();
        ReportData report = list.getItems().get(0);
        
        String json = RestUtils.GSON.toJson(report.getData());
        Appointment retrieved = parser.parseResource(Appointment.class, json);
        assertEquals(user.getUserId(), retrieved.getIdentifier().get(0).getValue());
    }
    
    @Test
    public void createProcedure() throws IOException {
        Identifier identifier = new Identifier();
        identifier.setSystem(BRIDGE_USER_ID_NS);
        identifier.setValue(user.getUserId());
        
        ProcedureRequest procedure = new ProcedureRequest();
        procedure.setId("procedureId");
        procedure.addIdentifier(identifier);
        
        IParser parser = CONTEXT.newJsonParser();
        String body = parser.encodeResourceToString(procedure);
        
        HttpResponse response = Request.Put(host + "/v1/cuimc/procedurerequests")
            .addHeader("Authorization", "Basic " + credentials)
            .bodyString(body, APPLICATION_JSON)
            .execute()
            .returnResponse();
        
        Message message = RestUtils.GSON.fromJson(EntityUtils.toString(response.getEntity()), Message.class);
        assertEquals("ProcedureRequest created.", message.getMessage());
        assertEquals(201, response.getStatusLine().getStatusCode());
        
        response = Request.Put(host + "/v1/cuimc/procedurerequests")
                .addHeader("Authorization", "Basic " + credentials)
                .bodyString(body, APPLICATION_JSON)
                .execute()
                .returnResponse();
        message = RestUtils.GSON.fromJson(EntityUtils.toString(response.getEntity()), Message.class);
        assertEquals("ProcedureRequest updated.", message.getMessage());
        assertEquals(200, response.getStatusLine().getStatusCode());

        TestUser adminUser = TestUserHelper.getSignedInAdmin();
        adminUser.getClient(ForSuperadminsApi.class).adminChangeApp(new SignIn().appId(APP_ID)).execute();
        ParticipantReportsApi reportsApi = adminUser.getClient(ParticipantReportsApi.class);
        
        ReportDataList list = reportsApi.getUsersParticipantReportRecords(
                user.getUserId(), "procedurerequest", JAN1, JAN2).execute().body();
        ReportData report = list.getItems().get(0);
        
        String json = RestUtils.GSON.toJson(report.getData());
        ProcedureRequest retrieved = parser.parseResource(ProcedureRequest.class, json);
        assertEquals(user.getUserId(), retrieved.getIdentifier().get(0).getValue());
    }
    
    @Test
    @Ignore
    public void createObservation() throws IOException {
        Identifier identifier = new Identifier();
        identifier.setSystem(BRIDGE_USER_ID_NS);
        identifier.setValue(user.getUserId());
        
        Observation observation = new Observation();
        observation.setId("observationId");
        observation.addIdentifier(identifier);
        
        IParser parser = CONTEXT.newJsonParser();
        String body = parser.encodeResourceToString(observation);
        
        HttpResponse response = Request.Put(host + "/v1/cuimc/observations")
            .addHeader("Authorization", "Basic " + credentials)
            .bodyString(body, APPLICATION_JSON)
            .execute()
            .returnResponse();
        Message message = RestUtils.GSON.fromJson(EntityUtils.toString(response.getEntity()), Message.class);
        assertEquals("Observation created.", message.getMessage());
        assertEquals(201, response.getStatusLine().getStatusCode());
        
        response = Request.Put(host + "/v1/cuimc/observations")
                .addHeader("Authorization", "Basic " + credentials)
                .bodyString(body, APPLICATION_JSON)
                .execute()
                .returnResponse();
        message = RestUtils.GSON.fromJson(EntityUtils.toString(response.getEntity()), Message.class);
        assertEquals("Observation updated.", message.getMessage());
        assertEquals(200, response.getStatusLine().getStatusCode());

        TestUser adminUser = TestUserHelper.getSignedInAdmin();
        adminUser.getClient(ForSuperadminsApi.class).adminChangeApp(new SignIn().appId(APP_ID)).execute();
        ParticipantReportsApi reportsApi = adminUser.getClient(ParticipantReportsApi.class);
        
        ReportDataList list = reportsApi.getUsersParticipantReportRecords(
                user.getUserId(), "observation", JAN1, JAN2).execute().body();
        ReportData report = list.getItems().get(0);
        
        String json = RestUtils.GSON.toJson(report.getData());
        Observation retrieved = parser.parseResource(Observation.class, json);
        assertEquals(user.getUserId(), retrieved.getIdentifier().get(0).getValue());
    }
    
}
