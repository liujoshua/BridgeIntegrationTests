package org.sagebionetworks.bridge.sdk.integration;

import static java.util.stream.Collectors.toSet;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.sagebionetworks.bridge.rest.model.Role.RESEARCHER;
import static org.sagebionetworks.bridge.rest.model.Role.STUDY_COORDINATOR;
import static org.sagebionetworks.bridge.rest.model.SharingScope.ALL_QUALIFIED_RESEARCHERS;
import static org.sagebionetworks.bridge.rest.model.SharingScope.NO_SHARING;
import static org.sagebionetworks.bridge.sdk.integration.Tests.PASSWORD;
import static org.sagebionetworks.bridge.sdk.integration.Tests.STUDY_ID_1;
import static org.sagebionetworks.bridge.sdk.integration.Tests.STUDY_ID_2;
import static org.sagebionetworks.bridge.sdk.integration.Tests.SYNAPSE_USER_ID;
import static org.sagebionetworks.bridge.util.IntegTestUtils.TEST_APP_ID;

import java.util.List;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.joda.time.DateTime;
import org.joda.time.LocalDate;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.rest.api.ForResearchersApi;
import org.sagebionetworks.bridge.rest.api.ForStudyCoordinatorsApi;
import org.sagebionetworks.bridge.rest.exceptions.BadRequestException;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.exceptions.UnauthorizedException;
import org.sagebionetworks.bridge.rest.model.AccountSummaryList;
import org.sagebionetworks.bridge.rest.model.AccountSummarySearch;
import org.sagebionetworks.bridge.rest.model.ActivityEvent;
import org.sagebionetworks.bridge.rest.model.ActivityEventList;
import org.sagebionetworks.bridge.rest.model.ConsentSignature;
import org.sagebionetworks.bridge.rest.model.Enrollment;
import org.sagebionetworks.bridge.rest.model.EnrollmentDetail;
import org.sagebionetworks.bridge.rest.model.EnrollmentDetailList;
import org.sagebionetworks.bridge.rest.model.ExternalIdentifierList;
import org.sagebionetworks.bridge.rest.model.IdentifierHolder;
import org.sagebionetworks.bridge.rest.model.NotificationMessage;
import org.sagebionetworks.bridge.rest.model.NotificationRegistrationList;
import org.sagebionetworks.bridge.rest.model.RequestInfo;
import org.sagebionetworks.bridge.rest.model.SharingScope;
import org.sagebionetworks.bridge.rest.model.SignIn;
import org.sagebionetworks.bridge.rest.model.SignUp;
import org.sagebionetworks.bridge.rest.model.Study;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.rest.model.SubpopulationList;
import org.sagebionetworks.bridge.rest.model.UploadList;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.util.IntegTestUtils;

public class ForStudyCoordinatorsTest {
    
    private static final List<String> TEST_USER_LIST = ImmutableList.of("test_user");
    
    TestUser studyCoordinator;
    TestUser researcher;
    TestUser user;
    ForStudyCoordinatorsApi coordApi;
    IdentifierHolder id;
    
    @Before
    public void beforeMethod() throws Exception {
        // In Sage Bionetworks, so has access to study1
        studyCoordinator = TestUserHelper.createAndSignInUser(ForStudyCoordinatorsTest.class, false, STUDY_COORDINATOR);
        coordApi = studyCoordinator.getClient(ForStudyCoordinatorsApi.class);
    }

    @After
    public void afterMethod() throws Exception {
        if (id != null) {
            coordApi.deleteTestStudyParticipant(STUDY_ID_1, id.getIdentifier()).execute();
        }
        if (user != null) {
            user.signOutAndDeleteUser();
        }
        if (studyCoordinator != null) {
            studyCoordinator.signOutAndDeleteUser();
        }
        if (researcher != null) {
            researcher.signOutAndDeleteUser();
        }
    }
    
    @Test
    public void studyCoordinatorHasAccessToSomeKeyApis() throws Exception {
        Study study = coordApi.getStudy(STUDY_ID_1).execute().body();
        assertEquals(STUDY_ID_1, study.getIdentifier());
        
        SubpopulationList list = coordApi.getSubpopulations(false).execute().body();
        assertTrue(list.getItems().stream()
                .anyMatch(subpop -> subpop.getStudyIdsAssignedOnConsent().contains(STUDY_ID_1)));
    }
    
    @Test
    public void crudStudyParticipantWithoutExternalId() throws Exception {
        String email = IntegTestUtils.makeEmail(ForStudyCoordinatorsTest.class);
        SignUp signUp = new SignUp()
                .email(email)
                .password(PASSWORD)
                .appId(TEST_APP_ID)
                .dataGroups(TEST_USER_LIST)
                .sharingScope(ALL_QUALIFIED_RESEARCHERS);
        id = coordApi.createStudyParticipant(STUDY_ID_1, signUp).execute().body();
        String userId = id.getIdentifier();
        
        StudyParticipant participant = coordApi.getStudyParticipantById(
                STUDY_ID_1, userId, true).execute().body();
        assertEquals(userId, participant.getId());
        assertEquals(email, participant.getEmail());
        assertEquals(TEST_USER_LIST, participant.getDataGroups());
        // This person is enrolled because of the way we made them. 
        assertEquals(ImmutableList.of(STUDY_ID_1), participant.getStudyIds());
        
        // Their enrollment record indicates they have not consented, however, which we can verify
        EnrollmentDetailList list = coordApi.getStudyParticipantEnrollments(STUDY_ID_1, userId).execute().body();
        assertEquals(1, list.getItems().size());
        assertTrue(list.getItems().get(0).isConsentRequired());
        
        // get in page
        AccountSummarySearch search = new AccountSummarySearch().emailFilter(email); 
        AccountSummaryList summaries = coordApi.getStudyParticipants(STUDY_ID_1, search).execute().body();
        assertTrue(summaries.getItems().stream().anyMatch(sum -> sum.getId().equals(userId)));
        
        try { // this won't work
            coordApi.getStudyParticipantEnrollments(STUDY_ID_2, userId).execute();
            fail("Should have thrown exception");
        } catch(EntityNotFoundException e) {
            assertEquals("Account not found.", e.getMessage());
        }
        
        // With test accounts, the account is returned (see EnrollmentTest for more tests)
        EnrollmentDetailList studyEnrollments = coordApi.getEnrollments(
                STUDY_ID_1, "enrolled", true, null, null).execute().body();
        assertTrue(studyEnrollments.getItems().stream()
                .anyMatch(en -> en.getParticipant().getIdentifier().equals(userId)));
        
        // Just verify these all work, though there's no data
        ActivityEventList aeList = coordApi.getStudyParticipantActivityEvents(STUDY_ID_1, userId).execute().body();
        assertEquals(ImmutableSet.of("study_start_date", "created_on"), 
                aeList.getItems().stream().map(ActivityEvent::getEventId).collect(toSet()));
        
        NotificationRegistrationList nrList = coordApi.getStudyParticipantNotificationRegistrations(STUDY_ID_1, userId).execute().body();
        assertTrue(nrList.getItems().isEmpty());
        
        UploadList uList = coordApi.getStudyParticipantUploads(
                STUDY_ID_1, userId, DateTime.now().minusDays(3), DateTime.now(), null, null).execute().body();
        assertTrue(uList.getItems().isEmpty());

        RequestInfo info = coordApi.getStudyParticipantRequestInfo(STUDY_ID_1, userId).execute().body();
        assertNotNull(info);
        assertEquals("UTC", info.getTimeZone());
        
        // Again we just want to see that these don't fail...we can't verify
        coordApi.sendStudyParticipantEmailVerification(STUDY_ID_1, userId).execute();
        coordApi.sendStudyParticipantPhoneVerification(STUDY_ID_1, userId).execute();
        
        try {
            NotificationMessage message = new NotificationMessage().subject("subject").message("message");
            coordApi.sendStudyParticipantNotification(STUDY_ID_1, userId, message).execute();
            fail("Should have thrown exception");
        } catch(BadRequestException e) {
            assertEquals("Participant has not registered to receive push notifications.", e.getMessage());
        }
        coordApi.sendStudyParticipantResetPassword(STUDY_ID_1, userId).execute();
        
        coordApi.signOutStudyParticipant(STUDY_ID_1, userId, true).execute();
        
        // This won't work or change anything because the account hasn't consented (nor can it, since
        // we haven't verified it). But we can verify it works.
        coordApi.withdrawParticipant(STUDY_ID_1, userId, "A withdrawal note.").execute();
        
        participant.setFirstName("New First Name");
        participant.setLastName("New Last Name");
        coordApi.updateStudyParticipant(STUDY_ID_1, userId, participant).execute();
        
        participant = coordApi.getStudyParticipantById(STUDY_ID_1, userId, true).execute().body();
        assertEquals("New First Name", participant.getFirstName());
        assertEquals("New Last Name", participant.getLastName());
        
        coordApi.deleteTestStudyParticipant(STUDY_ID_1, userId).execute();
        id = null;
        
        try {
            coordApi.getStudyParticipantById(STUDY_ID_1, userId, true).execute();
            fail("Should have thrown exception");
        } catch(EntityNotFoundException e) {
            assertEquals("Account not found.", e.getMessage());
        }
    }
    
    @Test
    public void resendStudyParticipantConsentAgreement() throws Exception {
        user = TestUserHelper.createAndSignInUser(ForStudyCoordinatorsTest.class, false);
        
        try {
            coordApi.resendStudyParticipantConsentAgreement(STUDY_ID_1, user.getUserId(), TEST_APP_ID).execute();
            fail("Should have thrown exception");
        } catch(EntityNotFoundException e) {
            assertEquals("Account not found.", e.getMessage());
        }
        // This is the default consent which enrolls the user in study1.
        ConsentSignature sig = new ConsentSignature().name("Test User").scope(NO_SHARING)
                .birthdate(LocalDate.parse("2000-01-01"));
        user.getClient(ForConsentedUsersApi.class).createConsentSignature("api", sig).execute();
        
        coordApi.resendStudyParticipantConsentAgreement(STUDY_ID_1, user.getUserId(), TEST_APP_ID).execute();
    }
    
    @Test
    public void createAndThenEnrollStudyParticipant() throws Exception {
        researcher = TestUserHelper.createAndSignInUser(ForStudyCoordinatorsTest.class, false, RESEARCHER);
        
        String email = IntegTestUtils.makeEmail(ForStudyCoordinatorsTest.class);
        SignUp signUp = new SignUp()
                .email(email)
                .password(PASSWORD)
                .appId(TEST_APP_ID)
                .dataGroups(TEST_USER_LIST)
                .sharingScope(ALL_QUALIFIED_RESEARCHERS);
        id = researcher.getClient(ForResearchersApi.class).createParticipant(signUp).execute().body();
        String userId = id.getIdentifier();

        try {
            coordApi.getStudyParticipantById(STUDY_ID_1, userId, true).execute();
            fail("Should have thrown exception");
        } catch(EntityNotFoundException e) {
            
        }
        Enrollment enrollment = coordApi.enrollParticipant(STUDY_ID_1, new Enrollment().userId(userId)).execute().body();
        assertEquals(studyCoordinator.getUserId(), enrollment.getEnrolledBy());
        assertEquals(userId, enrollment.getUserId());
    }

    @Test
    public void crudStudyParticipantWithExternalId() throws Exception {
        String email = IntegTestUtils.makeEmail(ForStudyCoordinatorsTest.class);
        String externalId = Tests.randomIdentifier(ForStudyCoordinatorsTest.class);
        // Enroll this person in study2.
        SignUp signUp = new SignUp()
                .email(email)
                .appId(TEST_APP_ID)
                .dataGroups(TEST_USER_LIST)
                .externalIds(ImmutableMap.of(STUDY_ID_1, externalId))
                .sharingScope(ALL_QUALIFIED_RESEARCHERS);
        id = coordApi.createStudyParticipant(STUDY_ID_1, signUp).execute().body();
        
        StudyParticipant participant = coordApi.getStudyParticipantById(
                STUDY_ID_1, "externalid:"+externalId, false).execute().body();
        assertEquals(id.getIdentifier(), participant.getId());
        assertEquals(externalId, participant.getExternalIds().get(STUDY_ID_1));
        
        ExternalIdentifierList list = coordApi.getExternalIdsForStudy(STUDY_ID_1, null, null, externalId).execute().body();
        assertTrue(list.getItems().stream()
                .anyMatch(en -> en.getIdentifier().equals(externalId)));
    }
    
    @Test
    public void deleteTestStudyParticipant() throws Exception {
        // Enrolled in study 1 due to consent.
        user = TestUserHelper.createAndSignInUser(ForStudyCoordinatorsTest.class, true);
        
        // User is not a test user so this fails
        try {
            coordApi.deleteTestStudyParticipant(STUDY_ID_1, user.getUserId()).execute();
            fail("Should have thrown an exception");
        } catch(UnauthorizedException e) {
        }
        
        StudyParticipant participant = coordApi.getStudyParticipantById(STUDY_ID_1, user.getUserId(), false).execute().body();
        participant.setDataGroups(ImmutableList.of("test_user"));
        coordApi.updateStudyParticipant(STUDY_ID_1, user.getUserId(), participant).execute();
        
        // this now succeeds
        coordApi.deleteTestStudyParticipant(STUDY_ID_1, user.getUserId()).execute();
        
        try {
            coordApi.getStudyParticipantById(STUDY_ID_1, user.getUserId(), false).execute();
            fail("Should have thrown exception");
        } catch(EntityNotFoundException e) {
        }
    }
    
    @Test
    public void getEnrollmentsForUser() throws Exception {
        // Enrolled in study 1 due to consent.
        user = TestUserHelper.createAndSignInUser(ForStudyCoordinatorsTest.class, true);
        
        EnrollmentDetailList list = coordApi.getStudyParticipantEnrollments(STUDY_ID_1, user.getUserId()).execute().body();
        
        assertEquals(1, list.getItems().size());
        EnrollmentDetail enrollment = list.getItems().get(0);
        assertEquals(user.getUserId(), enrollment.getParticipant().getIdentifier());
        assertEquals(STUDY_ID_1, enrollment.getStudyId());
        
        Enrollment newEnrollment = new Enrollment();
        newEnrollment.setUserId(user.getUserId());
        
        coordApi.enrollParticipant(STUDY_ID_2, newEnrollment).execute();
        
        list = coordApi.getStudyParticipantEnrollments(STUDY_ID_1, user.getUserId()).execute().body();
        
        assertEquals(2, list.getItems().size());
        enrollment = list.getItems().get(1);
        assertEquals(user.getUserId(), enrollment.getParticipant().getIdentifier());
        assertEquals(STUDY_ID_2, enrollment.getStudyId());
    }
}
