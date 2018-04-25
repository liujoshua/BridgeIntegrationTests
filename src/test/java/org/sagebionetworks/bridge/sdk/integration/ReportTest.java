package org.sagebionetworks.bridge.sdk.integration;

import com.google.gson.JsonObject;

import org.joda.time.DateTime;
import org.joda.time.LocalDate;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.rest.api.ForWorkersApi;
import org.sagebionetworks.bridge.rest.api.ParticipantReportsApi;
import org.sagebionetworks.bridge.rest.api.ParticipantsApi;
import org.sagebionetworks.bridge.rest.api.StudyReportsApi;
import org.sagebionetworks.bridge.rest.api.UsersApi;
import org.sagebionetworks.bridge.rest.exceptions.BadRequestException;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.model.ForwardCursorReportDataList;
import org.sagebionetworks.bridge.rest.model.ReportData;
import org.sagebionetworks.bridge.rest.model.ReportDataForWorker;
import org.sagebionetworks.bridge.rest.model.ReportDataList;
import org.sagebionetworks.bridge.rest.model.ReportIndex;
import org.sagebionetworks.bridge.rest.model.ReportIndexList;
import org.sagebionetworks.bridge.rest.model.ReportType;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.Study;
import org.sagebionetworks.bridge.rest.model.VersionHolder;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ReportTest {

    private static final LocalDate SEARCH_START_DATE = LocalDate.parse("2016-02-01");
    private static final LocalDate SEARCH_END_DATE = LocalDate.parse("2016-02-20");
    
    private static final DateTime SEARCH_START_TIME = DateTime.parse("2016-02-01T10:00:00.000-07:00");
    private static final DateTime SEARCH_END_TIME = DateTime.parse("2016-02-05T00:00:00.00-07:00");
    
    private static LocalDate TIME1 = LocalDate.parse("2016-02-02");
    private static LocalDate TIME2 = LocalDate.parse("2016-02-03");
    private static LocalDate TIME3 = LocalDate.parse("2016-02-04");

    private static DateTime DATETIME1 = DateTime.parse("2016-02-02T10:00:02.000-07:00");
    private static DateTime DATETIME2 = DateTime.parse("2016-02-03T14:34:02.123-07:00");
    private static DateTime DATETIME3 = DateTime.parse("2016-02-04T23:56:16.937-07:00");
    
    private static JsonObject DATA1 = new JsonObject();
    private static JsonObject DATA2 = new JsonObject();
    private static JsonObject DATA3 = new JsonObject();

    private static ReportData REPORT1;
    private static ReportData REPORT2;
    private static ReportData REPORT3;

    private TestUser admin;

    private TestUser user;

    private String reportId;

    @Before
    public void before() throws Exception {
        REPORT1 = new ReportData();
        REPORT1.setLocalDate(TIME1);
        REPORT1.setData(DATA1);
        REPORT2 = new ReportData();
        REPORT2.setLocalDate(TIME2);
        REPORT2.setData(DATA2);
        REPORT3 = new ReportData();
        REPORT3.setLocalDate(TIME3);
        REPORT3.setData(DATA3);

        this.admin = TestUserHelper.getSignedInAdmin();
        this.user = TestUserHelper.createAndSignInUser(ReportTest.class, true);
        this.reportId = Tests.randomIdentifier(ReportTest.class);
    }

    @After
    public void after() throws Exception {
        if (user != null) {
            user.signOutAndDeleteUser();
        }
    }

    @Test
    public void developerCanCrudParticipantReport() throws Exception {
        TestUser developer = TestUserHelper.createAndSignInUser(ReportTest.class, true, Role.DEVELOPER);
        String userId = user.getSession().getId();
        try {
            ParticipantReportsApi reportsApi = developer.getClient(ParticipantReportsApi.class);

            reportsApi.addParticipantReportRecord(userId, reportId, REPORT1).execute();
            reportsApi.addParticipantReportRecord(userId, reportId, REPORT2).execute();
            reportsApi.addParticipantReportRecord(userId, reportId, REPORT3).execute();

            ForConsentedUsersApi usersApi = user.getClient(ForConsentedUsersApi.class);

            ReportDataList results = usersApi.getParticipantReportRecords(reportId, SEARCH_START_DATE, SEARCH_END_DATE)
                    .execute().body();
            assertEquals(3, results.getItems().size());
            assertEquals(SEARCH_START_DATE, results.getRequestParams().getStartDate());
            assertEquals(SEARCH_END_DATE, results.getRequestParams().getEndDate());

            // This search is out of range, and should return no results.
            results = usersApi
                    .getParticipantReportRecords(reportId, SEARCH_START_DATE.plusDays(30), SEARCH_END_DATE.plusDays(30))
                    .execute().body();
            assertEquals(0, results.getItems().size());

            // We should see indices for this participant report
            ReportIndexList indices = reportsApi.getParticipantReportIndices().execute().body();
            assertTrue(containsThisIdentifier(indices, reportId));
            assertEquals(ReportType.PARTICIPANT, indices.getRequestParams().getReportType());

            // but not if we ask for study reports
            StudyReportsApi studyReportsApi = developer.getClient(StudyReportsApi.class);
            indices = studyReportsApi.getStudyReportIndices().execute().body();
            assertFalse(containsThisIdentifier(indices, reportId));
            assertEquals(ReportType.STUDY, indices.getRequestParams().getReportType());

            // delete
            reportsApi.deleteAllParticipantReportRecords(userId, reportId).execute();
            results = usersApi.getParticipantReportRecords(reportId, SEARCH_START_DATE, SEARCH_END_DATE).execute()
                    .body();
            assertFalse(containsThisIdentifier(indices, reportId));
        } finally {
            developer.signOutAndDeleteUser();

            ForAdminsApi adminApi = admin.getClient(ForAdminsApi.class);
            adminApi.deleteParticipantReportIndex(reportId).execute();
        }
    }

    @Test
    public void workerCanCrudParticipantReport() throws Exception {
        ReportDataForWorker report1 = new ReportDataForWorker();
        report1.setDate(TIME1);
        report1.setData(DATA1);
        ReportDataForWorker report2 = new ReportDataForWorker();
        report2.setDate(TIME2);
        report2.setData(DATA2);
        ReportDataForWorker report3 = new ReportDataForWorker();
        report3.setDate(TIME3);
        report3.setData(DATA3);

        TestUser admin = TestUserHelper.getSignedInAdmin();
        ForAdminsApi adminApi = admin.getClient(ForAdminsApi.class);
        Study study = adminApi.getStudy("api").execute().body();
        study.setHealthCodeExportEnabled(true);
        VersionHolder versionHolder = adminApi.updateStudy(study.getIdentifier(), study).execute().body();
        study.setVersion(versionHolder.getVersion());

        // Make this worker a researcher solely for the purpose of getting the healthCode needed to user the worker
        // API
        TestUser worker = TestUserHelper.createAndSignInUser(ReportTest.class, false, Role.WORKER, Role.RESEARCHER);

        TestUser developer = TestUserHelper.createAndSignInUser(ReportTest.class, false, Role.DEVELOPER);

        String healthCode = worker.getClient(ParticipantsApi.class).getParticipantById(user.getSession().getId(), false).execute()
                .body().getHealthCode();
        assertNotNull(healthCode);

        String userId = user.getSession().getId();
        try {
            report1.setHealthCode(healthCode);
            report2.setHealthCode(healthCode);
            report3.setHealthCode(healthCode);

            ForWorkersApi workerReportsApi = worker.getClient(ForWorkersApi.class);
            workerReportsApi.addParticipantReportRecord(reportId, report1).execute();
            workerReportsApi.addParticipantReportRecord(reportId, report2).execute();
            workerReportsApi.addParticipantReportRecord(reportId, report3).execute();

            ParticipantReportsApi userReportsApi = user.getClient(ParticipantReportsApi.class);
            ReportDataList results = userReportsApi
                    .getParticipantReportRecords(reportId, SEARCH_START_DATE, SEARCH_END_DATE).execute().body();

            assertEquals(3, results.getItems().size());
            assertEquals(SEARCH_START_DATE, results.getRequestParams().getStartDate());
            assertEquals(SEARCH_END_DATE, results.getRequestParams().getEndDate());

            // This search is out of range, and should return no results.
            results = userReportsApi
                    .getParticipantReportRecords(reportId, SEARCH_START_DATE.plusDays(30), SEARCH_END_DATE.plusDays(30))
                    .execute().body();
            assertEquals(0, results.getItems().size());

            // delete. Must be done by developer
            ParticipantReportsApi developerReportsApi = developer.getClient(ParticipantReportsApi.class);
            developerReportsApi.deleteAllParticipantReportRecords(userId, reportId).execute();

            results = userReportsApi.getParticipantReportRecords(reportId, SEARCH_START_DATE, SEARCH_END_DATE).execute()
                    .body();
            assertEquals(0, results.getItems().size());
        } finally {
            study.setHealthCodeExportEnabled(false);
            adminApi.updateStudy(study.getIdentifier(), study).execute();

            worker.signOutAndDeleteUser();
            developer.signOutAndDeleteUser();

            admin.getClient(ForAdminsApi.class).deleteParticipantReportIndex(reportId).execute();
        }
    }

    @Test
    public void canCrudStudyReport() throws Exception {
        TestUser developer = TestUserHelper.createAndSignInUser(ReportTest.class, true, Role.DEVELOPER);
        try {
            StudyReportsApi devReportClient = developer.getClient(StudyReportsApi.class);
            devReportClient.addStudyReportRecord(reportId, REPORT1).execute();
            devReportClient.addStudyReportRecord(reportId, REPORT2).execute();
            devReportClient.addStudyReportRecord(reportId, REPORT3).execute();

            ReportDataList results = devReportClient.getStudyReportRecords(reportId, SEARCH_START_DATE, SEARCH_END_DATE)
                    .execute().body();
            assertEquals(3, results.getItems().size());
            assertEquals(SEARCH_START_DATE, results.getRequestParams().getStartDate());
            assertEquals(SEARCH_END_DATE, results.getRequestParams().getEndDate());

            // This search is out of range, and should return no results.
            ParticipantReportsApi participantReportsApi = developer.getClient(ParticipantReportsApi.class);
            results = participantReportsApi.getParticipantReportRecords(reportId, SEARCH_START_DATE.minusDays(30),
                    SEARCH_END_DATE.minusDays(30)).execute().body();
            assertEquals(0, results.getItems().size());

            // We should see indices for this study report
            ReportIndexList indices = devReportClient.getStudyReportIndices().execute().body();
            assertTrue(containsThisIdentifier(indices, reportId));
            assertEquals(ReportType.STUDY, indices.getRequestParams().getReportType());

            // but not if we use the other type
            indices = participantReportsApi.getParticipantReportIndices().execute().body();
            assertFalse(containsThisIdentifier(indices, reportId));
            assertEquals(ReportType.PARTICIPANT, indices.getRequestParams().getReportType());

            developer.getClient(StudyReportsApi.class).deleteAllStudyReportRecords(reportId).execute();
            results = participantReportsApi.getParticipantReportRecords(reportId, SEARCH_START_DATE, SEARCH_END_DATE)
                    .execute().body();
            assertEquals(0, results.getItems().size());
        } finally {
            developer.signOutAndDeleteUser();
        }
    }

    @Test
    public void canMakeStudyReportPublic() throws Exception {
        TestUser developer = TestUserHelper.createAndSignInUser(ReportTest.class, true, Role.DEVELOPER);
        try {
            StudyReportsApi devReportClient = developer.getClient(StudyReportsApi.class);
            devReportClient.addStudyReportRecord(reportId, REPORT1).execute();
            devReportClient.addStudyReportRecord(reportId, REPORT2).execute();
            devReportClient.addStudyReportRecord(reportId, REPORT3).execute();

            // Reports are not publicly accessible.
            try {
                devReportClient.getPublicStudyReportRecords(developer.getStudyId(), reportId, SEARCH_START_DATE,
                        SEARCH_END_DATE).execute().body();
                fail("Should have thrown exception");
            } catch(EntityNotFoundException e) {
                // expected exception
            }

            // Convert the index so the report is public.
            ReportIndex index = devReportClient.getStudyReportIndex(reportId).execute().body();
            index.setPublic(Boolean.TRUE);
            devReportClient.updateStudyReportIndex(reportId, index).execute();

            // Get the reports through the public API
            ReportDataList report = devReportClient
                    .getPublicStudyReportRecords(developer.getStudyId(), reportId, SEARCH_START_DATE, SEARCH_END_DATE)
                    .execute().body();
            assertEquals(3, report.getItems().size());

        } finally {
            StudyReportsApi devReportClient = developer.getClient(StudyReportsApi.class);
            devReportClient.deleteAllStudyReportRecords(reportId).execute();
            developer.signOutAndDeleteUser();
        }
    }

    @Test
    public void differentStudyReportsMakeDifferentIndices() throws Exception {
        // We previously had a bug in ReportService where if you create two reports with the same type but different
        // IDs, it would only create one index. (This was caused by a cache using the wrong cache key.) This is
        // unlikely to happen again, since the cache was removed. However, in order to verify the fix and prevent
        // future regression, this test has been added.

        TestUser developer = TestUserHelper.createAndSignInUser(this.getClass(), false, Role.DEVELOPER);
        try {
            // Create reports with different IDs.
            StudyReportsApi devReportClient = developer.getClient(StudyReportsApi.class);
            devReportClient.addStudyReportRecord(reportId + 1, REPORT1).execute();
            devReportClient.addStudyReportRecord(reportId + 2, REPORT2).execute();

            // We should see indices for both reports.
            ReportIndexList indices = devReportClient.getStudyReportIndices().execute().body();
            assertTrue(containsThisIdentifier(indices, reportId + 1));
            assertTrue(containsThisIdentifier(indices, reportId + 2));

            StudyReportsApi studyReportsApi = developer.getClient(StudyReportsApi.class);
            studyReportsApi.deleteAllStudyReportRecords(reportId + 1).execute();
            studyReportsApi.deleteAllStudyReportRecords(reportId + 2).execute();
        } finally {
            developer.signOutAndDeleteUser();
        }
    }

    @Test
    public void correctExceptionsOnBadRequest() throws Exception {
        TestUser developer = TestUserHelper.createAndSignInUser(ReportTest.class, true, Role.DEVELOPER);
        try {
            StudyReportsApi devReportClient = developer.getClient(StudyReportsApi.class);
            try {
                devReportClient
                        .getStudyReportRecords(reportId, LocalDate.parse("2010-10-10"), LocalDate.parse("2012-10-10"))
                        .execute();
                fail("Should have thrown an exception");
            } catch (BadRequestException e) {
                assertEquals("Date range cannot exceed 45 days, startDate=2010-10-10, endDate=2012-10-10",
                        e.getMessage());
            }
            try {
                devReportClient.getStudyReportRecords(reportId, SEARCH_END_DATE, SEARCH_START_DATE).execute();
            } catch (BadRequestException e) {
                assertEquals("Start date 2016-02-20 can't be after end date 2016-02-01", e.getMessage());
            }
            ParticipantReportsApi participantReportsApi = developer.getClient(ParticipantReportsApi.class);
            try {
            		participantReportsApi.getParticipantReportRecords(reportId, LocalDate.parse("2010-10-10"),
                        LocalDate.parse("2012-10-10")).execute();
                fail("Should have thrown an exception");
            } catch (BadRequestException e) {
                assertEquals("Date range cannot exceed 45 days, startDate=2010-10-10, endDate=2012-10-10",
                        e.getMessage());
            }
            try {
				participantReportsApi.getParticipantReportRecords(reportId, SEARCH_END_DATE, SEARCH_START_DATE)
						.execute();
            } catch (BadRequestException e) {
                assertEquals("Start date 2016-02-20 can't be after end date 2016-02-01", e.getMessage());
            }
        } finally {
            developer.signOutAndDeleteUser();
        }
    }
    
    @Test
    public void userCanCRUDSelfReports() throws Exception {
        UsersApi userApi = user.getClient(UsersApi.class);

        setDateTime(REPORT1, DATETIME1);
        setDateTime(REPORT2, DATETIME2);
        setDateTime(REPORT3, DATETIME3);

        userApi.saveParticipantReportRecordsV4("foo", REPORT1).execute();
        userApi.saveParticipantReportRecordsV4("foo", REPORT2).execute();
        userApi.saveParticipantReportRecordsV4("foo", REPORT3).execute();
        
        ForwardCursorReportDataList results = userApi
                .getParticipantReportRecordsV4("foo", SEARCH_START_TIME, SEARCH_END_TIME, 20, null).execute().body();
        
        assertEquals(3, results.getItems().size());
        assertNull(results.getNextPageOffsetKey());
        assertEquals(DATETIME1.toString(), results.getItems().get(0).getDate());
        assertEquals(DATETIME2.toString(), results.getItems().get(1).getDate());
        assertEquals(DATETIME3.toString(), results.getItems().get(2).getDate());
        
        // Time zone has been restored
        assertEquals(DATETIME1.toString(), results.getItems().get(0).getDateTime().toString());
        assertEquals(DATETIME2.toString(), results.getItems().get(1).getDateTime().toString());
        assertEquals(DATETIME3.toString(), results.getItems().get(2).getDateTime().toString());
        
        // This is okay, it just doesn't return results
        results = userApi
                .getParticipantReportRecordsV4("bar", SEARCH_START_TIME, SEARCH_END_TIME, 20, null).execute().body();
        assertTrue(results.getItems().isEmpty());
        
        // But this is an invalid parameter.
        try {
            results = userApi
                    .getParticipantReportRecordsV4("foo", SEARCH_START_TIME, SEARCH_END_TIME, 20, "junkkey").execute().body();
            fail("Should have thrown an exception");
        } catch(BadRequestException e) {
        }
        // so is this
        try {
            results = userApi
                    .getParticipantReportRecordsV4("foo", SEARCH_END_TIME, SEARCH_START_TIME, 20, null).execute().body();
            fail("Should have thrown an exception");
        } catch(BadRequestException e) {
        }
        
        TestUser developer = TestUserHelper.createAndSignInUser(ReportTest.class, false, Role.DEVELOPER);
        try {
            ParticipantReportsApi reportsApi = developer.getClient(ParticipantReportsApi.class);
            reportsApi.deleteAllParticipantReportRecords(user.getSession().getId(), "foo").execute();
        } catch(Throwable t) {
            developer.signOutAndDeleteUser();
        }
        results = userApi
                .getParticipantReportRecordsV4("foo", SEARCH_START_TIME, SEARCH_END_TIME, 20, null).execute().body();
        assertTrue(results.getItems().isEmpty());
    }
    
    private void setDateTime(ReportData report, DateTime dateTime) {
        report.setLocalDate(null);
        report.setDateTime(dateTime);
    }

    private boolean containsThisIdentifier(ReportIndexList indices, String identifier) {
        for (ReportIndex index : indices.getItems()) {
            if (index.getIdentifier().equals(identifier)) {
                return true;
            }
        }
        return false;
    }
}
