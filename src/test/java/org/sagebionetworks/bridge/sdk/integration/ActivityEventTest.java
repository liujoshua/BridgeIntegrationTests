package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.Period;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.rest.api.ForDevelopersApi;
import org.sagebionetworks.bridge.rest.api.ForResearchersApi;
import org.sagebionetworks.bridge.rest.model.ActivityEvent;
import org.sagebionetworks.bridge.rest.model.ActivityEventList;
import org.sagebionetworks.bridge.rest.model.CustomActivityEventRequest;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.Study;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.user.TestUserHelper;

public class ActivityEventTest {
    private static final String EVENT_KEY = "event1";
    private static final String TWO_WEEKS_AFTER_KEY = "2-weeks-after";
    private static final String TWO_WEEKS_AFTER_VALUE = "enrollment:P2W";

    private static TestUserHelper.TestUser developer;
    private static TestUserHelper.TestUser researcher;
    private static TestUserHelper.TestUser user;
    private static ForConsentedUsersApi usersApi;
    private static ForResearchersApi researchersApi;

    @BeforeClass
    public static void beforeAll() throws Exception {
        researcher = TestUserHelper.createAndSignInUser(ActivityEventTest.class, true, Role.RESEARCHER);
        researchersApi = researcher.getClient(ForResearchersApi.class);

        developer = TestUserHelper.createAndSignInUser(ActivityEventTest.class, false, Role.DEVELOPER);
        ForDevelopersApi developersApi = developer.getClient(ForDevelopersApi.class);

        Study study = developersApi.getUsersStudy().execute().body();
        boolean updateStudy = false;

        // Add custom event key, if not already present.
        if (!study.getActivityEventKeys().contains(EVENT_KEY)) {
            study.addActivityEventKeysItem(EVENT_KEY);
            updateStudy = true;
        }

        // Add automatic custom event.
        if (!study.getAutomaticCustomEvents().containsKey(TWO_WEEKS_AFTER_KEY)) {
            study.putAutomaticCustomEventsItem(TWO_WEEKS_AFTER_KEY, TWO_WEEKS_AFTER_VALUE);
            updateStudy = true;
        }

        if (updateStudy) {
            developersApi.updateUsersStudy(study).execute();
        }

        // Create user last, so the automatic custom events are created
        user = TestUserHelper.createAndSignInUser(ActivityEventTest.class, true);
        usersApi = user.getClient(ForConsentedUsersApi.class);
    }

    @AfterClass
    public static void deleteDeveloper() throws Exception {
        if (developer != null) {
            developer.signOutAndDeleteUser();
        }
    }

    @AfterClass
    public static void deleteResearcher() throws Exception {
        if (researcher != null) {
            researcher.signOutAndDeleteUser();
        }
    }

    @AfterClass
    public static void deleteUser() throws Exception {
        if (user != null) {
            user.signOutAndDeleteUser();
        }
    }

    @Test
    public void canCreateAndGetCustomEvent() throws IOException {
        // Setup
        ActivityEventList activityEventList = usersApi.getActivityEvents().execute().body();
        List<ActivityEvent> activityEvents = activityEventList.getItems();

        StudyParticipant participant = usersApi.getUsersParticipantRecord(false).execute().body();

        // Create custom event
        DateTime timestamp = DateTime.now(DateTimeZone.UTC);
        usersApi.createCustomActivityEvent(
                new CustomActivityEventRequest()
                        .eventKey(EVENT_KEY)
                        .timestamp(timestamp))
                .execute();

        // Verify created event
        activityEventList = usersApi.getActivityEvents().execute().body();
        List<ActivityEvent> updatedActivityEvents = activityEventList.getItems();
        assertNotEquals(activityEvents, updatedActivityEvents);

        String expectedEventKey = "custom:" + EVENT_KEY;
        Optional<ActivityEvent> eventOptional = updatedActivityEvents.stream()
                .filter(e -> e.getEventId().equals(expectedEventKey))
                .findAny();

        assertTrue(eventOptional.isPresent());
        ActivityEvent event = eventOptional.get();
        assertEquals(timestamp, event.getTimestamp());

        // Verify researcher's view of created event
        activityEventList = researchersApi.getActivityEvents(participant.getId()).execute().body();
        assertEquals(updatedActivityEvents, activityEventList.getItems());
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
}
