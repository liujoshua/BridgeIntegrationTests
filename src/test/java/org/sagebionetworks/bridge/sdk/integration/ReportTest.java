package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.joda.time.LocalDate;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.sdk.ClientProvider;
import org.sagebionetworks.bridge.sdk.ReportClient;
import org.sagebionetworks.bridge.sdk.Roles;
import org.sagebionetworks.bridge.sdk.StudyClient;
import org.sagebionetworks.bridge.sdk.exceptions.BadRequestException;
import org.sagebionetworks.bridge.sdk.integration.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.sdk.models.DateRangeResourceList;
import org.sagebionetworks.bridge.sdk.models.ReportTypeResourceList;
import org.sagebionetworks.bridge.sdk.models.reports.ReportData;
import org.sagebionetworks.bridge.sdk.models.reports.ReportIndex;
import org.sagebionetworks.bridge.sdk.models.reports.ReportType;
import org.sagebionetworks.bridge.sdk.models.studies.Study;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

// Don't run in production as right now, it's leaving a participant index every time the test is run.
public class ReportTest {
    
    private static final LocalDate SEARCH_END_DATE = LocalDate.parse("2016-02-20");

    private static final LocalDate SEARCH_START_DATE = LocalDate.parse("2016-02-01");
    
    private static LocalDate TIME1 = LocalDate.parse("2016-02-02");
    private static LocalDate TIME2 = LocalDate.parse("2016-02-03");
    private static LocalDate TIME3 = LocalDate.parse("2016-02-04");
    
    private static JsonNode DATA1 = JsonNodeFactory.instance.objectNode();
    private static JsonNode DATA2 = JsonNodeFactory.instance.objectNode();
    private static JsonNode DATA3 = JsonNodeFactory.instance.objectNode();
    
    private static ReportData REPORT1 = new ReportData(TIME1, DATA1);
    private static ReportData REPORT2 = new ReportData(TIME2, DATA2);
    private static ReportData REPORT3 = new ReportData(TIME3, DATA3);
    
    private TestUser user;
    
    private String reportId;
    
    @Before
    public void before() {
        ClientProvider.addLanguage("en");
        this.user = TestUserHelper.createAndSignInUser(ReportTest.class, true);
        this.reportId = Tests.randomIdentifier(ReportTest.class);
    }

    @After
    public void after() {
        if (user != null) {
            user.signOutAndDeleteUser();
        }
    }
    
    @Test
    public void developerCanCrudParticipantReport() {
        TestUser developer = TestUserHelper.createAndSignInUser(ReportTest.class, true, Roles.DEVELOPER);
        try {
            String userId = user.getSession().getStudyParticipant().getId();
            ReportClient devReportClient = developer.getSession().getReportClient();
            
            devReportClient.saveParticipantReportByUserId(userId, reportId, REPORT1);
            devReportClient.saveParticipantReportByUserId(userId, reportId, REPORT2);
            devReportClient.saveParticipantReportByUserId(userId, reportId, REPORT3);
            
            ReportClient userReportClient = user.getSession().getReportClient();
            DateRangeResourceList<ReportData> results = userReportClient.getParticipantReport(reportId,
                    SEARCH_START_DATE, SEARCH_END_DATE);
            assertEquals(3, results.getTotal());
            assertEquals(SEARCH_START_DATE, results.getStartDate());
            assertEquals(SEARCH_END_DATE, results.getEndDate());
            
            // This search is out of range, and should return no results.
            results = userReportClient
                    .getParticipantReport(reportId, SEARCH_START_DATE.plusDays(30), SEARCH_END_DATE.plusDays(30));
            assertEquals(0, results.getTotal());
            assertEquals(0, results.getItems().size());
            
            // We should see indices for this participant report
            ReportTypeResourceList<ReportIndex> indices = devReportClient.getReportIndices(ReportType.PARTICIPANT);
            assertTrue(containsThisIdentifier(indices, reportId));
            assertEquals(ReportType.PARTICIPANT, indices.getReportType());
            
            // but not if we ask for study reports
            indices = devReportClient.getReportIndices(ReportType.STUDY);
            assertFalse(containsThisIdentifier(indices, reportId));
            assertEquals(ReportType.STUDY, indices.getReportType());
            
            // delete
            devReportClient.deleteParticipantReport(userId, reportId);
            results = userReportClient.getParticipantReport(reportId, SEARCH_START_DATE, SEARCH_END_DATE);
            assertFalse(containsThisIdentifier(indices, reportId));
        } finally {
            developer.signOutAndDeleteUser();
        }
    }

    @Test
    public void workerCanCrudParticipantReport() {
        TestUser admin = TestUserHelper.getSignedInAdmin();
        StudyClient adminStudyClient = admin.getSession().getStudyClient();
        Study study = adminStudyClient.getStudy("api");
        study.setHealthCodeExportEnabled(true);
        adminStudyClient.updateStudy(study);
        
        // Make this worker a researcher solely for the purpose of getting the healthCode needed to user the worker
        // API
        TestUser worker = TestUserHelper.createAndSignInUser(ReportTest.class, false, Roles.WORKER, Roles.RESEARCHER);
        
        TestUser developer = TestUserHelper.createAndSignInUser(ReportTest.class, false, Roles.DEVELOPER);
        
        String healthCode = worker.getSession().getParticipantClient()
                .getStudyParticipant(user.getSession().getStudyParticipant().getId()).getHealthCode();
        try {
            String userId = user.getSession().getStudyParticipant().getId();
            
            ReportClient workerReportClient = worker.getSession().getReportClient();
            
            workerReportClient.saveParticipantReportByHealthCode(healthCode, reportId, REPORT1);
            workerReportClient.saveParticipantReportByHealthCode(healthCode, reportId, REPORT2);
            workerReportClient.saveParticipantReportByHealthCode(healthCode, reportId, REPORT3);
            
            ReportClient userReportClient = user.getSession().getReportClient();
            DateRangeResourceList<ReportData> results = userReportClient.getParticipantReport(reportId,
                    SEARCH_START_DATE, SEARCH_END_DATE);
            assertEquals(3, results.getTotal());
            assertEquals(SEARCH_START_DATE, results.getStartDate());
            assertEquals(SEARCH_END_DATE, results.getEndDate());
            
            // This search is out of range, and should return no results.
            results = userReportClient
                    .getParticipantReport(reportId, SEARCH_START_DATE.plusDays(30), SEARCH_END_DATE.plusDays(30));
            assertEquals(0, results.getTotal());
            assertEquals(0, results.getItems().size());
            
            // delete. Must be done by developer
            developer.getSession().getReportClient().deleteParticipantReport(userId, reportId);
            
            results = userReportClient.getParticipantReport(reportId, SEARCH_START_DATE, SEARCH_END_DATE);
            assertEquals(0, results.getTotal());
            assertEquals(0, results.getItems().size());
        } finally {
            study.setHealthCodeExportEnabled(false);
            adminStudyClient.updateStudy(study);

            worker.signOutAndDeleteUser();
            developer.signOutAndDeleteUser();
        }
    }

    @Test
    public void canCrudStudyReport() {
        TestUser developer = TestUserHelper.createAndSignInUser(ReportTest.class, true, Roles.DEVELOPER);
        try {
            ReportClient devReportClient = developer.getSession().getReportClient();
            devReportClient.saveStudyReport(reportId, REPORT1);
            devReportClient.saveStudyReport(reportId, REPORT2);
            devReportClient.saveStudyReport(reportId, REPORT3);
            
            DateRangeResourceList<ReportData> results = devReportClient.getStudyReport(reportId, SEARCH_START_DATE,
                    SEARCH_END_DATE);
            assertEquals(3, results.getTotal());
            assertEquals(SEARCH_START_DATE, results.getStartDate());
            assertEquals(SEARCH_END_DATE, results.getEndDate());

            // This search is out of range, and should return no results.
            results = devReportClient
                    .getParticipantReport(reportId, SEARCH_START_DATE.minusDays(30), SEARCH_END_DATE.minusDays(30));
            assertEquals(0, results.getTotal());
            assertEquals(0, results.getItems().size());
            
            // We should see indices for this study report
            ReportTypeResourceList<ReportIndex> indices = devReportClient.getReportIndices(ReportType.STUDY);
            assertTrue(containsThisIdentifier(indices, reportId));
            assertEquals(ReportType.STUDY, indices.getReportType());
            
            // but not if we use the other type
            indices = devReportClient.getReportIndices(ReportType.PARTICIPANT);
            assertFalse(containsThisIdentifier(indices, reportId));
            assertEquals(ReportType.PARTICIPANT, indices.getReportType());
            
            developer.getSession().getReportClient().deleteStudyReport(reportId);
            results = devReportClient.getParticipantReport(reportId, SEARCH_START_DATE, SEARCH_END_DATE);
            assertEquals(0, results.getTotal());
            assertEquals(0, results.getItems().size());
        } finally {
            developer.signOutAndDeleteUser();
        }
    }
    
    @Test
    public void correctExceptionsOnBadRequest() {
        TestUser developer = TestUserHelper.createAndSignInUser(ReportTest.class, true, Roles.DEVELOPER);
        try {
            ReportClient devReportClient = developer.getSession().getReportClient();
            try {
                devReportClient.getStudyReport(reportId, LocalDate.parse("2010-10-10"), LocalDate.parse("2012-10-10"));
                fail("Should have thrown an exception");
            } catch(BadRequestException e) {
                assertEquals("Date range cannot exceed 45 days, startDate=2010-10-10, endDate=2012-10-10", e.getMessage());
            }
            try {
                devReportClient.getStudyReport(reportId, SEARCH_END_DATE, SEARCH_START_DATE);
            } catch(BadRequestException e) {
                assertEquals("Start date 2016-02-20 can't be after end date 2016-02-01", e.getMessage());
            }
            try {
                devReportClient.getParticipantReport(reportId, LocalDate.parse("2010-10-10"), LocalDate.parse("2012-10-10"));
                fail("Should have thrown an exception");
            } catch(BadRequestException e) {
                assertEquals("Date range cannot exceed 45 days, startDate=2010-10-10, endDate=2012-10-10", e.getMessage());
            }
            try {
                devReportClient.getParticipantReport(reportId, SEARCH_END_DATE, SEARCH_START_DATE);
            } catch(BadRequestException e) {
                assertEquals("Start date 2016-02-20 can't be after end date 2016-02-01", e.getMessage());
            }
        } finally {
            developer.signOutAndDeleteUser();
        }
    }
    
    private boolean containsThisIdentifier(ReportTypeResourceList<ReportIndex> indices, String identifier) {
        for (ReportIndex index : indices.getItems()) {
            if (index.getIdentifier().equals(identifier)) {
                return true;
            }
        }
        return false;
    }
}
