package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.sagebionetworks.bridge.sdk.integration.Tests.PASSWORD;
import static org.sagebionetworks.bridge.sdk.integration.Tests.STUDY_ID_1;
import static org.sagebionetworks.bridge.util.IntegTestUtils.TEST_APP_ID;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.api.ParticipantsApi;
import org.sagebionetworks.bridge.rest.api.StudyParticipantsApi;
import org.sagebionetworks.bridge.rest.api.SubpopulationsApi;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
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
    private static TestUserHelper.TestUser studyCoordinator;
    private static StudyParticipantsApi participantsApi;
    private static SubpopulationsApi subpopApi;
    private static String subpopGuid2;

    private TestUserHelper.TestUser user;

    @BeforeClass
    public static void setup() throws Exception {
        admin = TestUserHelper.getSignedInAdmin();

        developer = TestUserHelper.createAndSignInUser(ParticipantIsConsentedTest.class, false,
                Role.DEVELOPER);
        subpopApi = developer.getClient(SubpopulationsApi.class);

        studyCoordinator = TestUserHelper.createAndSignInUser(ParticipantIsConsentedTest.class, false,
                Role.STUDY_COORDINATOR); // accesses study 1 through Sage Bionetworks
        
        participantsApi = studyCoordinator.getClient(StudyParticipantsApi.class);

        // Set up subpops:
        // 1. Default subpop prohibits data group sdk-int-2 and is required.
        // 2. Subpop2 any of data group sdk-int-2 and is optional.
        Subpopulation defaultSubpop = subpopApi.getSubpopulation(TEST_APP_ID).execute().body();
        Criteria defaultSubpopCriteria = new Criteria().addNoneOfGroupsItem(DATA_GROUP);
        defaultSubpop.setCriteria(defaultSubpopCriteria);
        subpopApi.updateSubpopulation(TEST_APP_ID, defaultSubpop).execute();

        Criteria criteria2 = new Criteria().addAllOfGroupsItem(DATA_GROUP);
        Subpopulation subpop2 = new Subpopulation().required(false).name("subpop2").criteria(criteria2)
                .addStudyIdsAssignedOnConsentItem(STUDY_ID_1);
        subpopGuid2 = subpopApi.createSubpopulation(subpop2).execute().body().getGuid();
    }

    @AfterClass
    public static void teardown() throws Exception {
        // Reset default criteria.
        Subpopulation defaultSubpop = subpopApi.getSubpopulation(TEST_APP_ID).execute().body();
        defaultSubpop.setCriteria(null);
        subpopApi.updateSubpopulation(TEST_APP_ID, defaultSubpop).execute();

        // Delete subpop2.
        if (subpopGuid2 != null) {
            admin.getClient(SubpopulationsApi.class).deleteSubpopulation(subpopGuid2, true).execute();
        }

        // Delete developer.
        if (developer != null) {
            developer.signOutAndDeleteUser();
        }

        // Delete study coordinator.
        if (studyCoordinator != null) {
            studyCoordinator.signOutAndDeleteUser();
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
        StudyParticipant participant = participantsApi.getStudyParticipantById(STUDY_ID_1, user.getUserId(), true)
                .execute().body();
        assertNull(participant.isConsented());

        // Sign in and get the participant again. Now that there's a request info, doesConsent=true.
        user.signInAgain();
        participant = participantsApi.getStudyParticipantById(STUDY_ID_1, user.getUserId(), true).execute().body();
        assertTrue(participant.isConsented());
    }

    @Test
    public void defaultRequiredNotSigned() throws Exception {
        user = TestUserHelper.createAndSignInUser(ParticipantIsConsentedTest.class, false);
        
        // A study coordinator cannot see this account because it hasn't consented into any study the 
        // researcher has access to.
        try {
            participantsApi.getStudyParticipantById(STUDY_ID_1, user.getUserId(), true).execute();
            fail("Should have thrown exception");
        } catch(EntityNotFoundException e) {
            assertTrue(e.getMessage().contains("Account not found."));
        }
        
        // However an admin can see the account, which is not consented
        StudyParticipant participant = admin.getClient(ParticipantsApi.class)
                .getParticipantById(user.getUserId(), true).execute().body();
        assertFalse(participant.isConsented());
    }

    @Test
    public void optionalSubpopNotSigned() throws Exception {
        // Create a user with a data group such that only the optional consent matches;
        // this person is consented. They should be visible to the researcher (but they
        // are not).
        String email = IntegTestUtils.makeEmail(ParticipantIsConsentedTest.class);
        SignUp signUp = new SignUp().appId(TEST_APP_ID).email(email).password(PASSWORD);
        signUp.addDataGroupsItem(DATA_GROUP);
        user = new TestUserHelper.Builder(ParticipantIsConsentedTest.class).withConsentUser(false)
                .withSignUp(signUp).createAndSignInUser();

        // This still fails...we no longer support "consent by default," ie without some
        // positive enrollment in a study. During migration, we will fix this by finding
        // implicitly consented accounts and adding enrollment records for them.
        try {
            participantsApi.getStudyParticipantById(STUDY_ID_1, user.getUserId(), true).execute();
            fail("Should have thrown exception");
        } catch(EntityNotFoundException e) {
            assertTrue(e.getMessage().contains("Account not found."));
        }
        
        // Consented, although nothing has been signed, because only consent is optional.
        StudyParticipant participant = admin.getClient(ParticipantsApi.class)
                .getParticipantById(user.getUserId(), true).execute().body();
        assertTrue(participant.isConsented());
    }
}
