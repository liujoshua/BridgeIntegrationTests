package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.sagebionetworks.bridge.rest.model.Role.RESEARCHER;
import static org.sagebionetworks.bridge.sdk.integration.Tests.ORG_ID_1;
import static org.sagebionetworks.bridge.sdk.integration.Tests.STUDY_ID_1;

import org.joda.time.DateTime;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.api.OrganizationsApi;
import org.sagebionetworks.bridge.rest.api.ParticipantsApi;
import org.sagebionetworks.bridge.rest.api.StudiesApi;
import org.sagebionetworks.bridge.rest.model.Enrollment;
import org.sagebionetworks.bridge.rest.model.EnrollmentDetailList;
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
            EnrollmentDetailList list = studiesApi.getEnrollments(STUDY_ID_1, "enrolled", false, null, null).execute().body();
            assertTrue(list.getItems().stream().anyMatch(e -> e.getParticipant().getIdentifier().equals(user.getUserId())));
            
            list = studiesApi.getEnrollments(STUDY_ID_1, null, false, null, null).execute().body();
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
            
            list = studiesApi.getEnrollments(STUDY_ID_1, "enrolled", false, null, null).execute().body();
            assertFalse(list.getItems().stream().anyMatch(e -> e.getParticipant().getIdentifier().equals(user.getUserId())));
            
            // This person is accessible via the external ID.
            StudyParticipant participant = admin.getClient(ParticipantsApi.class)
                    .getParticipantByExternalId(externalId, false).execute().body();
            assertEquals(user.getUserId(), participant.getId());
            
            // It is still in the paged API, despite being withdrawn.
            list = studiesApi.getEnrollments(STUDY_ID_1, "withdrawn", false, null, null).execute().body();
            assertTrue(list.getItems().stream().anyMatch(e -> e.getParticipant().getIdentifier().equals(user.getUserId())));
            
            list = studiesApi.getEnrollments(STUDY_ID_1, "all", false, null, null).execute().body();
            assertTrue(list.getItems().stream().anyMatch(e -> e.getParticipant().getIdentifier().equals(user.getUserId())));
            
            // test the filter for test accounts
            participant.addDataGroupsItem("test_user");
            admin.getClient(ParticipantsApi.class).updateParticipant(participant.getId(), participant).execute();
            
            list = studiesApi.getEnrollments(STUDY_ID_1, "all", false, null, null).execute().body();
            assertFalse(list.getItems().stream().anyMatch(e -> e.getParticipant().getIdentifier().equals(user.getUserId())));
            
            list = studiesApi.getEnrollments(STUDY_ID_1, "all", true, null, null).execute().body();
            assertTrue(list.getItems().stream().anyMatch(e -> e.getParticipant().getIdentifier().equals(user.getUserId())));
        } finally {
            user.signOutAndDeleteUser();
        }
    }
}
