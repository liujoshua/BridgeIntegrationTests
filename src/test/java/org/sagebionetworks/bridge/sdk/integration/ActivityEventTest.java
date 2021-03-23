package org.sagebionetworks.bridge.sdk.integration;

import static org.joda.time.DateTimeZone.UTC;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.sagebionetworks.bridge.rest.model.ActivityEventUpdateType.MUTABLE;
import static org.sagebionetworks.bridge.sdk.integration.Tests.STUDY_ID_1;
import static org.sagebionetworks.bridge.sdk.integration.Tests.STUDY_ID_2;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.google.common.collect.ImmutableMap;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.Period;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.rest.api.ForDevelopersApi;
import org.sagebionetworks.bridge.rest.api.ForResearchersApi;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.model.ActivityEvent;
import org.sagebionetworks.bridge.rest.model.ActivityEventList;
import org.sagebionetworks.bridge.rest.model.ActivityEventUpdateType;
import org.sagebionetworks.bridge.rest.model.App;
import org.sagebionetworks.bridge.rest.model.CustomActivityEventRequest;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.user.TestUserHelper;

public class ActivityEventTest {
    private static final String EVENT_KEY1 = "event1";
    private static final String EVENT_KEY2 = "event2";
    private static final String TWO_WEEKS_AFTER_KEY = "2-weeks-after";
    private static final String TWO_WEEKS_AFTER_VALUE = "enrollment:P2W";

    private static TestUserHelper.TestUser developer;
    private static TestUserHelper.TestUser researcher;
    private static TestUserHelper.TestUser user;
    private static ForConsentedUsersApi usersApi;
    private static ForResearchersApi researchersApi;

    @Before
    public void beforeAll() throws Exception {
        researcher = TestUserHelper.createAndSignInUser(ActivityEventTest.class, true, Role.RESEARCHER);
        researchersApi = researcher.getClient(ForResearchersApi.class);

        developer = TestUserHelper.createAndSignInUser(ActivityEventTest.class, false, Role.DEVELOPER);
        ForDevelopersApi developersApi = developer.getClient(ForDevelopersApi.class);

        App app = developersApi.getUsersApp().execute().body();
        boolean updateApp = false;

        // Add custom event key, if not already present.
        if (!app.getCustomEvents().keySet().contains(EVENT_KEY1) || 
                app.getCustomEvents().get(EVENT_KEY1) != MUTABLE) {
            app.setCustomEvents(ImmutableMap.of(EVENT_KEY1, MUTABLE));
            updateApp = true;
        }
        if (!app.getCustomEvents().keySet().contains(EVENT_KEY2) ||
                app.getCustomEvents().get(EVENT_KEY2) != MUTABLE) {
            app.setCustomEvents(ImmutableMap.of(EVENT_KEY2, MUTABLE));
            updateApp = true;
        }

        // Add automatic custom event.
        if (!app.getAutomaticCustomEvents().containsKey(TWO_WEEKS_AFTER_KEY)) {
            app.putAutomaticCustomEventsItem(TWO_WEEKS_AFTER_KEY, TWO_WEEKS_AFTER_VALUE);
            updateApp = true;
        }

        if (updateApp) {
            developersApi.updateUsersApp(app).execute();
        }

        // Create user last, so the automatic custom events are created
        user = TestUserHelper.createAndSignInUser(ActivityEventTest.class, true);
        usersApi = user.getClient(ForConsentedUsersApi.class);
    }

    @After
    public void deleteDeveloper() throws Exception {
        if (developer != null) {
            developer.signOutAndDeleteUser();
        }
    }

    @After
    public void deleteResearcher() throws Exception {
        if (researcher != null) {
            researcher.signOutAndDeleteUser();
        }
    }

    @After
    public void deleteUser() throws Exception {
        if (user != null) {
            user.signOutAndDeleteUser();
        }
    }

    @Test
    public void canCreateCreatedOnAndStudyStartDate() throws IOException {
        // Get activity events and convert to map for ease of use
        List<ActivityEvent> activityEventList = usersApi.getActivityEvents().execute().body().getItems();
        Map<String, ActivityEvent> activityEventMap = activityEventList.stream().collect(
                Collectors.toMap(ActivityEvent::getEventId, e -> e));
        
        // Verify enrollment events exist
        ActivityEvent enrollmentEvent = activityEventMap.get("enrollment");
        assertNotNull(enrollmentEvent);
        DateTime enrollmentTime = enrollmentEvent.getTimestamp();
        assertNotNull(enrollmentTime);
        
        StudyParticipant participant = usersApi.getUsersParticipantRecord(false).execute().body();
        
        // Verify enrollment events exist
        ActivityEvent createdOnEvent = activityEventMap.get("created_on");
        assertNotNull(createdOnEvent);
        DateTime createdOnTime = createdOnEvent.getTimestamp();
        assertEquals(createdOnTime, participant.getCreatedOn());
        
        // Verify enrollment events exist
        ActivityEvent studyStateDateEvent = activityEventMap.get("study_start_date");
        assertNotNull(studyStateDateEvent);
        DateTime studyStateDateTime = studyStateDateEvent.getTimestamp();
        assertEquals(studyStateDateTime, enrollmentTime);
    }

    @Test
    public void canCrudCustomEvent() throws IOException {
        // Setup
        ActivityEventList activityEventList = usersApi.getActivityEvents().execute().body();
        List<ActivityEvent> activityEvents = activityEventList.getItems();

        StudyParticipant participant = usersApi.getUsersParticipantRecord(false).execute().body();

        // Create custom event
        DateTime timestamp = DateTime.now(DateTimeZone.UTC);
        usersApi.createCustomActivityEvent(
                new CustomActivityEventRequest().eventId(EVENT_KEY1).timestamp(timestamp)).execute();

        // Verify created event
        activityEventList = usersApi.getActivityEvents().execute().body();
        List<ActivityEvent> updatedActivityEvents = activityEventList.getItems();
        assertNotEquals(activityEvents, updatedActivityEvents);

        String expectedEventKey = "custom:" + EVENT_KEY1;
        Optional<ActivityEvent> eventOptional = updatedActivityEvents.stream()
                .filter(e -> e.getEventId().equals(expectedEventKey))
                .findAny();

        assertTrue(eventOptional.isPresent());
        ActivityEvent event = eventOptional.get();
        assertEquals(timestamp, event.getTimestamp());

        // Verify researcher's view of created event
        activityEventList = researchersApi.getActivityEventsForParticipant(participant.getId()).execute().body();
        assertEquals(updatedActivityEvents, activityEventList.getItems());
        
        // delete the event
        usersApi.deleteCustomActivityEvent(EVENT_KEY1).execute();
        activityEventList = usersApi.getActivityEvents().execute().body();
        assertFalse(activityEventList.getItems().stream().map(ActivityEvent::getEventId)
                .collect(Collectors.toSet()).contains(expectedEventKey));
        assertFalse(activityEventList.getItems().stream().map(ActivityEvent::getEventId)
                .collect(Collectors.toSet()).contains(EVENT_KEY1));
    }

    @Test
    public void automaticCustomEvents() throws Exception {
        // Get activity events and convert to map for ease of use
        List<ActivityEvent> activityEventList = usersApi.getActivityEvents().execute().body().getItems();
        Map<String, ActivityEvent> activityEventMap = activityEventList.stream().collect(
                Collectors.toMap(ActivityEvent::getEventId, e -> e));

        // Verify enrollment events exist
        ActivityEvent enrollmentEvent = activityEventMap.get("enrollment");
        assertNotNull(enrollmentEvent);
        DateTime enrollmentTime = enrollmentEvent.getTimestamp();
        assertNotNull(enrollmentTime);

        // Verify custom event exists and that it's 2 weeks after enrollment
        ActivityEvent twoWeeksAfterEvent = activityEventMap.get("custom:" + TWO_WEEKS_AFTER_KEY);
        assertNotNull(twoWeeksAfterEvent);
        DateTime twoWeeksAfterTime = twoWeeksAfterEvent.getTimestamp();
        // This can fail when you're near the time zone change to DST. Add one hour to overshoot 
        // and compensate for the time zone change.
        Period twoWeeksAfterPeriod = new Period(enrollmentTime, twoWeeksAfterTime.plusHours(1));
        assertEquals(2, twoWeeksAfterPeriod.getWeeks());
    }
    
    @Test
    public void researcherCanSubmitCustomEvents() throws Exception {
        ForResearchersApi researchersApi = researcher.getClient(ForResearchersApi.class);
        
        // it's stored as a long since epoch, so timezone must be UTC to match
        DateTime timestamp1 = DateTime.now(DateTimeZone.UTC).minusHours(4);
        DateTime timestamp2 = DateTime.now(DateTimeZone.UTC);
        
        CustomActivityEventRequest request = new CustomActivityEventRequest();
        request.setEventId(EVENT_KEY1);
        request.setTimestamp(timestamp1);
        
        researchersApi.createActivityEventForParticipant(user.getUserId(), request).execute();
        
        ActivityEventList list = researchersApi.getActivityEventsForParticipant(user.getUserId()).execute().body();
        Optional<ActivityEvent> optional = list.getItems().stream()
                .filter((evt) -> evt.getEventId().equals("custom:"+EVENT_KEY1)).findAny();
        assertTrue(optional.isPresent());
        assertEquals(timestamp1.toString(), optional.get().getTimestamp().toString());
        
        request.setTimestamp(timestamp2);
        researchersApi.createActivityEventForParticipant(user.getUserId(), request).execute();
        
        list = researchersApi.getActivityEventsForParticipant(user.getUserId()).execute().body();
        optional = list.getItems().stream()
                .filter((evt) -> evt.getEventId().equals("custom:"+EVENT_KEY1)).findAny();
        assertTrue(optional.isPresent());
        assertEquals(timestamp2.toString(), optional.get().getTimestamp().toString());
    }
    
    @Test
    public void createActivityEventScopedForStudy() throws Exception {
        // Because this is set first, it is immutable and will not be changed by the study-scoped value.
        // Both with exist side-by-side
        DateTime globalTimestamp = DateTime.now(UTC).minusDays(2);
        CustomActivityEventRequest globalRequest = new CustomActivityEventRequest()
                .eventId(EVENT_KEY1).timestamp(globalTimestamp);
        researchersApi.createActivityEventForParticipant(user.getUserId(), globalRequest).execute();
        
        DateTime studyScopedTimestamp = DateTime.now(UTC);  
        CustomActivityEventRequest studyScopedRequest = new CustomActivityEventRequest()
            .eventId(EVENT_KEY1).timestamp(studyScopedTimestamp);
        researchersApi.createStudyParticipantActivityEvent(STUDY_ID_1, user.getUserId(), studyScopedRequest).execute();
        
        ActivityEventList globalList = researchersApi.getActivityEventsForParticipant(user.getUserId()).execute().body();
        ActivityEvent globalEvent = findEventByKey(globalList, "custom:"+EVENT_KEY1);
        assertEquals(globalTimestamp, globalEvent.getTimestamp());
        
        ActivityEventList scopedList = researchersApi.getStudyParticipantActivityEvents(STUDY_ID_1, user.getUserId()).execute().body();
        ActivityEvent scopedEvent = findEventByKey(scopedList, "custom:"+EVENT_KEY1);
        assertEquals(studyScopedTimestamp, scopedEvent.getTimestamp());

        // User can also retrieve these two different events.
        scopedList = usersApi.getActivityEventsForSelf(STUDY_ID_1).execute().body();
        scopedEvent = findEventByKey(scopedList, "custom:"+EVENT_KEY1);
        assertEquals(studyScopedTimestamp, scopedEvent.getTimestamp());
        
        globalList= usersApi.getActivityEvents().execute().body();
        globalEvent = findEventByKey(globalList, "custom:"+EVENT_KEY1);
        assertEquals(globalTimestamp, globalEvent.getTimestamp());
        
        // Verify that a user can create a custom event, and that it is visible to researchers
        globalTimestamp = globalTimestamp.minusWeeks(2);
        globalRequest = new CustomActivityEventRequest().eventId(EVENT_KEY2).timestamp(globalTimestamp);
        usersApi.createCustomActivityEvent(globalRequest).execute();
        
        studyScopedTimestamp = studyScopedTimestamp.minusWeeks(2);
        studyScopedRequest = new CustomActivityEventRequest().eventId(EVENT_KEY2).timestamp(studyScopedTimestamp);
        usersApi.createActivityEventForSelf(STUDY_ID_1, studyScopedRequest).execute();

        // user can see these events
        scopedList = usersApi.getActivityEventsForSelf(STUDY_ID_1).execute().body();
        scopedEvent = findEventByKey(scopedList, "custom:"+EVENT_KEY2);
        assertEquals(studyScopedTimestamp, scopedEvent.getTimestamp());
        
        globalList= usersApi.getActivityEvents().execute().body();
        globalEvent = findEventByKey(globalList, "custom:"+EVENT_KEY2);
        assertEquals(globalTimestamp, globalEvent.getTimestamp());

        // the researcher can see these events
        globalList = researchersApi.getActivityEventsForParticipant(user.getUserId()).execute().body();
        globalEvent = findEventByKey(globalList, "custom:"+EVENT_KEY2);
        assertEquals(globalTimestamp, globalEvent.getTimestamp());
        
        scopedList = researchersApi.getStudyParticipantActivityEvents(STUDY_ID_1, user.getUserId()).execute().body();
        scopedEvent = findEventByKey(scopedList, "custom:"+EVENT_KEY2);
        assertEquals(studyScopedTimestamp, scopedEvent.getTimestamp());
    }
    
    @Test
    public void globalEventsDoNotCreateStudyVersions() throws Exception {
        DateTime globalTimestamp = DateTime.now(UTC).minusDays(2);
        CustomActivityEventRequest globalRequest = new CustomActivityEventRequest()
                .eventId(EVENT_KEY1).timestamp(globalTimestamp);
        researchersApi.createActivityEventForParticipant(user.getUserId(), globalRequest).execute();

        ActivityEventList scopedList = researchersApi.getStudyParticipantActivityEvents(STUDY_ID_1, user.getUserId()).execute().body();
        assertNull(findEventByKey(scopedList, "custom:"+EVENT_KEY1));

        // The user has not been enrolled in this study so there should be no events.
        try {
            researchersApi.getStudyParticipantActivityEvents(STUDY_ID_2, user.getUserId()).execute().body();
            fail("Should have thrown an exception.");
        } catch(EntityNotFoundException e) {
            
        }
    }
    
    private ActivityEvent findEventByKey(ActivityEventList list, String key) {
        for (ActivityEvent event : list.getItems()) {
            if (event.getEventId().equals(key)) {
                return event;
            }
        }
        return null;
    }
}
