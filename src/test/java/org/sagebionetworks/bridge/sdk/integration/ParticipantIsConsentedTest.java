package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.sagebionetworks.bridge.util.IntegTestUtils.STUDY_ID;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.api.ParticipantsApi;
import org.sagebionetworks.bridge.rest.api.SubpopulationsApi;
import org.sagebionetworks.bridge.rest.model.Criteria;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.SignUp;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.rest.model.Subpopulation;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.util.IntegTestUtils;

public class ParticipantIsConsentedTest {
    private static final String DATA_GROUP = "sdk-int-2";

    private static TestUserHelper.TestUser admin;
    private static TestUserHelper.TestUser developer;
    private static ParticipantsApi participantsApi;
    private static TestUserHelper.TestUser researcher;
    private static SubpopulationsApi subpopApi;
    private static String subpopGuid2;

    private TestUserHelper.TestUser user;

    @BeforeClass
    public static void setup() throws Exception {
        admin = TestUserHelper.getSignedInAdmin();

        developer = TestUserHelper.createAndSignInUser(ParticipantIsConsentedTest.class, false,
                Role.DEVELOPER);
        subpopApi = developer.getClient(SubpopulationsApi.class);

        researcher = TestUserHelper.createAndSignInUser(ParticipantIsConsentedTest.class, false,
                Role.RESEARCHER);
        participantsApi = researcher.getClient(ParticipantsApi.class);

        // Set up subpops:
        // 1. Default subpop prohibits data group sdk-int-2 and is required.
        // 2. Subpop2 any of data group sdk-int-2 and is optional.
        Subpopulation defaultSubpop = subpopApi.getSubpopulation(IntegTestUtils.STUDY_ID).execute().body();
        Criteria defaultSubpopCriteria = new Criteria().addNoneOfGroupsItem(DATA_GROUP);
        defaultSubpop.setCriteria(defaultSubpopCriteria);
        subpopApi.updateSubpopulation(IntegTestUtils.STUDY_ID, defaultSubpop).execute();

        Criteria criteria2 = new Criteria().addAllOfGroupsItem(DATA_GROUP);
        Subpopulation subpop2 = new Subpopulation().name("subpop2").criteria(criteria2);
        subpopGuid2 = subpopApi.createSubpopulation(subpop2).execute().body().getGuid();
    }

    @AfterClass
    public static void teardown() throws Exception {
        // Reset default criteria.
        Subpopulation defaultSubpop = subpopApi.getSubpopulation(IntegTestUtils.STUDY_ID).execute().body();
        defaultSubpop.setCriteria(null);
        subpopApi.updateSubpopulation(IntegTestUtils.STUDY_ID, defaultSubpop).execute();

        // Delete subpop2.
        if (subpopGuid2 != null) {
            admin.getClient(SubpopulationsApi.class).deleteSubpopulation(subpopGuid2, true).execute();
        }

        // Delete developer.
        if (developer != null) {
            developer.signOutAndDeleteUser();
        }

        // Delete researcher.
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
    public void neverSignedIn() throws Exception {
        // Never signed in, which means no request info, which means null doesConsent.
        user = new TestUserHelper.Builder(ParticipantIsConsentedTest.class).withConsentUser(true).createUser();
        StudyParticipant participant = participantsApi.getParticipantById(user.getUserId(), true).execute()
                .body();
        assertNull(participant.isConsented());

        // Sign in and get the participant again. Now that there's a request info, doesConsent=true.
        user.signInAgain();
        participant = participantsApi.getParticipantById(user.getUserId(), true).execute().body();
        assertTrue(participant.isConsented());
    }

    @Test
    public void defaultRequiredNotSigned() throws Exception {
        user = TestUserHelper.createAndSignInUser(ParticipantIsConsentedTest.class, false);
        StudyParticipant participant = participantsApi.getParticipantById(user.getUserId(), true).execute()
                .body();
        assertFalse(participant.isConsented());
    }

    @Test
    public void optionalSubpopNotSigned() throws Exception {
        SignUp signUp = new SignUp().appId(STUDY_ID)
                .email(IntegTestUtils.makeEmail(ParticipantIsConsentedTest.class)).password(Tests.PASSWORD);
        signUp.addDataGroupsItem(DATA_GROUP);
        user = new TestUserHelper.Builder(ParticipantIsConsentedTest.class).withConsentUser(false).withSignUp(signUp)
                .createAndSignInUser();

        StudyParticipant participant = participantsApi.getParticipantById(user.getUserId(), true).execute()
                .body();
        assertTrue(participant.isConsented());
    }
}
