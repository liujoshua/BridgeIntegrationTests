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

import org.joda.time.DateTime;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.api.ExternalIdentifiersApi;
import org.sagebionetworks.bridge.rest.api.InternalApi;
import org.sagebionetworks.bridge.rest.api.OrganizationsApi;
import org.sagebionetworks.bridge.rest.api.ParticipantsApi;
import org.sagebionetworks.bridge.rest.api.StudiesApi;
import org.sagebionetworks.bridge.rest.exceptions.ConstraintViolationException;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.model.Enrollment;
import org.sagebionetworks.bridge.rest.model.EnrollmentDetailList;
import org.sagebionetworks.bridge.rest.model.EnrollmentList;
import org.sagebionetworks.bridge.rest.model.EnrollmentMigration;
import org.sagebionetworks.bridge.rest.model.ExternalIdentifier;
import org.sagebionetworks.bridge.rest.model.Organization;
import org.sagebionetworks.bridge.rest.model.Study;
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
        admin = TestUserHelper.getSignedInAdmin();
        OrganizationsApi orgsApi = admin.getClient(OrganizationsApi.class);

        StudiesApi studiesApi = admin.getClient(StudiesApi.class);
        try {
            studiesApi.getStudy(STUDY_ID_1).execute();
        } catch(EntityNotFoundException e) {
            Study study = new Study().identifier(STUDY_ID_1).name(STUDY_ID_1);
            studiesApi.createStudy(study).execute();
        }
        try {
            studiesApi.getStudy(STUDY_ID_2).execute();
        } catch(EntityNotFoundException e) {
            Study study = new Study().identifier(STUDY_ID_2).name(STUDY_ID_2);
            studiesApi.createStudy(study).execute();
        }
        try {
            orgsApi.getOrganization(ORG_ID_1).execute().body();
        } catch(EntityNotFoundException e) {
            Organization org = new Organization().identifier(ORG_ID_1).name(ORG_ID_1);
            orgsApi.createOrganization(org).execute();
        }
        try {
            orgsApi.addStudySponsorship(ORG_ID_1, STUDY_ID_1).execute();    
        } catch(ConstraintViolationException e) {
            // If this isn't the message, this isn't the exception we're expecting.
            assertEquals("Organization is already a sponsor of this study.", e.getMessage());
        }
        researcher = TestUserHelper.createAndSignInUser(EnrollmentTest.class, false, RESEARCHER);
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
            EnrollmentDetailList list = studiesApi.getEnrollees(STUDY_ID_1, "enrolled", null, null).execute().body();
            assertTrue(list.getItems().stream().anyMatch(e -> e.getParticipant().getIdentifier().equals(user.getUserId())));
            
            list = studiesApi.getEnrollees(STUDY_ID_1, null, null, null).execute().body();
            assertTrue(list.getItems().stream().anyMatch(e -> e.getParticipant().getIdentifier().equals(user.getUserId())));
            
            retValue = studiesApi.withdrawParticipant(STUDY_ID_1, user.getUserId(), 
                    "Testing enrollment and withdrawal.").execute().body();
            assertEquals(user.getUserId(), retValue.getUserId());
            assertTrue(retValue.isConsentRequired());
            assertEquals(timestamp.getMillis(), retValue.getEnrolledOn().getMillis());
            assertEquals(admin.getUserId(), retValue.getEnrolledBy());
            assertTrue(retValue.getWithdrawnOn().isAfter(timestamp));
            assertEquals(admin.getUserId(), retValue.getWithdrawnBy());
            assertEquals("Testing enrollment and withdrawal.", retValue.getWithdrawalNote());
            
            list = studiesApi.getEnrollees(STUDY_ID_1, "enrolled", null, null).execute().body();
            assertFalse(list.getItems().stream().anyMatch(e -> e.getParticipant().getIdentifier().equals(user.getUserId())));
            
            // This person is accessible via the external ID.
            StudyParticipant participant = admin.getClient(ParticipantsApi.class)
                    .getParticipantByExternalId(externalId, false).execute().body();
            assertEquals(user.getUserId(), participant.getId());
            
            // It is still in the paged API, despite being withdrawn.
            list = studiesApi.getEnrollees(STUDY_ID_1, "withdrawn", null, null).execute().body();
            assertTrue(list.getItems().stream().anyMatch(e -> e.getParticipant().getIdentifier().equals(user.getUserId())));
            
            list = studiesApi.getEnrollees(STUDY_ID_1, "all", null, null).execute().body();
            assertTrue(list.getItems().stream().anyMatch(e -> e.getParticipant().getIdentifier().equals(user.getUserId())));
        } finally {
            user.signOutAndDeleteUser();
        }
    }
    
    @Test
    public void adminApisToMigrateEnrollments() throws Exception {
        InternalApi internalApi = admin.getClient(InternalApi.class);
        ExternalIdentifiersApi extIdsApi = admin.getClient(ExternalIdentifiersApi.class);
        ParticipantsApi participantsApi = admin.getClient(ParticipantsApi.class);
        
        DateTime timestamp = new DateTime();
        
        // Create a user and enroll them in two studies using external IDs. Then we'll remove them from one.
        TestUser user = TestUserHelper.createAndSignInUser(EnrollmentTest.class, true);
        ExternalIdentifier extId2 = new ExternalIdentifier().studyId(STUDY_ID_2).identifier(randomIdentifier(EnrollmentTest.class));
        try {
            extIdsApi.createExternalId(extId2).execute();
            
            StudyParticipant participant = participantsApi.getParticipantById(user.getUserId(), false).execute().body();
            List<EnrollmentMigration> migrations = internalApi.getEnrollmentMigrations(participant.getId()).execute().body();
            
            assertEquals(0, migrations.size());
            
            EnrollmentMigration migration = new EnrollmentMigration();
            migration.setAppId(user.getAppId());
            migration.setStudyId(STUDY_ID_2);
            migration.setEnrolledBy(admin.getUserId());
            migration.setEnrolledOn(timestamp);
            migration.setExternalId(extId2.getIdentifier());
            migration.setUserId(user.getUserId());
            migrations.add(migration);
            
            internalApi.updateEnrollmentMigrations(participant.getId(), migrations).execute().body();
            
            participant = participantsApi.getParticipantById(user.getUserId(), false).execute().body();
            
            assertEquals(extId2.getIdentifier(), participant.getExternalIds().get(STUDY_ID_2));
            assertEquals(ImmutableList.of(STUDY_ID_2), participant.getStudyIds());
            
            migrations = internalApi.getEnrollmentMigrations(participant.getId()).execute().body();
            assertEquals(1, migrations.size());
            assertEquals(STUDY_ID_2, migrations.get(0).getStudyId());
        } finally {
            extIdsApi.deleteExternalId(extId2.getIdentifier()).execute();
            user.signOutAndDeleteUser();
        }
    }
}
