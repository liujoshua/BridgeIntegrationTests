package org.sagebionetworks.bridge.sdk.integration;

import static org.apache.http.entity.ContentType.APPLICATION_JSON;
import static org.hl7.fhir.dstu3.model.Appointment.AppointmentStatus.BOOKED;
import static org.hl7.fhir.dstu3.model.Appointment.AppointmentStatus.CANCELLED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.gson.JsonElement;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.fluent.Request;
import org.apache.http.util.EntityUtils;
import org.hl7.fhir.dstu3.model.Appointment;
import org.hl7.fhir.dstu3.model.Appointment.AppointmentParticipantComponent;
import org.hl7.fhir.dstu3.model.Appointment.AppointmentStatus;
import org.hl7.fhir.dstu3.model.CodeableConcept;
import org.hl7.fhir.dstu3.model.Coding;
import org.hl7.fhir.dstu3.model.Extension;
import org.hl7.fhir.dstu3.model.Identifier;
import org.hl7.fhir.dstu3.model.Observation;
import org.hl7.fhir.dstu3.model.ProcedureRequest;
import org.hl7.fhir.dstu3.model.Range;
import org.hl7.fhir.dstu3.model.Reference;
import org.hl7.fhir.dstu3.model.StringType;
import org.joda.time.DateTime;
import org.joda.time.LocalDate;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
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

    static final LocalDate JAN1 = LocalDate.parse("1970-01-01");
    static final LocalDate JAN2 = LocalDate.parse("1970-01-02");
    static final FhirContext CONTEXT = FhirContext.forDstu3();
    static final String TEST_EMAIL = "bridge-testing+crc@sagebase.org";
    static final List<String> WORKFLOW_TAGS = ImmutableList.of("enrolled", "selected",
            "declined", "tests_requested", "tests_scheduled", "tests_collected", "tests_available",
            "ship_test_requested");
    static final List<String> USER_PROFILE_ATTRIBUTES = ImmutableList.of("state_change_timestamp", "address1",
            "address2", "city", "state", "zip_code", "dob", "gender", "home_phone");
    static final String USER_ID_VALUE_NS = "https://ws.sagebridge.org/#userId";

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
            !app.getUserProfileAttributes().containsAll(USER_PROFILE_ATTRIBUTES) ||
            !app.isHealthCodeExportEnabled()) {
            
            app.setHealthCodeExportEnabled(true);
            app.getDataGroups().addAll(WORKFLOW_TAGS);
            app.getUserProfileAttributes().addAll(USER_PROFILE_ATTRIBUTES);
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
    public void requestShipmentAsParticipant() throws IOException {
        user.signInAgain();

        setupShippingInfo();

        HttpResponse response = Request.Post(host + "/v1/cuimc/participants/self/labshipments/request")
                .addHeader("Bridge-Session", user.getSession().getSessionToken())
                .execute()
                .returnResponse();

        assertEquals(HttpStatus.SC_ACCEPTED, response.getStatusLine().getStatusCode());

        ParticipantReportsApi reportsApi = adminUser.getClient(ParticipantReportsApi.class);

        ReportDataList list = reportsApi.getUsersParticipantReportRecords(
                user.getUserId(), "shipmentrequest", JAN1, JAN2).execute().body();
        ReportData report = list.getItems().get(0);

        JsonElement jsonElement = RestUtils.GSON.toJsonTree(report.getData());
        assertTrue(jsonElement.getAsJsonObject().get("orderNumber").getAsString().startsWith(user.getUserId()));
    }

    @Test
    public void requestShipmentForHealthCode() throws IOException {
        setupShippingInfo();

        StudyParticipant participant = adminUser.getClient(ParticipantsApi.class)
                .getParticipantById(user.getUserId(), false).execute().body();
        String healthCode = participant.getHealthCode();

        HttpResponse response = Request.Post(
                host + "/v1/cuimc/participants/healthcode:" + healthCode + "/labshipments/request")
                .addHeader("Authorization", "Basic " + credentials)
                .execute()
                .returnResponse();

        assertEquals(HttpStatus.SC_ACCEPTED, response.getStatusLine().getStatusCode());

        ParticipantReportsApi reportsApi = adminUser.getClient(ParticipantReportsApi.class);

        ReportDataList list = reportsApi.getUsersParticipantReportRecords(
                user.getUserId(), "shipmentrequest", JAN1, JAN2).execute().body();
        ReportData report = list.getItems().get(0);

        JsonElement jsonElement = RestUtils.GSON.toJsonTree(report.getData());
        assertTrue(jsonElement.getAsJsonObject().get("orderNumber").getAsString().startsWith(user.getUserId()));
    }

    private void setupShippingInfo() throws IOException {
        StudyParticipant participant = user.getClient(ParticipantsApi.class).getUsersParticipantRecord(false)
                .execute().body();
        Map<String, String> homeTestInfo = new ImmutableMap.Builder<String, String>()
                .put("address1", "123 Sesame Street")
                .put("address2", "Apt. 6")
                .put("city", "Seattle")
                .put("dob", "1980-08-10")
                .put("gender", "female")
                .put("state", "WA")
                .put("zip_code", "10001")
                .put("home_phone", "206.547.2600")
                .build();

        Map<String, String> attributes = participant.getAttributes();
        attributes.putAll(homeTestInfo);

        StudyParticipant updateParticipant = new StudyParticipant();
        updateParticipant.setFirstName("Test");
        updateParticipant.setLastName("User");
        updateParticipant.setAttributes(attributes);

        user.getClient(ParticipantsApi.class).updateUsersParticipantRecord(updateParticipant).execute();
    }
    
    @Ignore("Waiting for integration workflow to be finalized")
    @Test
    public void checkShipmentStatus() throws IOException {
        HttpResponse response = Request.Get(host + "/v1/cuimc/labshipments/fzOJmVi8-h-IGRmnGM2RAZQz2021-01-19/status")
                .addHeader("Authorization", "Basic " + credentials)
                .execute().returnResponse();
    }
    
    @Ignore("Waiting for integration workflow to be finalized")
    @Test
    public void shippingConfirmations() throws IOException {
        HttpResponse response = Request.Get(host +
        "/v1/cuimc/participants/labshipments/confirmations?startDate=2021-01-01&endDate=2021-01-20")
                .addHeader("Authorization", "Basic " + credentials)
                .execute().returnResponse();

        RestUtils.GSON.fromJson(EntityUtils.toString(response.getEntity()), Message.class);
        
        assertEquals(200, response.getStatusLine().getStatusCode());
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
        assertTrue(participant.getDataGroups().containsAll(ImmutableList.of("test_user", "selected")));
    }
    
    @Test
    public void createAppointment() throws Exception {
        Appointment appointment = new Appointment();
        appointment.setId("appointmentId");
        appointment.setStatus(BOOKED);
        
        AppointmentParticipantComponent idComp = new AppointmentParticipantComponent();
        Identifier id = new Identifier();
        id.setSystem(USER_ID_VALUE_NS);
        id.setValue(user.getUserId());
        Reference ref = new Reference();
        ref.setIdentifier(id);
        idComp.setActor(ref);
        appointment.addParticipant(idComp);
        
        // This stanza will trigger Bridge server to retrieve the location from our external partner.
        // Disabled here so our tests don't depend on that API functioning. But useful for 
        // manual integration tests.
//        AppointmentParticipantComponent locComp = new AppointmentParticipantComponent();
//        Reference locRef = new Reference();
//        locRef.setReference("Location/CovidRecoveryChony");
//        locRef.setDisplay("Covid Recovery CHONY");
//        locComp.setActor(locRef);
//        appointment.addParticipant(locComp);
        
        IParser parser = CONTEXT.newJsonParser();
        String body = parser.encodeResourceToString(appointment);
        
        HttpResponse response = Request.Put(host + "/v1/cuimc/appointments")
            .addHeader("Authorization", "Basic " + credentials)
            .bodyString(body, APPLICATION_JSON)
            .execute()
            .returnResponse();
        
        Message message = RestUtils.GSON.fromJson(EntityUtils.toString(response.getEntity()), Message.class);
        assertEquals("Appointment created (status = booked).", message.getMessage());
        assertEquals(201, response.getStatusLine().getStatusCode());
        
        response = Request.Put(host + "/v1/cuimc/appointments")
                .addHeader("Authorization", "Basic " + credentials)
                .bodyString(body, APPLICATION_JSON)
                .execute()
                .returnResponse();
        message = RestUtils.GSON.fromJson(EntityUtils.toString(response.getEntity()), Message.class);
        assertEquals("Appointment updated (status = booked).", message.getMessage());
        assertEquals(200, response.getStatusLine().getStatusCode());
        
        ParticipantReportsApi reportsApi = adminUser.getClient(ParticipantReportsApi.class);
        
        ReportDataList list = reportsApi.getUsersParticipantReportRecords(
                user.getUserId(), "appointment", JAN1, JAN2).execute().body();
        ReportData report = list.getItems().get(0);
        
        String json = RestUtils.GSON.toJson(report.getData());
        Appointment retrieved = parser.parseResource(Appointment.class, json);
        
        assertEquals(user.getUserId(), retrieved.getParticipant()
                .get(0).getActor().getIdentifier().getValue());
        
        StudyParticipant participant = adminUser.getClient(ParticipantsApi.class)
                .getParticipantById(user.getUserId(), false).execute().body();
        assertTrue(participant.getDataGroups().contains("tests_scheduled"));
        verifyHealthDataRecords("appointment");
        
        // Now let's cancel
        appointment.setStatus(CANCELLED);
        body = parser.encodeResourceToString(appointment);
        
        response = Request.Put(host + "/v1/cuimc/appointments")
            .addHeader("Authorization", "Basic " + credentials)
            .bodyString(body, APPLICATION_JSON)
            .execute()
            .returnResponse();
        
        message = RestUtils.GSON.fromJson(EntityUtils.toString(response.getEntity()), Message.class);
        assertEquals("Appointment updated (status = cancelled).", message.getMessage());
        assertEquals(200, response.getStatusLine().getStatusCode());
        
        participant = adminUser.getClient(ParticipantsApi.class)
                .getParticipantById(user.getUserId(), false).execute().body();
        assertTrue(participant.getDataGroups().contains("tests_cancelled"));
        
        // tests were entered in error
        appointment.setStatus(AppointmentStatus.ENTEREDINERROR);
        body = parser.encodeResourceToString(appointment);
        
        response = Request.Put(host + "/v1/cuimc/appointments")
                .addHeader("Authorization", "Basic " + credentials)
                .bodyString(body, APPLICATION_JSON)
                .execute()
                .returnResponse();
        message = RestUtils.GSON.fromJson(EntityUtils.toString(response.getEntity()), Message.class);
        assertEquals("Appointment deleted.", message.getMessage());
        assertEquals(200, response.getStatusLine().getStatusCode());
        
        participant = adminUser.getClient(ParticipantsApi.class)
                .getParticipantById(user.getUserId(), false).execute().body();
        assertTrue(participant.getDataGroups().contains("selected"));
        
        // report doesn't exist
        list = reportsApi.getUsersParticipantReportRecords(
                user.getUserId(), "appointment", JAN1, JAN2).execute().body();
        assertTrue(list.getItems().isEmpty());
    }
    
    private void verifyHealthDataRecords(String typeName) {
        HealthDataRecordList records = Tests.retryHelper(() -> user.getClient(InternalApi.class)
                        .getHealthDataByCreatedOn(DateTime.now().minusHours(1), DateTime.now().plusHours(1))
                        .execute().body(),
                l -> l.getItems().size() == 2);
        
        // There will be two records and they will both be appointments
        assertEquals(2, records.getItems().size());
        for (HealthDataRecord record : records.getItems()) {
            JsonElement el = RestUtils.toJSON(record.getUserMetadata());
            String type = el.getAsJsonObject().get("type").getAsString();
            assertEquals(typeName, type);
        }
    }
    
    @Test
    public void createProcedureRequest() throws Exception {
        ProcedureRequest procedure = new ProcedureRequest();
        procedure.setId("procedureId");
        
        Identifier id = new Identifier();
        id.setSystem(USER_ID_VALUE_NS);
        id.setValue(user.getUserId());
        Reference ref = new Reference();
        ref.setIdentifier(id);
        procedure.setSubject(ref);
        
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
        
        assertEquals(user.getUserId(), retrieved.getSubject().getIdentifier().getValue());
        
        StudyParticipant participant = adminUser.getClient(ParticipantsApi.class)
                .getParticipantById(user.getUserId(), false).execute().body();
        assertTrue(participant.getDataGroups().contains("tests_collected"));
        
        verifyHealthDataRecords("procedurerequest");
    }
    
    private Observation makeObservation(String code, String result) {
        Observation observation = new Observation();
        observation.setId("observationId");
        
        Coding coding = new Coding().setCode(code);
        CodeableConcept codeableConcept = new CodeableConcept().addCoding(coding);
        observation.setCode(codeableConcept);
        
        Extension extension = new Extension();
        extension.setUrl("some-value");
        extension.setValue(new StringType(result));
        Range range = new Range();
        range.addExtension(extension);
        observation.setValue(range);
        
        Identifier id = new Identifier();
        id.setSystem(USER_ID_VALUE_NS);
        id.setValue(user.getUserId());
        Reference ref = new Reference();
        ref.setIdentifier(id);
        observation.setSubject(ref);
        return observation;
    }
    
    
    @Test
    public void createObservation() throws Exception {
        Observation observation = makeObservation("484670513", "Positive");
        
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
        assertEquals(user.getUserId(), retrieved.getSubject().getIdentifier().getValue());
        
        StudyParticipant participant = adminUser.getClient(ParticipantsApi.class)
                .getParticipantById(user.getUserId(), false).execute().body();
        assertTrue(participant.getDataGroups().contains("tests_available"));
        
        verifyHealthDataRecords("observation");
    }
    
    @Test
    public void createUnknownObservationType() throws Exception {
        Observation observation = makeObservation("111110111", "Positive");
        
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
        assertEquals(user.getUserId(), retrieved.getSubject().getIdentifier().getValue());
        
        StudyParticipant participant = adminUser.getClient(ParticipantsApi.class)
                .getParticipantById(user.getUserId(), false).execute().body();
        assertTrue(participant.getDataGroups().contains("tests_available_type_unknown"));
        
        verifyHealthDataRecords("observation");
    }

}
