package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
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

public class ActivityEventTest {
    private static final String EVENT_KEY = "event1";

    private static TestUserHelper.TestUser developer;
    private static TestUserHelper.TestUser researcher;
    private static TestUserHelper.TestUser user;
    private static ForConsentedUsersApi usersApi;
    private static ForResearchersApi researchersApi;

    @BeforeClass
    public static void beforeAll() throws Exception {
        researcher = TestUserHelper.createAndSignInUser(ActivityEventTest.class, true, Role.RESEARCHER);
        researchersApi = researcher.getClient(ForResearchersApi.class);

        user = TestUserHelper.createAndSignInUser(ActivityEventTest.class, true);
        usersApi = user.getClient(ForConsentedUsersApi.class);

        developer = TestUserHelper.createAndSignInUser(ActivityEventTest.class, false, Role.DEVELOPER);
        ForDevelopersApi developersApi = developer.getClient(ForDevelopersApi.class);

        Study study = developersApi.getUsersStudy().execute().body();

        Set<String> activityEventKeys = Sets.newHashSet(study.getActivityEventKeys());
        activityEventKeys.add(EVENT_KEY);
        study.setActivityEventKeys(Lists.newArrayList(activityEventKeys));

        developersApi.updateUsersStudy(study).execute();
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
        ActivityEventList activityEventList = usersApi.getActivityEvents().execute().body();
        List<ActivityEvent> activityEvents = activityEventList.getItems();

        StudyParticipant participant = usersApi.getUsersParticipantRecord().execute().body();

        DateTime timestamp = DateTime.now(DateTimeZone.UTC);
        usersApi.createCustomActivityEvent(
                new CustomActivityEventRequest()
                        .eventKey(EVENT_KEY)
                        .timestamp(timestamp))
                .execute();

        activityEventList = usersApi.getActivityEvents().execute().body();
        List<ActivityEvent> updatedActivityEvents = activityEventList.getItems();

        assertNotEquals(activityEvents, updatedActivityEvents);

        Optional<ActivityEvent> eventOptional = updatedActivityEvents.stream()
                .filter(e -> e.getEventId().contains(EVENT_KEY))
                .findAny();

        assertTrue(eventOptional.isPresent());

        ActivityEvent event = eventOptional.get();

        assertEquals(timestamp, event.getTimestamp());

        activityEventList = researchersApi.getActivityEvents(participant.getId()).execute().body();

        assertEquals(updatedActivityEvents, activityEventList.getItems());
    }
}
