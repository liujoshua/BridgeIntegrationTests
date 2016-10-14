package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.sagebionetworks.bridge.sdk.Roles;
import org.sagebionetworks.bridge.sdk.UserClient;
import org.sagebionetworks.bridge.sdk.integration.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.sdk.rest.model.StudyParticipant;

import java.util.List;
import java.util.Map;

/**
 * Test of the participant APIs that act on the currently authenticated user, which have replaced the 
 * user profile APIs as well as individual calls to update things like dataGroups, externalId, or 
 * sharing settings.
 *
 */
@Category(IntegrationSmokeTest.class)
public class UserParticipantTest {

    private static TestUser developer;

    @BeforeClass
    public static void before() {
        developer = TestUserHelper.createAndSignInUser(UserParticipantTest.class, true, Roles.DEVELOPER);
    }

    @AfterClass
    public static void after() {
        if (developer != null) {
            developer.signOutAndDeleteUser();    
        }
    }

    @Test
    public void canUpdateProfile() throws Exception {
        UserClient userClient = developer.getSession().getUserClient();

        Map<String,String> attributes = Maps.newHashMap();
        attributes.put("can_be_recontacted", "true");

        StudyParticipant participant = new StudyParticipant();

        participant.firstName("Davey").lastName("Crockett").attributes(attributes);

        userClient.saveStudyParticipant(participant);

        participant = userClient.getStudyParticipant();

        assertEquals(developer.getEmail(), participant.getEmail());
        assertEquals("First name updated", "Davey", participant.getFirstName());
        assertEquals("Last name updated", "Crockett", participant.getLastName());
        assertEquals("Attribute set", "true", participant.getAttributes().get("can_be_recontacted"));
    }

    @Test
    public void canAddExternalIdentifier() throws Exception {
        final UserClient userClient = developer.getSession().getUserClient();

        StudyParticipant participant = userClient.getStudyParticipant();
        StudyParticipant updated = new StudyParticipant();
        updated.externalId("ABC-123-XYZ");

        userClient.saveStudyParticipant(updated);

        participant = userClient.getStudyParticipant();
        assertEquals(developer.getEmail(), participant.getEmail());
        assertEquals("ABC-123-XYZ", participant.getExternalId());
    }

    @Test
    public void canUpdateDataGroups() throws Exception {
        List<String> dataGroups = Lists.newArrayList("sdk-int-1", "sdk-int-2");

        UserClient userClient = developer.getSession().getUserClient();

        StudyParticipant participant = new StudyParticipant();
        participant.dataGroups(dataGroups);

        userClient.saveStudyParticipant(participant);

        // session updated

        assertEquals(
                Sets.newHashSet(dataGroups),
                Sets.newHashSet(Lists.newArrayList(developer.getSession()
                                                            .getStudyParticipant()
                                                            .getDataGroups()))
        );

        // server updated
        participant = userClient.getStudyParticipant();
        assertEquals(Sets.newHashSet(dataGroups), Sets.newHashSet(participant.getDataGroups()));

        // now clear the values, it should be possible to remove them.
        participant = new StudyParticipant();

        participant.dataGroups(Lists.newArrayList());
        userClient.saveStudyParticipant(participant);

        // session updated
        assertEquals(Sets.<String>newHashSet(), developer.getSession().getStudyParticipant().getDataGroups());

        // server updated
        participant = userClient.getStudyParticipant();
        assertTrue(participant.getDataGroups().isEmpty());
    }

}
