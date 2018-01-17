package org.sagebionetworks.bridge.sdk.integration;

import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.sagebionetworks.bridge.rest.api.AuthenticationApi;
import org.sagebionetworks.bridge.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.rest.api.StudiesApi;
import org.sagebionetworks.bridge.rest.model.Study;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.rest.model.UserSessionInfo;
import org.sagebionetworks.bridge.sdk.integration.TestUserHelper.TestUser;

import static org.junit.Assert.assertEquals;

@Category(IntegrationSmokeTest.class)
public class UTF8Test {
    @Test
    public void canSaveAndRetrieveDataStoredInDynamo() throws Exception {
        String studyId = Tests.randomIdentifier(UTF8Test.class);
        String studyName = "☃지구상의　３대　극지라　불리는";
        StudiesApi studiesApi = TestUserHelper.getSignedInAdmin().getClient(StudiesApi.class);

        // make minimal study
        Study study = new Study();
        study.setIdentifier(studyId);
        study.setName(studyName);
        study.setSponsorName(studyName);
        study.setTechnicalEmail("bridge-testing+technical@sagebase.org");
        study.setSupportEmail("bridge-testing+support@sagebase.org");
        study.setConsentNotificationEmail("bridge-testing+consent@sagebase.org");
        study.setResetPasswordTemplate(Tests.TEST_RESET_PASSWORD_TEMPLATE);
        study.setVerifyEmailTemplate(Tests.TEST_VERIFY_EMAIL_TEMPLATE);
        study.setEmailVerificationEnabled(true);

        // create study
        studiesApi.createStudy(study).execute();

        try {
            // get study back and verify fields
            Study returnedStudy = studiesApi.getStudy(studyId).execute().body();
            assertEquals(studyId, returnedStudy.getIdentifier());
            assertEquals(studyName, returnedStudy.getName());
        } finally {
            // clean-up: delete study
            studiesApi.deleteStudy(studyId, true).execute();
        }
    }

    @Test
    public void canSaveAndRetrieveDataStoredInRedis() throws Exception {
        TestUser testUser = TestUserHelper.createAndSignInUser(UTF8Test.class, true);
        try {
            ForConsentedUsersApi usersApi = testUser.getClient(ForConsentedUsersApi.class);

            StudyParticipant participant = new StudyParticipant();
            participant.setFirstName("☃");
            participant.setLastName("지구상의　３대　극지라　불리는");

            usersApi.updateUsersParticipantRecord(participant).execute();

            // Force a refresh of the Redis session cache.
            AuthenticationApi authApi = testUser.getClient(AuthenticationApi.class);
            authApi.signOut().execute();
            UserSessionInfo session = authApi.signInV4(testUser.getSignIn()).execute().body();

            assertEquals("☃", session.getFirstName());
            assertEquals("지구상의　３대　극지라　불리는", session.getLastName());
        } finally {
            testUser.signOutAndDeleteUser();
        }
    }
}
