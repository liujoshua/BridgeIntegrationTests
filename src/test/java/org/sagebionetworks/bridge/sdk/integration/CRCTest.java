package org.sagebionetworks.bridge.sdk.integration;

import static org.apache.http.entity.ContentType.APPLICATION_JSON;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Base64;
import java.util.List;

import com.google.common.collect.ImmutableList;
import com.google.gson.JsonElement;

import org.apache.http.HttpResponse;
import org.apache.http.client.fluent.Request;
import org.apache.http.util.EntityUtils;
import org.hl7.fhir.dstu3.model.Appointment;
import org.hl7.fhir.dstu3.model.Identifier;
import org.hl7.fhir.dstu3.model.Observation;
import org.hl7.fhir.dstu3.model.ProcedureRequest;
import org.joda.time.DateTime;
import org.joda.time.LocalDate;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.RestUtils;
import org.sagebionetworks.bridge.rest.api.AppsApi;
import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.api.InternalApi;
import org.sagebionetworks.bridge.rest.api.ParticipantReportsApi;
import org.sagebionetworks.bridge.rest.api.ParticipantsApi;
import org.sagebionetworks.bridge.rest.model.AccountSummaryList;
import org.sagebionetworks.bridge.rest.model.AccountSummarySearch;
import org.sagebionetworks.bridge.rest.model.App;
import org.sagebionetworks.bridge.rest.model.HealthDataRecord;
import org.sagebionetworks.bridge.rest.model.HealthDataRecordList;
import org.sagebionetworks.bridge.rest.model.Message;
import org.sagebionetworks.bridge.rest.model.ReportData;
import org.sagebionetworks.bridge.rest.model.ReportDataList;
import org.sagebionetworks.bridge.rest.model.SignUp;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;

/**
 * These calls use HTTP Basic Authentication, so they're not done through our
 * SDK (which supports our custom header implementation).
 */
public class CRCTest {

    static final String BRIDGE_USER_ID_NS = "https://ws.sagebridge.org/#userId";
    static final LocalDate JAN1 = LocalDate.parse("1970-01-01");
    static final LocalDate JAN2 = LocalDate.parse("1970-01-02");
    static final FhirContext CONTEXT = FhirContext.forDstu3();
    static final String TEST_EMAIL = "bridge-testing+crc@sagebase.org";
    static final List<String> WORKFLOW_TAGS = ImmutableList.of("enrolled", "selected", 
            "declined", "tests_requested", "tests_scheduled", "tests_collected", "tests_available");
    
    static TestUser user;
    static TestUser adminUser;
    
    static String host;
    static String credentials;
    
    @Before
    public void beforeMethod() throws IOException {
        adminUser = TestUserHelper.getSignedInAdmin();
        AppsApi appsApi = adminUser.getClient(AppsApi.class);
        
        AccountSummarySearch search = new AccountSummarySearch()
                .emailFilter(TEST_EMAIL);
        AccountSummaryList list = adminUser.getClient(ParticipantsApi.class).searchAccountSummaries(search).execute().body();
        if (!list.getItems().isEmpty()) {
            String userId = list.getItems().get(0).getId();
            adminUser.getClient(ForAdminsApi.class).deleteUser(userId).execute();
        }
        
        App app = appsApi.getUsersApp().execute().body();
        if (!app.getDataGroups().containsAll(WORKFLOW_TAGS) ||
            !app.getUserProfileAttributes().contains("state_change_timestamp")) {
            
            app.getDataGroups().addAll(WORKFLOW_TAGS);
            app.getUserProfileAttributes().add("state_change_timestamp");
            appsApi.updateUsersApp(app).execute();
        }
        // Create an account that is a system account and the target user account
        String password = Tests.randomIdentifier(CRCTest.class);
        user = new TestUserHelper.Builder(CRCTest.class)
                .withConsentUser(true)
                .withSetPassword(false)
                .withSignUp(new SignUp().email(TEST_EMAIL).password(password).addDataGroupsItem("test_user"))
                .createUser();
        
        host = adminUser.getClientManager().getHostUrl();
        credentials = new String(Base64.getEncoder().encode((TEST_EMAIL + ":" + password).getBytes()));
    }
    
    @After
    public void afterMethod() throws Exception {
        if (user != null) {
            user.signOutAndDeleteUser();
        }
    }

    @Test
    public void orderLabs() throws IOException {
        StudyParticipant participant = adminUser.getClient(ParticipantsApi.class)
                .getParticipantById(user.getUserId(), false).execute().body();
        String healthCode = participant.getHealthCode();
        
        HttpResponse response = Request.Post(host + "/v1/cuimc/participants/healthcode:" + healthCode + "/laborders")
                .addHeader("Authorization", "Basic " + credentials)
                .execute()
                .returnResponse();
        
        Message message = RestUtils.GSON.fromJson(EntityUtils.toString(response.getEntity()), Message.class);
        assertEquals("Participant updated.", message.getMessage());
        assertEquals(200, response.getStatusLine().getStatusCode());
        
        participant = adminUser.getClient(ParticipantsApi.class)
                .getParticipantById(user.getUserId(), false).execute().body();
        assertTrue(participant.getDataGroups().containsAll(ImmutableList.of("test_user", "tests_requested")));
    }
    
    @Test
    public void createAppointment() throws Exception {
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
        
        ParticipantReportsApi reportsApi = adminUser.getClient(ParticipantReportsApi.class);
        
        ReportDataList list = reportsApi.getUsersParticipantReportRecords(
                user.getUserId(), "appointment", JAN1, JAN2).execute().body();
        ReportData report = list.getItems().get(0);
        
        String json = RestUtils.GSON.toJson(report.getData());
        Appointment retrieved = parser.parseResource(Appointment.class, json);
        assertEquals(user.getUserId(), retrieved.getIdentifier().get(0).getValue());
        
        StudyParticipant participant = adminUser.getClient(ParticipantsApi.class)
                .getParticipantById(user.getUserId(), false).execute().body();
        assertTrue(participant.getDataGroups().contains("tests_scheduled"));
        
        Thread.sleep(2000);
        
        HealthDataRecordList records = user.getClient(InternalApi.class)
                .getHealthDataByCreatedOn(DateTime.now().minusHours(1), DateTime.now().plusHours(1))
                .execute().body();
        verifyHealthDataRecords(records, "appointment");
    }
    
    private void verifyHealthDataRecords(HealthDataRecordList records, String typeName) {
        // There will be two records and they will both be appointments
        assertEquals(records.getItems().size(), 2);
        for (HealthDataRecord record : records.getItems()) {
            JsonElement el = RestUtils.toJSON(record.getUserMetadata());
            String type = el.getAsJsonObject().get("type").getAsString();
            assertEquals(typeName, type);
        }
    }
    
    @Test
    public void createProcedureRequest() throws Exception {
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

        ParticipantReportsApi reportsApi = adminUser.getClient(ParticipantReportsApi.class);
        
        ReportDataList list = reportsApi.getUsersParticipantReportRecords(
                user.getUserId(), "procedurerequest", JAN1, JAN2).execute().body();
        ReportData report = list.getItems().get(0);
        
        String json = RestUtils.GSON.toJson(report.getData());
        ProcedureRequest retrieved = parser.parseResource(ProcedureRequest.class, json);
        assertEquals(user.getUserId(), retrieved.getIdentifier().get(0).getValue());
        
        StudyParticipant participant = adminUser.getClient(ParticipantsApi.class)
                .getParticipantById(user.getUserId(), false).execute().body();
        assertTrue(participant.getDataGroups().contains("tests_collected"));
        
        Thread.sleep(2000);

        HealthDataRecordList records = user.getClient(InternalApi.class)
                .getHealthDataByCreatedOn(DateTime.now().minusHours(1), DateTime.now().plusHours(1))
                .execute().body();
        verifyHealthDataRecords(records, "procedurerequest");
    }
    
    @Test
    public void createObservation() throws Exception {
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

        ParticipantReportsApi reportsApi = adminUser.getClient(ParticipantReportsApi.class);
        
        ReportDataList list = reportsApi.getUsersParticipantReportRecords(
                user.getUserId(), "observation", JAN1, JAN2).execute().body();
        ReportData report = list.getItems().get(0);
        
        String json = RestUtils.GSON.toJson(report.getData());
        Observation retrieved = parser.parseResource(Observation.class, json);
        assertEquals(user.getUserId(), retrieved.getIdentifier().get(0).getValue());
        
        StudyParticipant participant = adminUser.getClient(ParticipantsApi.class)
                .getParticipantById(user.getUserId(), false).execute().body();
        assertTrue(participant.getDataGroups().contains("tests_available"));
        
        Thread.sleep(2000);
        
        HealthDataRecordList records = user.getClient(InternalApi.class)
                .getHealthDataByCreatedOn(DateTime.now().minusHours(1), DateTime.now().plusHours(1))
                .execute().body();
        verifyHealthDataRecords(records, "observation");
    }
}
