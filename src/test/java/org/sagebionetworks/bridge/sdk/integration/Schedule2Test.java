package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.sagebionetworks.bridge.rest.model.NotificationType.START_OF_WINDOW;
import static org.sagebionetworks.bridge.rest.model.PerformanceOrder.SEQUENTIAL;
import static org.sagebionetworks.bridge.rest.model.ReminderType.BEFORE_WINDOW_END;
import static org.sagebionetworks.bridge.rest.model.Role.DEVELOPER;
import static org.sagebionetworks.bridge.rest.model.Role.STUDY_DESIGNER;
import static org.sagebionetworks.bridge.sdk.integration.Tests.ORG_ID_1;
import static org.sagebionetworks.bridge.sdk.integration.Tests.ORG_ID_2;
import static org.sagebionetworks.bridge.util.IntegTestUtils.SAGE_ID;
import static org.sagebionetworks.bridge.util.IntegTestUtils.TEST_APP_ID;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.api.AssessmentsApi;
import org.sagebionetworks.bridge.rest.api.OrganizationsApi;
import org.sagebionetworks.bridge.rest.api.SchedulesV2Api;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.exceptions.InvalidEntityException;
import org.sagebionetworks.bridge.rest.exceptions.PublishedEntityException;
import org.sagebionetworks.bridge.rest.model.Assessment;
import org.sagebionetworks.bridge.rest.model.AssessmentReference2;
import org.sagebionetworks.bridge.rest.model.ColorScheme;
import org.sagebionetworks.bridge.rest.model.Label;
import org.sagebionetworks.bridge.rest.model.NotificationMessage;
import org.sagebionetworks.bridge.rest.model.Schedule2;
import org.sagebionetworks.bridge.rest.model.Schedule2List;
import org.sagebionetworks.bridge.rest.model.Session;
import org.sagebionetworks.bridge.rest.model.TimeWindow;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;

public class Schedule2Test {
     
    TestUser developer;
    TestUser studyDesigner;
    Assessment assessment;
    Schedule2 schedule;
    
    String org1ScheduleGuid;
    String org2ScheduleGuid;
    
    @Before
    public void before() throws Exception {
        developer = TestUserHelper.createAndSignInUser(Schedule2Test.class, false, DEVELOPER);
        studyDesigner = TestUserHelper.createAndSignInUser(Schedule2Test.class, false, STUDY_DESIGNER);
        
        assessment = new Assessment().title(Schedule2Test.class.getSimpleName()).osName("Universal").ownerId(SAGE_ID)
                .identifier(Tests.randomIdentifier(Schedule2Test.class));
        
        assessment = developer.getClient(AssessmentsApi.class).createAssessment(assessment).execute().body();
    }
    
    @After
    public void after() throws Exception {
        TestUser admin = TestUserHelper.getSignedInAdmin();
        SchedulesV2Api adminSchedulesApi = admin.getClient(SchedulesV2Api.class);
        if (schedule != null && schedule.getGuid() != null) {
            adminSchedulesApi.deleteSchedule(schedule.getGuid(), true).execute();
        }
        if (org1ScheduleGuid != null) {
            adminSchedulesApi.deleteSchedule(org1ScheduleGuid, true).execute();
        }
        if (org2ScheduleGuid != null) {
            adminSchedulesApi.deleteSchedule(org2ScheduleGuid, true).execute();
        }
        if (assessment != null && assessment.getGuid() != null) {
            admin.getClient(AssessmentsApi.class).deleteAssessment(assessment.getGuid(), true).execute();
        }
        if (studyDesigner != null) {
            studyDesigner.signOutAndDeleteUser();
        }
        if (developer != null) {
            developer.signOutAndDeleteUser();
        }
    }

    @Test
    public void crudWorks() throws Exception {
        SchedulesV2Api schedulesApi = studyDesigner.getClient(SchedulesV2Api.class);
        // create schedule that is invalid, fails.
        
        schedule = new Schedule2();
        try {
            schedulesApi.createSchedule(schedule).execute();
            fail("Should have thrown exception");
        } catch(InvalidEntityException e) {
        }
        
        schedule.setName("Test Schedule [Schedule2Test]");
        schedule.setDuration("P10W");
        // These will all be ignored
        schedule.setDeleted(true);
        schedule.setGuid("ABC");
        schedule.setOwnerId("somebody");
        schedule.setPublished(true);
        schedule.setVersion(10L);
        
        // create schedule.
        schedule = schedulesApi.createSchedule(schedule).execute().body();
        assertEquals("Test Schedule [Schedule2Test]", schedule.getName());
        assertEquals("P10W", schedule.getDuration());
        assertFalse(schedule.isDeleted());
        assertFalse(schedule.isPublished());
        assertEquals(SAGE_ID, schedule.getOwnerId());
        assertNotEquals("ABC", schedule.getGuid());
        assertNotEquals(Long.valueOf(10), schedule.getVersion());
        
        // add a session
        Session session = new Session();
        session.setName("Simple repeating assessment");
        session.addLabelsItem(new Label().lang("en").value("Take the assessment"));
        session.setStartEventId("enrollment");
        session.setDelay("P1W");
        session.setInterval("P4W");
        session.setPerformanceOrder(SEQUENTIAL);
        session.setNotifyAt(START_OF_WINDOW);
        session.setRemindAt(BEFORE_WINDOW_END);
        session.setReminderPeriod("P1D");
        session.setAllowSnooze(true);
        
        ColorScheme colorScheme = new ColorScheme()
                .background("#111111")
                .foreground("#222222")
                .activated("#333333")
                .inactivated("#444444");
        
        AssessmentReference2 ref = new AssessmentReference2()
                .guid(assessment.getGuid())
                .appId(TEST_APP_ID)
                .colorScheme(colorScheme)
                .addLabelsItem(new Label().lang("en").value("Test Value"))
                .minutesToComplete(10)
                .title("A title")
                .identifier(assessment.getIdentifier());
        session.addAssessmentsItem(ref);
        session.addTimeWindowsItem(new TimeWindow().startTime("08:00").expiration("P3D"));

        NotificationMessage msgEn = new NotificationMessage().lang("en").subject("Subject")
                .message("Time to take the assessment");
        NotificationMessage msgFr = new NotificationMessage().lang("fr").subject("Sujet")
                .message("Il est temps de passer l'évaluation");
        session.addMessagesItem(msgEn);
        session.addMessagesItem(msgFr);
        
        schedule.addSessionsItem(session);

        schedule = schedulesApi.updateSchedule(schedule.getGuid(), schedule).execute().body();
        assertSchedule(schedule);
        
        // get schedule
        schedule = schedulesApi.getSchedule(schedule.getGuid()).execute().body();
        assertSchedule(schedule);
        
        // update schedule, fails validation
        schedule.getSessions().get(0).setName("Updated name for session");
        schedule.getSessions().get(0).addLabelsItem(new Label().lang("ja").value("評価を受ける"));

        // update schedule, succeeds
        schedule = schedulesApi.updateSchedule(schedule.getGuid(), schedule).execute().body();
        assertEquals("Updated name for session", schedule.getSessions().get(0).getName());
        assertEquals("ja", schedule.getSessions().get(0).getLabels().get(1).getLang());
        assertEquals("評価を受ける", schedule.getSessions().get(0).getLabels().get(1).getValue());
        
        // get schedules works (try the parameters)
        Schedule2List page = schedulesApi.getSchedules(null, null, null).execute().body();
        assertEquals(Integer.valueOf(1), page.getTotal());
        assertEquals(1, page.getItems().size());
        assertEquals(schedule.getGuid(), page.getItems().get(0).getGuid());
        assertEquals(Integer.valueOf(0), page.getRequestParams().getOffsetBy());
        assertEquals(Integer.valueOf(50), page.getRequestParams().getPageSize());
        
        page = schedulesApi.getSchedules(50, null, null).execute().body();
        assertEquals(Integer.valueOf(1), page.getTotal());
        assertTrue(page.getItems().isEmpty());

        // delete schedule logically (even though set to physical=true)
        schedulesApi.deleteSchedule(schedule.getGuid(), true).execute().body();
        
        // it still exists
        schedule = schedulesApi.getSchedule(schedule.getGuid()).execute().body();
        assertTrue(schedule.isDeleted());
        
        // get schedules deleted/not deleted works
        page = schedulesApi.getSchedules(null, null, false).execute().body();
        assertEquals(Integer.valueOf(0), page.getTotal());
        assertTrue(page.getItems().isEmpty());
        
        page = schedulesApi.getSchedules(null, null, true).execute().body();
        assertEquals(Integer.valueOf(1), page.getTotal());
        assertFalse(page.getItems().isEmpty());
        
        // cannot update logically deleted schedule
        try {
            schedulesApi.updateSchedule(schedule.getGuid(), schedule).execute();
            fail("Should have thrown exception");
        } catch(EntityNotFoundException e) {
        }
        
        // cannot publish it
        try {
            schedulesApi.publishSchedule(schedule.getGuid()).execute();
            fail("Should have thrown exception");
        } catch(EntityNotFoundException e) {
        }
        
        // undelete it through update
        schedule.setDeleted(false);
        schedule = schedulesApi.updateSchedule(schedule.getGuid(), schedule).execute().body();
        
        // publish works
        schedulesApi.publishSchedule(schedule.getGuid()).execute();
        
        // update now fails
        try {
            schedulesApi.updateSchedule(schedule.getGuid(), schedule).execute();
            fail("Should have thrown exception");
        } catch(PublishedEntityException e) {
        }
        
        // republish fails
        try {
            schedulesApi.publishSchedule(schedule.getGuid()).execute();
            fail("Should have thrown exception");
        } catch(PublishedEntityException e) {
        }
        
        // physically delete it
        TestUser admin = TestUserHelper.getSignedInAdmin();
        admin.getClient(SchedulesV2Api.class)
            .deleteSchedule(schedule.getGuid(), true).execute();
        
        try {
            schedulesApi.getSchedule(schedule.getGuid()).execute();
            fail("Should have thrown exception");
        } catch(EntityNotFoundException e) {
        }
        schedule = null;
    }
    
    @Test
    public void schedulesScopedToOrganization() throws Exception {
        TestUser admin = TestUserHelper.getSignedInAdmin();
        OrganizationsApi adminOrgApi = admin.getClient(OrganizationsApi.class);
        
        SchedulesV2Api schedulesApi = studyDesigner.getClient(SchedulesV2Api.class);
        
        adminOrgApi.removeMember(SAGE_ID, studyDesigner.getUserId()).execute();
        adminOrgApi.addMember(ORG_ID_1, studyDesigner.getUserId()).execute();
        
        schedule = new Schedule2();
        schedule.setName("ORG1: Test Schedule [Schedule2Test]");
        schedule.setDuration("P30D");
        org1ScheduleGuid = schedulesApi.createSchedule(schedule).execute().body().getGuid();
        
        adminOrgApi.removeMember(ORG_ID_1, studyDesigner.getUserId()).execute();
        adminOrgApi.addMember(ORG_ID_2, studyDesigner.getUserId()).execute();
        
        schedule.setName("ORG2: Test Schedule [Schedule2Test]");
        org2ScheduleGuid = schedulesApi.createSchedule(schedule).execute().body().getGuid();
        
        // Designer should not be able to see study schedule1 
        Schedule2List list = schedulesApi.getSchedules(null, null, null).execute().body();
        assertEquals(1, list.getItems().size());
        assertFalse(list.getItems().stream().anyMatch(sch -> sch.getName().contains("ORG1")));
        assertTrue(list.getItems().stream().anyMatch(sch -> sch.getName().contains("ORG2")));
        
        adminOrgApi.removeMember(ORG_ID_2, studyDesigner.getUserId()).execute();
        adminOrgApi.addMember(Tests.ORG_ID_1, studyDesigner.getUserId()).execute();

        list = schedulesApi.getSchedules(null, null, null).execute().body();
        assertEquals(1, list.getItems().size());
        assertTrue(list.getItems().stream().anyMatch(sch -> sch.getName().contains("ORG1")));
        assertFalse(list.getItems().stream().anyMatch(sch -> sch.getName().contains("ORG2")));
        
        // Developers see everything
        list = developer.getClient(SchedulesV2Api.class).getSchedules(null, null, null).execute().body();
        assertEquals(2, list.getItems().size());
        assertTrue(list.getItems().stream().anyMatch(sch -> sch.getName().contains("ORG1")));
        assertTrue(list.getItems().stream().anyMatch(sch -> sch.getName().contains("ORG2")));
    }
    
    private void assertSchedule(Schedule2 schedule) {
        assertEquals("Test Schedule [Schedule2Test]", schedule.getName());
        assertEquals("P10W", schedule.getDuration());
        assertFalse(schedule.isDeleted());
        assertFalse(schedule.isPublished());
        assertEquals(SAGE_ID, schedule.getOwnerId());
        assertNotEquals("ABC", schedule.getGuid());
        assertNotEquals(Long.valueOf(10), schedule.getVersion());
        
        Session session = schedule.getSessions().get(0);
        assertEquals("Simple repeating assessment", session.getName());
        assertEquals(1, session.getLabels().size());
        assertEquals("en", session.getLabels().get(0).getLang());
        assertEquals("Take the assessment", session.getLabels().get(0).getValue());
        assertEquals("enrollment", session.getStartEventId());
        assertEquals(SEQUENTIAL, session.getPerformanceOrder());
        assertEquals("P1W", session.getDelay());
        assertEquals("P4W", session.getInterval());
        assertEquals(START_OF_WINDOW, session.getNotifyAt());
        assertEquals(BEFORE_WINDOW_END, session.getRemindAt());
        assertEquals("P1D", session.getReminderPeriod());
        assertTrue(session.isAllowSnooze());
        
        assertEquals(1, session.getAssessments().size());
        AssessmentReference2 ref = session.getAssessments().get(0);
        assertEquals(TEST_APP_ID, ref.getAppId());
        assertEquals(assessment.getGuid(), ref.getGuid());
        assertEquals(assessment.getIdentifier(), ref.getIdentifier());
        assertEquals("en", ref.getLabels().get(0).getLang());
        assertEquals("Test Value", ref.getLabels().get(0).getValue());
        assertEquals("#111111", ref.getColorScheme().getBackground());
        assertEquals("#222222", ref.getColorScheme().getForeground());
        assertEquals("#333333", ref.getColorScheme().getActivated());
        assertEquals("#444444", ref.getColorScheme().getInactivated());
        assertEquals(Integer.valueOf(10), ref.getMinutesToComplete());
        assertEquals("A title", ref.getTitle());
        
        assertEquals(1, session.getTimeWindows().size());
        TimeWindow window = session.getTimeWindows().get(0);
        assertEquals("08:00", window.getStartTime());
        assertEquals("P3D", window.getExpiration());
        
        NotificationMessage msgEn = session.getMessages().get(0);
        assertEquals("en", msgEn.getLang());
        assertEquals("Subject", msgEn.getSubject());
        assertEquals("Time to take the assessment", msgEn.getMessage());
        
        NotificationMessage msgFr = session.getMessages().get(1);
        assertEquals("fr", msgFr.getLang());
        assertEquals("Sujet", msgFr.getSubject());
        assertEquals("Il est temps de passer l'évaluation", msgFr.getMessage());
    }
    
}