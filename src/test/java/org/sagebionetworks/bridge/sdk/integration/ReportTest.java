package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.sagebionetworks.bridge.rest.model.Role.DEVELOPER;
import static org.sagebionetworks.bridge.rest.model.Role.RESEARCHER;
import static org.sagebionetworks.bridge.rest.model.Role.WORKER;
import static org.sagebionetworks.bridge.sdk.integration.Tests.STUDY_ID_1;
import static org.sagebionetworks.bridge.sdk.integration.Tests.STUDY_ID_2;
import static org.sagebionetworks.bridge.util.IntegTestUtils.TEST_APP_ID;

import java.util.Map;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.joda.time.DateTime;
import org.joda.time.LocalDate;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.rest.api.ForDevelopersApi;
import org.sagebionetworks.bridge.rest.api.ForSuperadminsApi;
import org.sagebionetworks.bridge.rest.api.ForWorkersApi;
import org.sagebionetworks.bridge.rest.api.ParticipantReportsApi;
import org.sagebionetworks.bridge.rest.api.ParticipantsApi;
import org.sagebionetworks.bridge.rest.api.StudyReportsApi;
import org.sagebionetworks.bridge.rest.api.UsersApi;
import org.sagebionetworks.bridge.rest.exceptions.BadRequestException;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.exceptions.InvalidEntityException;
import org.sagebionetworks.bridge.rest.exceptions.UnauthorizedException;
import org.sagebionetworks.bridge.rest.model.App;
import org.sagebionetworks.bridge.rest.model.ForwardCursorReportDataList;
import org.sagebionetworks.bridge.rest.model.ReportData;
import org.sagebionetworks.bridge.rest.model.ReportDataForWorker;
import org.sagebionetworks.bridge.rest.model.ReportDataList;
import org.sagebionetworks.bridge.rest.model.ReportIndex;
import org.sagebionetworks.bridge.rest.model.ReportIndexList;
import org.sagebionetworks.bridge.rest.model.ReportType;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;

@SuppressWarnings("unchecked")
public class ReportTest {

    private static final LocalDate SEARCH_START_DATE = LocalDate.parse("2016-02-01");
    private static final LocalDate SEARCH_END_DATE = LocalDate.parse("2016-02-20");
    
    private static final DateTime SEARCH_START_TIME = DateTime.parse("2016-02-01T10:00:00.000-07:00");
    private static final DateTime SEARCH_END_TIME = DateTime.parse("2016-02-05T00:00:00.00-07:00");
    
    private static final LocalDate DATE1 = LocalDate.parse("2016-02-02");
    private static final LocalDate DATE2 = LocalDate.parse("2016-02-03");
    private static final LocalDate DATE3 = LocalDate.parse("2016-02-04");

    private static final DateTime DATETIME1 = DateTime.parse("2016-02-02T10:00:02.000-07:00");
    private static final DateTime DATETIME2 = DateTime.parse("2016-02-03T14:34:02.123-07:00");
    private static final DateTime DATETIME3 = DateTime.parse("2016-02-04T23:56:16.937-07:00");

    private static TestUser admin;
    private static TestUser developer;
    private static TestUser appScopedDeveloper;
    private static TestUser worker;
    
    private String reportId;
    private TestUser user;
    private TestUser studyScopedUser;

    @BeforeClass
    public static void beforeClass() throws Exception {
        admin = TestUserHelper.getSignedInAdmin();
        
        developer = new TestUserHelper.Builder(ReportTest.class).withRoles(DEVELOPER)
                .createAndSignInUser();
        
        appScopedDeveloper = new TestUserHelper.Builder(ReportTest.class).withRoles(DEVELOPER)
                .createAndSignInUser(); // assigned to study1, like other admin accounts

        worker = TestUserHelper.createAndSignInUser(ReportTest.class, false, WORKER, RESEARCHER);

        // Worker test needs to be able to get healthcode.
        ForSuperadminsApi superadminApi = admin.getClient(ForSuperadminsApi.class);
        App app = superadminApi.getApp(TEST_APP_ID).execute().body();
        app.setHealthCodeExportEnabled(true);
        superadminApi.updateApp(app.getIdentifier(), app).execute().body();
    }

    @Before
    public void before() throws Exception {
        reportId = Tests.randomIdentifier(ReportTest.class);
    }

    @After
    public void after() throws Exception {
        ForDevelopersApi developerApi = developer.getClient(ForDevelopersApi.class);
        developerApi.deleteAllStudyReportRecords(reportId).execute();

        admin.getClient(ForAdminsApi.class).deleteParticipantReportIndex(reportId).execute();
        
        if (user != null) {
            developerApi.deleteAllParticipantReportRecords(user.getUserId(), reportId).execute();
            user.signOutAndDeleteUser();
        }
        if (studyScopedUser != null) {
            developerApi.deleteAllParticipantReportRecords(studyScopedUser.getUserId(), reportId).execute();
            studyScopedUser.signOutAndDeleteUser();
        }
    }
    
    @AfterClass
    public static void deleteDeveloper() throws Exception {
        if (developer != null) {
            developer.signOutAndDeleteUser();
        }
        if (appScopedDeveloper != null) {
            appScopedDeveloper.signOutAndDeleteUser();
        }
        if (worker != null) {
            worker.signOutAndDeleteUser();
        }

        ForSuperadminsApi superadminApi = admin.getClient(ForSuperadminsApi.class);
        App app = superadminApi.getApp(TEST_APP_ID).execute().body();
        app.setHealthCodeExportEnabled(false);
        superadminApi.updateApp(app.getIdentifier(), app).execute().body();
    }

    @Test
    public void developerCanCrudParticipantReport() throws Exception {
        user = TestUserHelper.createAndSignInUser(ReportTest.class, true);
        
        String userId = user.getSession().getId();
        ParticipantReportsApi reportsApi = developer.getClient(ParticipantReportsApi.class);

        reportsApi.addParticipantReportRecordV4(userId, reportId, makeReportData(DATE1, "foo", "A"))
                .execute();
        reportsApi.addParticipantReportRecordV4(userId, reportId, makeReportData(DATE2, "bar", "B"))
                .execute();
        reportsApi.addParticipantReportRecordV4(userId, reportId, makeReportData(DATE3, "baz", "C"))
                .execute();

        ForConsentedUsersApi usersApi = user.getClient(ForConsentedUsersApi.class);

        ReportDataList results = usersApi.getParticipantReportRecords(reportId, SEARCH_START_DATE, SEARCH_END_DATE)
                .execute().body();
        assertEquals(3, results.getItems().size());
        assertReportData(DATE1, "foo", "A", results.getItems().get(0));
        assertReportData(DATE2, "bar", "B", results.getItems().get(1));
        assertReportData(DATE3, "baz", "C", results.getItems().get(2));
        assertEquals(SEARCH_START_DATE, results.getRequestParams().getStartDate());
        assertEquals(SEARCH_END_DATE, results.getRequestParams().getEndDate());

        // This search is out of range, and should return no results.
        results = usersApi.getParticipantReportRecords(reportId, SEARCH_START_DATE.plusDays(30), SEARCH_END_DATE.plusDays(30))
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
        assertEquals(0, results.getItems().size());
        assertFalse(containsThisIdentifier(indices, reportId));
    }

    @Test
    public void workerCanCrudParticipantReportByDate() throws Exception {
        user = TestUserHelper.createAndSignInUser(ReportTest.class, true);

        String healthCode = worker.getClient(ParticipantsApi.class).getParticipantById(user.getSession().getId(),
                false).execute().body().getHealthCode();
        assertNotNull(healthCode);
        String userId = user.getSession().getId();

        // Worker can make reports.
        ForWorkersApi workerReportsApi = worker.getClient(ForWorkersApi.class);
        workerReportsApi.addParticipantReportRecord(reportId, makeReportDataForWorker(healthCode, DATE1, "foo",
                "A")).execute();
        workerReportsApi.addParticipantReportRecord(reportId, makeReportDataForWorker(healthCode, DATE2, "bar",
                "B")).execute();
        workerReportsApi.addParticipantReportRecord(reportId, makeReportDataForWorker(healthCode, DATE3, "baz",
                "C")).execute();

        // User can get those reports.
        ParticipantReportsApi userReportsApi = user.getClient(ParticipantReportsApi.class);
        ReportDataList results = userReportsApi.getParticipantReportRecords(reportId, SEARCH_START_DATE,
                SEARCH_END_DATE).execute().body();
        assertEquals(3, results.getItems().size());
        assertReportData(DATE1, "foo", "A", results.getItems().get(0));
        assertReportData(DATE2, "bar", "B", results.getItems().get(1));
        assertReportData(DATE3, "baz", "C", results.getItems().get(2));
        assertEquals(SEARCH_START_DATE, results.getRequestParams().getStartDate());
        assertEquals(SEARCH_END_DATE, results.getRequestParams().getEndDate());

        // Worker can get those reports.
        results = workerReportsApi.getParticipantReportsForParticipant(user.getAppId(), userId, reportId,
                SEARCH_START_DATE, SEARCH_END_DATE).execute().body();
        assertEquals(3, results.getItems().size());
        assertReportData(DATE1, "foo", "A", results.getItems().get(0));
        assertReportData(DATE2, "bar", "B", results.getItems().get(1));
        assertReportData(DATE3, "baz", "C", results.getItems().get(2));
        assertEquals(SEARCH_START_DATE, results.getRequestParams().getStartDate());
        assertEquals(SEARCH_END_DATE, results.getRequestParams().getEndDate());
    }

    @Test
    public void workerCanCrudParticipantReportByDateTime() throws Exception {
        user = TestUserHelper.createAndSignInUser(ReportTest.class, true);

        String healthCode = worker.getClient(ParticipantsApi.class).getParticipantById(user.getSession().getId(),
                false).execute().body().getHealthCode();
        assertNotNull(healthCode);
        String userId = user.getSession().getId();

        // Worker can make reports.
        ForWorkersApi workerReportsApi = worker.getClient(ForWorkersApi.class);
        workerReportsApi.addParticipantReportRecord(reportId, makeReportDataForWorker(healthCode, DATETIME1, "foo",
                "A")).execute();
        workerReportsApi.addParticipantReportRecord(reportId, makeReportDataForWorker(healthCode, DATETIME2, "bar",
                "B")).execute();
        workerReportsApi.addParticipantReportRecord(reportId, makeReportDataForWorker(healthCode, DATETIME3, "baz",
                "C")).execute();

        // User can get those reports.
        ParticipantReportsApi userReportsApi = user.getClient(ParticipantReportsApi.class);
        ForwardCursorReportDataList results = userReportsApi.getParticipantReportRecordsV4(reportId, SEARCH_START_TIME,
                SEARCH_END_TIME, 20, null).execute().body();
        assertEquals(3, results.getItems().size());
        assertNull(results.getNextPageOffsetKey());
        assertReportData(DATETIME1, "foo", "A", results.getItems().get(0));
        assertReportData(DATETIME2, "bar", "B", results.getItems().get(1));
        assertReportData(DATETIME3, "baz", "C", results.getItems().get(2));
        assertEquals(SEARCH_START_TIME.toString(), results.getRequestParams().getStartTime().toString());
        assertEquals(SEARCH_END_TIME.toString(), results.getRequestParams().getEndTime().toString());

        // Worker can get those reports.
        results = workerReportsApi.getParticipantReportsForParticipantV2(user.getAppId(), userId, reportId,
                SEARCH_START_TIME, SEARCH_END_TIME, null, 20).execute().body();
        assertEquals(3, results.getItems().size());
        assertNull(results.getNextPageOffsetKey());
        assertReportData(DATETIME1, "foo", "A", results.getItems().get(0));
        assertReportData(DATETIME2, "bar", "B", results.getItems().get(1));
        assertReportData(DATETIME3, "baz", "C", results.getItems().get(2));
        assertEquals(SEARCH_START_TIME.toString(), results.getRequestParams().getStartTime().toString());
        assertEquals(SEARCH_END_TIME.toString(), results.getRequestParams().getEndTime().toString());
    }

    @Test
    public void canCrudStudyReport() throws Exception {
        StudyReportsApi devReportClient = developer.getClient(StudyReportsApi.class);
        devReportClient.addStudyReportRecord(reportId, makeReportData(DATE1, "foo", "A")).execute();
        devReportClient.addStudyReportRecord(reportId, makeReportData(DATE2, "bar", "B")).execute();
        devReportClient.addStudyReportRecord(reportId, makeReportData(DATE3, "baz", "C")).execute();

        ReportDataList results = devReportClient.getStudyReportRecords(reportId, SEARCH_START_DATE, SEARCH_END_DATE)
                .execute().body();
        assertEquals(3, results.getItems().size());
        assertReportData(DATE1, "foo", "A", results.getItems().get(0));
        assertReportData(DATE2, "bar", "B", results.getItems().get(1));
        assertReportData(DATE3, "baz", "C", results.getItems().get(2));
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
    }

    @Test
    public void canMakeStudyReportPublic() throws Exception {
        StudyReportsApi devReportClient = developer.getClient(StudyReportsApi.class);
        devReportClient.addStudyReportRecord(reportId, makeReportData(DATE1, "foo", "A")).execute();
        devReportClient.addStudyReportRecord(reportId, makeReportData(DATE2, "bar", "B")).execute();
        devReportClient.addStudyReportRecord(reportId, makeReportData(DATE3, "baz", "C")).execute();

        // Reports are not publicly accessible.
        try {
            devReportClient.getPublicStudyReportRecords(developer.getAppId(), reportId, SEARCH_START_DATE,
                    SEARCH_END_DATE).execute().body();
            fail("Should have thrown exception");
        } catch (EntityNotFoundException e) {
            // expected exception
        }

        // Convert the index so the report is public.
        ReportIndex index = devReportClient.getStudyReportIndex(reportId).execute().body();
        index.setPublic(Boolean.TRUE);
        devReportClient.updateStudyReportIndex(reportId, index).execute();

        // Get the reports through the public API
        ReportDataList report = devReportClient
                .getPublicStudyReportRecords(developer.getAppId(), reportId, SEARCH_START_DATE, SEARCH_END_DATE)
                .execute().body();
        assertEquals(3, report.getItems().size());
        assertReportData(DATE1, "foo", "A", report.getItems().get(0));
        assertReportData(DATE2, "bar", "B", report.getItems().get(1));
        assertReportData(DATE3, "baz", "C", report.getItems().get(2));
    }

    @Test
    public void differentStudyReportsMakeDifferentIndices() throws Exception {
        // We previously had a bug in ReportService where if you create two reports with the same type but different
        // IDs, it would only create one index. (This was caused by a cache using the wrong cache key.) This is
        // unlikely to happen again, since the cache was removed. However, in order to verify the fix and prevent
        // future regression, this test has been added.

        StudyReportsApi devReportClient = developer.getClient(StudyReportsApi.class);
        try {
            // Create reports with different IDs.
            devReportClient.addStudyReportRecord(reportId + 1, makeReportData(DATE1, "foo", "A"))
                    .execute();
            devReportClient.addStudyReportRecord(reportId + 2, makeReportData(DATE2, "bar", "B"))
                    .execute();

            // We should see indices for both reports.
            ReportIndexList indices = devReportClient.getStudyReportIndices().execute().body();
            assertTrue(containsThisIdentifier(indices, reportId + 1));
            assertTrue(containsThisIdentifier(indices, reportId + 2));

            StudyReportsApi studyReportsApi = developer.getClient(StudyReportsApi.class);
            studyReportsApi.deleteAllStudyReportRecords(reportId + 1).execute();
            studyReportsApi.deleteAllStudyReportRecords(reportId + 2).execute();
        } finally {
            devReportClient.deleteAllStudyReportRecords(reportId + 1).execute();
            devReportClient.deleteAllStudyReportRecords(reportId + 2).execute();
        }
    }

    @Test
    public void correctExceptionsOnBadRequest() throws Exception {
        StudyReportsApi devReportClient = developer.getClient(StudyReportsApi.class);
        try {
            devReportClient.getStudyReportRecords(reportId, LocalDate.parse("2010-10-10"), LocalDate.parse("2012-10-10"))
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
    }
    
    @Test
    public void userCanCRUDSelfReports() throws Exception {
        user = TestUserHelper.createAndSignInUser(ReportTest.class, true);
        UsersApi userApi = user.getClient(UsersApi.class);

        userApi.saveParticipantReportRecordsV4(reportId, makeReportData(DATETIME1, "foo", "A")).execute();
        userApi.saveParticipantReportRecordsV4(reportId, makeReportData(DATETIME2, "bar", "B")).execute();
        userApi.saveParticipantReportRecordsV4(reportId, makeReportData(DATETIME3, "baz", "C")).execute();
        
        ForwardCursorReportDataList results = userApi.getParticipantReportRecordsV4(reportId, SEARCH_START_TIME,
                SEARCH_END_TIME, 20, null).execute().body();
        
        assertEquals(3, results.getItems().size());
        assertNull(results.getNextPageOffsetKey());
        assertReportData(DATETIME1, "foo", "A", results.getItems().get(0));
        assertReportData(DATETIME2, "bar", "B", results.getItems().get(1));
        assertReportData(DATETIME3, "baz", "C", results.getItems().get(2));

        // This is okay, it just doesn't return results
        results = userApi.getParticipantReportRecordsV4(reportId + "A", SEARCH_START_TIME, SEARCH_END_TIME,
                20, null).execute().body();
        assertTrue(results.getItems().isEmpty());
        
        // But this is an invalid parameter.
        try {
            userApi.getParticipantReportRecordsV4(reportId, SEARCH_START_TIME, SEARCH_END_TIME, 20,
                    "junkkey").execute();
            fail("Should have thrown an exception");
        } catch(BadRequestException e) {
            // expected exception
        }
        // so is this
        try {
            userApi.getParticipantReportRecordsV4(reportId, SEARCH_END_TIME, SEARCH_START_TIME, 20,
                    null).execute();
            fail("Should have thrown an exception");
        } catch(BadRequestException e) {
            // expected exception
        }

        ParticipantReportsApi reportsApi = developer.getClient(ParticipantReportsApi.class);
        reportsApi.deleteAllParticipantReportRecords(user.getSession().getId(), reportId).execute();
        results = userApi
                .getParticipantReportRecordsV4("foo", SEARCH_START_TIME, SEARCH_END_TIME, 20, null).execute().body();
        assertTrue(results.getItems().isEmpty());
    }
    
    @Test
    public void studyReportsNotVisibleOutsideOfStudy() throws Exception {
        StudyReportsApi devReportClient = appScopedDeveloper.getClient(StudyReportsApi.class);
        
        ReportData data1 = makeReportData(DATE1, "asdf", "A");
        data1.setStudyIds(ImmutableList.of(STUDY_ID_1));
        
        ReportData data2 = makeReportData(DATE2, "asdf", "B");
        devReportClient.addStudyReportRecord(reportId, data1).execute();
        devReportClient.addStudyReportRecord(reportId, data2).execute();
        
        // Not a member of the study used for these report records
        studyScopedUser = new TestUserHelper.Builder(ReportTest.class).withConsentUser(false)
                .withStudyIds(ImmutableSet.of(STUDY_ID_2)).createAndSignInUser();
        StudyReportsApi reportsApi = studyScopedUser.getClient(StudyReportsApi.class);
        ReportIndex index = reportsApi.getStudyReportIndex(reportId).execute().body();
        assertTrue(index.getStudyIds().contains(STUDY_ID_1));
        try {
            reportsApi.getStudyReportRecords(reportId, SEARCH_START_DATE, SEARCH_END_DATE).execute().body();
            fail("Should have thrown an exception");
        } catch(UnauthorizedException e) {
            // expected, and from the correct call.
        }
        
        // Make it public, and the user *can* see it
        index.setPublic(true);
        devReportClient.updateStudyReportIndex(index.getIdentifier(), index).execute();
        
        ReportDataList list = reportsApi.getStudyReportRecords(reportId, SEARCH_START_DATE, SEARCH_END_DATE).execute().body();
        assertEquals(2, list.getItems().size());
        ReportData retrieved1 = list.getItems().get(0);
        assertEquals(DATE1.toString(), retrieved1.getDate());
        assertEquals(DATE1, retrieved1.getLocalDate());
        assertEquals("A", ((Map<String,String>)retrieved1.getData()).get("asdf"));
        assertNull(retrieved1.getStudyIds());
        
        ReportData retrieved2 = list.getItems().get(1);
        assertEquals(DATE2.toString(), retrieved2.getDate());
        assertEquals(DATE2, retrieved2.getLocalDate());
        assertEquals("B", ((Map<String,String>)retrieved2.getData()).get("asdf"));
        assertNull(retrieved2.getStudyIds());
        
        // You cannot change the studies of this report after the fact
        ReportData data3 = makeReportData(DATE3, "asdf", "C");
        data3.setStudyIds(ImmutableList.of(STUDY_ID_2));
        try {
            devReportClient.addStudyReportRecord(reportId, data3).execute();
            fail("Should have thrown an exception");
        } catch(InvalidEntityException e) {
            assertEquals("studyIds cannot be changed once created for a report", 
                    e.getErrors().get("studyIds").get(0));
        }
    }
    
    @Test
    public void participantReportsNotVisibleOutsideOfStudy() throws Exception {
        // It would seem to be dumb to create reports for a participant that are associated to studies such that the
        // user will not be able to see them. Nevertheless, if it happens, we enforce visibility constraints.
        studyScopedUser = new TestUserHelper.Builder(ReportTest.class).withConsentUser(false)
                .withStudyIds(ImmutableSet.of(STUDY_ID_2)).createAndSignInUser();
        
        String healthCode = worker.getClient(ParticipantsApi.class)
                .getParticipantById(studyScopedUser.getUserId(), false).execute().body().getHealthCode();

        // Note that the first record saved, sets the studies in the index and applies to all records after
        // that. So the scoped user cannot then retrieve the records because they are not in study1.
        ForWorkersApi workerApi = worker.getClient(ForWorkersApi.class);
        ReportDataForWorker data1 = makeReportDataForWorker(healthCode, DATE1, "asdf", "A");
        data1.setStudyIds(ImmutableList.of(STUDY_ID_1));
        ReportDataForWorker data2 = makeReportDataForWorker(healthCode, DATE2, "asdf", "B");
        workerApi.addParticipantReportRecord(reportId, data1).execute();
        workerApi.addParticipantReportRecord(reportId, data2).execute();
        
        // The index now exists and can be retrieved.
        ParticipantReportsApi reportsApi = studyScopedUser.getClient(ParticipantReportsApi.class);
        ReportIndex index = reportsApi.getParticipantReportIndex(reportId).execute().body();
        assertTrue(index.getStudyIds().contains(STUDY_ID_1));
        
        try {
            reportsApi.getParticipantReportRecords(reportId, SEARCH_START_DATE, SEARCH_END_DATE).execute().body();
            fail("Should have thrown an exception");
        } catch(UnauthorizedException e) {
            // expected, and from the correct call.
        }
        
        String appId = studyScopedUser.getAppId();
        String userId = studyScopedUser.getUserId();
        // The worker, which is not in any studies, can get these reports
        ReportDataList list = workerApi.getParticipantReportsForParticipant(appId, userId, reportId, SEARCH_START_DATE, SEARCH_END_DATE).execute().body();
        assertEquals(2, list.getItems().size());
        ReportData retrieved1 = list.getItems().get(0);
        assertEquals(DATE1.toString(), retrieved1.getDate());
        assertEquals(DATE1, retrieved1.getLocalDate());
        assertEquals("A", ((Map<String,String>)retrieved1.getData()).get("asdf"));
        assertNull(retrieved1.getStudyIds());
        
        ReportData retrieved2 = list.getItems().get(1);
        assertEquals(DATE2.toString(), retrieved2.getDate());
        assertEquals(DATE2, retrieved2.getLocalDate());
        assertEquals("B", ((Map<String,String>)retrieved2.getData()).get("asdf"));
        assertNull(retrieved2.getStudyIds());
    }
    
    private static ReportData makeReportData(LocalDate date, String key, String value) {
        ReportData reportData = new ReportData();
        reportData.setLocalDate(date);
        reportData.setData(ImmutableMap.of(key, value));
        return reportData;
    }

    private static ReportDataForWorker makeReportDataForWorker(String healthCode, LocalDate date, String key,
            String value) {
        ReportDataForWorker reportData = new ReportDataForWorker();
        reportData.setHealthCode(healthCode);
        reportData.setDate(date);
        reportData.setData(ImmutableMap.of(key, value));
        return reportData;
    }

    private static void assertReportData(LocalDate expectedDate, String expectedKey, String expectedValue,
            ReportData reportData) {
        assertEquals(expectedDate, reportData.getLocalDate());

        Map<String, String> data = (Map<String, String>) reportData.getData();
        assertEquals(1, data.size());
        assertEquals(expectedValue, data.get(expectedKey));
    }

    private ReportData makeReportData(DateTime dateTime, String key, String value) {
        ReportData reportData = new ReportData();
        reportData.setDateTime(dateTime);
        reportData.setData(ImmutableMap.of(key, value));
        return reportData;
    }

    private ReportDataForWorker makeReportDataForWorker(String healthCode, DateTime dateTime, String key,
            String value) {
        ReportDataForWorker reportData = new ReportDataForWorker();
        reportData.setHealthCode(healthCode);
        reportData.setDateTime(dateTime);
        reportData.setData(ImmutableMap.of(key, value));
        return reportData;
    }

    private static void assertReportData(DateTime expectedDateTime, String expectedKey, String expectedValue,
            ReportData reportData) {
        // Note that Joda's DateTime.equals() doesn't work with timezones. Convert to ISO8601 string and compare.
        assertEquals(expectedDateTime.toString(), reportData.getDateTime().toString());

        Map<String, String> data = (Map<String, String>) reportData.getData();
        assertEquals(1, data.size());
        assertEquals(expectedValue, data.get(expectedKey));
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
