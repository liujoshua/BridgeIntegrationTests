package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.sagebionetworks.bridge.rest.model.Role.RESEARCHER;
import static org.sagebionetworks.bridge.sdk.integration.Tests.ORG_ID_1;
import static org.sagebionetworks.bridge.sdk.integration.Tests.STUDY_ID_1;
import static org.sagebionetworks.bridge.sdk.integration.Tests.STUDY_ID_2;
import static org.sagebionetworks.bridge.sdk.integration.Tests.randomIdentifier;

import java.util.List;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.joda.time.DateTime;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.api.InternalApi;
import org.sagebionetworks.bridge.rest.api.OrganizationsApi;
import org.sagebionetworks.bridge.rest.api.ParticipantsApi;
import org.sagebionetworks.bridge.rest.api.StudiesApi;
import org.sagebionetworks.bridge.rest.model.Enrollment;
import org.sagebionetworks.bridge.rest.model.EnrollmentDetailList;
import org.sagebionetworks.bridge.rest.model.EnrollmentMigration;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;

public class EnrollmentTest {
    
    TestUser admin;
    TestUser researcher;
    
    @After
    public void after() throws Exception {
        if (researcher != null) {
            researcher.signOutAndDeleteUser();
        }
    }
    
    @Before
    public void before() throws Exception {
        researcher = TestUserHelper.createAndSignInUser(EnrollmentTest.class, false, RESEARCHER);
        
        admin = TestUserHelper.getSignedInAdmin();
        OrganizationsApi orgsApi = admin.getClient(OrganizationsApi.class);

        orgsApi.addMember(ORG_ID_1, researcher.getUserId()).execute();
    }
    
    @Test
    public void test() throws Exception {
        String externalId = Tests.randomIdentifier(EnrollmentTest.class);
        TestUser user = TestUserHelper.createAndSignInUser(EnrollmentTest.class, true);
        try {
            DateTime timestamp = DateTime.now();
            StudiesApi studiesApi = admin.getClient(StudiesApi.class);
            
            Enrollment enrollment = new Enrollment();
            enrollment.setEnrolledOn(timestamp);
            enrollment.setExternalId(externalId);
            enrollment.setUserId(user.getUserId());
            enrollment.setConsentRequired(true);
            Enrollment retValue = studiesApi.enrollParticipant(STUDY_ID_1, enrollment).execute().body();
            
            assertEquals(externalId, retValue.getExternalId());
            assertEquals(user.getUserId(), retValue.getUserId());
            assertTrue(retValue.isConsentRequired());
            assertEquals(timestamp.getMillis(), retValue.getEnrolledOn().getMillis());
            assertEquals(admin.getUserId(), retValue.getEnrolledBy());
            assertNull(enrollment.getWithdrawnOn());
            assertNull(enrollment.getWithdrawnBy());
            assertNull(enrollment.getWithdrawalNote());
            
            // Now shows up in paged api
            EnrollmentDetailList list = studiesApi.getEnrollees(STUDY_ID_1, "enrolled", false, null, null).execute().body();
            assertTrue(list.getItems().stream().anyMatch(e -> e.getParticipant().getIdentifier().equals(user.getUserId())));
            
            list = studiesApi.getEnrollees(STUDY_ID_1, null, false, null, null).execute().body();
            assertTrue(list.getItems().stream().anyMatch(e -> e.getParticipant().getIdentifier().equals(user.getUserId())));
            
            retValue = studiesApi.withdrawParticipant(STUDY_ID_1, user.getUserId(), 
                    "Testing enrollment and withdrawal.").execute().body();
            assertEquals(user.getUserId(), retValue.getUserId());
            assertTrue(retValue.isConsentRequired());
            assertEquals(timestamp.getMillis(), retValue.getEnrolledOn().getMillis());
            assertEquals(admin.getUserId(), retValue.getEnrolledBy());
            
            // Enrollment comes from the client in this test, but withdrawal timestamp comes from the 
            // server. Clock skew can throw this off by as much as an hour, so just verify the 
            // withdrawnOn value is there and within the hour either way.
            assertTrue(retValue.getWithdrawnOn().isAfter(DateTime.now().minusHours(1)));
            assertTrue(retValue.getWithdrawnOn().isBefore(DateTime.now().plusHours(1)));
            
            assertEquals(admin.getUserId(), retValue.getWithdrawnBy());
            assertEquals("Testing enrollment and withdrawal.", retValue.getWithdrawalNote());
            
            list = studiesApi.getEnrollees(STUDY_ID_1, "enrolled", false, null, null).execute().body();
            assertFalse(list.getItems().stream().anyMatch(e -> e.getParticipant().getIdentifier().equals(user.getUserId())));
            
            // This person is accessible via the external ID.
            StudyParticipant participant = admin.getClient(ParticipantsApi.class)
                    .getParticipantByExternalId(externalId, false).execute().body();
            assertEquals(user.getUserId(), participant.getId());
            
            // It is still in the paged API, despite being withdrawn.
            list = studiesApi.getEnrollees(STUDY_ID_1, "withdrawn", false, null, null).execute().body();
            assertTrue(list.getItems().stream().anyMatch(e -> e.getParticipant().getIdentifier().equals(user.getUserId())));
            
            list = studiesApi.getEnrollees(STUDY_ID_1, "all", false, null, null).execute().body();
            assertTrue(list.getItems().stream().anyMatch(e -> e.getParticipant().getIdentifier().equals(user.getUserId())));
            
            // test the filter for test accounts
            participant.addDataGroupsItem("test_user");
            admin.getClient(ParticipantsApi.class).updateParticipant(participant.getId(), participant).execute();
            
            list = studiesApi.getEnrollees(STUDY_ID_1, "all", false, null, null).execute().body();
            assertFalse(list.getItems().stream().anyMatch(e -> e.getParticipant().getIdentifier().equals(user.getUserId())));
            
            list = studiesApi.getEnrollees(STUDY_ID_1, "all", true, null, null).execute().body();
            assertTrue(list.getItems().stream().anyMatch(e -> e.getParticipant().getIdentifier().equals(user.getUserId())));
        } finally {
            user.signOutAndDeleteUser();
        }
    }
    
    @Test
    public void adminApisToMigrateEnrollments() throws Exception {
        InternalApi internalApi = admin.getClient(InternalApi.class);
        ParticipantsApi participantsApi = admin.getClient(ParticipantsApi.class);
        StudiesApi studiesApi = admin.getClient(StudiesApi.class);
        
        DateTime timestamp = new DateTime();
        
        // Create a user and enroll them in two studies (avoid the default consent). 
        // Then we'll remove them from one.
        TestUser user = TestUserHelper.createAndSignInUser(EnrollmentTest.class, false);
        String extId1 = randomIdentifier(EnrollmentTest.class);
        String extId2 = randomIdentifier(EnrollmentTest.class);
        try {
            studiesApi.enrollParticipant(STUDY_ID_1, new Enrollment().userId(user.getUserId())
                    .externalId(extId1)).execute();
            
            StudyParticipant participant = participantsApi.getParticipantById(user.getUserId(), false).execute().body();
            List<EnrollmentMigration> migrations = internalApi.getEnrollmentMigrations(participant.getId()).execute()
                    .body();
            
            assertEquals(1, migrations.size());
            assertEquals(ImmutableSet.of(STUDY_ID_1), ImmutableSet.copyOf(participant.getStudyIds()));
            
            EnrollmentMigration enrollInStudy2 = new EnrollmentMigration();
            enrollInStudy2.setAppId(user.getAppId());
            enrollInStudy2.setStudyId(STUDY_ID_2);
            enrollInStudy2.setEnrolledBy(admin.getUserId());
            enrollInStudy2.setEnrolledOn(timestamp);
            enrollInStudy2.setExternalId(extId2);
            enrollInStudy2.setUserId(user.getUserId());
            migrations.add(enrollInStudy2);
            
            internalApi.updateEnrollmentMigrations(participant.getId(), migrations).execute().body();
            participant = participantsApi.getParticipantById(user.getUserId(), false).execute().body();
            
            assertEquals(extId1, participant.getExternalIds().get(STUDY_ID_1));
            assertEquals(extId2, participant.getExternalIds().get(STUDY_ID_2));
            assertEquals(ImmutableList.of(STUDY_ID_1, STUDY_ID_2), participant.getStudyIds());
            
            // now unenroll from one
            EnrollmentMigration unenrollInStudy1 = migrations.stream()
                    .filter(em -> em.getStudyId().equals(STUDY_ID_1)).findFirst().get();
            unenrollInStudy1.setWithdrawnOn(timestamp);
            unenrollInStudy1.setWithdrawnBy(admin.getUserId());
            unenrollInStudy1.setWithdrawalNote("withdrawn for test");
            
            internalApi.updateEnrollmentMigrations(participant.getId(), migrations).execute().body();
            migrations = internalApi.getEnrollmentMigrations(participant.getId()).execute().body();
            
            EnrollmentMigration enrollment1 = migrations.stream()
                    .filter(en -> en.getStudyId().equals(STUDY_ID_1))
                    .findFirst().get();
            EnrollmentMigration enrollment2 = migrations.stream()
                    .filter(en -> en.getStudyId().equals(STUDY_ID_2))
                    .findFirst().get();
            
            assertEquals(timestamp.getMillis(), enrollment1.getWithdrawnOn().getMillis());
            assertNull(enrollment2.getWithdrawnOn());
        } finally {
            user.signOutAndDeleteUser();
        }
    }
}
