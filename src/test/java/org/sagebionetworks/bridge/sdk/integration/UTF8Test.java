package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.sagebionetworks.bridge.sdk.ClientProvider;
import org.sagebionetworks.bridge.sdk.Session;
import org.sagebionetworks.bridge.sdk.StudyClient;
import org.sagebionetworks.bridge.sdk.UserClient;
import org.sagebionetworks.bridge.sdk.integration.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.sdk.models.accounts.SignInCredentials;
import org.sagebionetworks.bridge.sdk.models.studies.Study;
import org.sagebionetworks.bridge.sdk.rest.model.StudyParticipant;

@Category(IntegrationSmokeTest.class)
public class UTF8Test {
    @Test
    public void canSaveAndRetrieveDataStoredInDynamo() {
        String studyId = Tests.randomIdentifier(UTF8Test.class);
        String studyName = "☃지구상의　３대　극지라　불리는";
        StudyClient studyClient = TestUserHelper.getSignedInAdmin().getSession().getStudyClient();

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

        // create study
        studyClient.createStudy(study);

        try {
            // get study back and verify fields
            Study returnedStudy = studyClient.getStudy(studyId);
            assertEquals(studyId, returnedStudy.getIdentifier());
            assertEquals(studyName, returnedStudy.getName());
        } finally {
            // clean-up: delete study
            studyClient.deleteStudy(studyId);
        }
    }

    @Test
    public void canSaveAndRetrieveDataStoredInRedis() {
        TestUser testUser = TestUserHelper.createAndSignInUser(UTF8Test.class, true);
        try {
            UserClient userClient = testUser.getSession().getUserClient();

           StudyParticipant participant = new StudyParticipant();
            participant.firstName("☃");
            // I understand from the source of this text that it is actually UTF-16. It should still work.
            participant.lastName("지구상의　３대　극지라　불리는");

            userClient.saveStudyParticipant(participant);

            // Force a refresh of the Redis session cache.
            testUser.getSession().signOut();
            Session session = ClientProvider.signIn(new SignInCredentials(Tests.TEST_KEY, testUser.getEmail(), testUser.getPassword()));

            StudyParticipant sessionParticipant = session.getUserClient().getStudyParticipant();
            assertEquals("☃", sessionParticipant.getFirstName());
            assertEquals("지구상의　３대　극지라　불리는", sessionParticipant.getLastName());
        } finally {
            testUser.signOutAndDeleteUser();
        }
    }
}
