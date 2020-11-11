package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.sagebionetworks.bridge.rest.model.Role.ADMIN;
import static org.sagebionetworks.bridge.rest.model.Role.DEVELOPER;
import static org.sagebionetworks.bridge.rest.model.Role.RESEARCHER;
import static org.sagebionetworks.bridge.util.IntegTestUtils.SAGE_ID;
import static org.sagebionetworks.bridge.util.IntegTestUtils.TEST_APP_ID;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.rest.api.ForSuperadminsApi;
import org.sagebionetworks.bridge.rest.api.OrganizationsApi;
import org.sagebionetworks.bridge.rest.api.ParticipantsApi;
import org.sagebionetworks.bridge.rest.api.StudiesApi;
import org.sagebionetworks.bridge.rest.model.App;
import org.sagebionetworks.bridge.rest.model.Enrollment;
import org.sagebionetworks.bridge.rest.model.EnrollmentDetailList;
import org.sagebionetworks.bridge.rest.model.SignUp;
import org.sagebionetworks.bridge.rest.model.Study;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.rest.model.Withdrawal;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.util.IntegTestUtils;

import com.google.common.collect.ImmutableMap;

/**
 * This test is redundant with other tests and could arguably be deleted. We test in many places
 * that external IDs establish an enrollment relationship.
 */
public class StudyMembershipTest {
    private TestUser admin;
    private TestUser appAdmin;
    private StudiesApi studiesApi;
    private Set<String> studyIdsToDelete;
    private Set<String> externalIdsToDelete;
    private Set<TestUser> usersToDelete;

    @Before
    public void before() throws Exception {
        admin = TestUserHelper.getSignedInAdmin();
        appAdmin = TestUserHelper.createAndSignInUser(StudyMembershipTest.class, false, DEVELOPER, RESEARCHER,
                ADMIN); // Sage Bionetworks
        studiesApi = admin.getClient(StudiesApi.class);

        studyIdsToDelete = new HashSet<>();
        externalIdsToDelete = new HashSet<>();
        usersToDelete = new HashSet<>();
    }

    @After
    public void after() throws Exception {
        ForSuperadminsApi superadminsApi = admin.getClient(ForSuperadminsApi.class);
        ForAdminsApi adminsApi = admin.getClient(ForAdminsApi.class);
        App app = superadminsApi.getApp(TEST_APP_ID).execute().body();
        app.setExternalIdRequiredOnSignup(false);
        superadminsApi.updateApp(app.getIdentifier(), app).execute();

        // This can only happen after external ID management is disabled.
        for (TestUser user : usersToDelete) {
            try {
                user.signOutAndDeleteUser();
            } catch(Exception e) {
                e.printStackTrace();
            }
        }
        if (appAdmin != null) {
            StudiesApi studiesApi = appAdmin.getClient(StudiesApi.class);
            for (String externalId : externalIdsToDelete) {
                try {
                    studiesApi.deleteExternalId(externalId).execute();    
                } catch(Exception e) {
                    e.printStackTrace();
                }
            }
            appAdmin.signOutAndDeleteUser();
        }
        for (String studyId : studyIdsToDelete) {
            try {
                adminsApi.deleteStudy(studyId, true).execute();    
            } catch(Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Test
    public void addingExternalIdsAssociatesToStudy() throws Exception {
        App app = admin.getClient(ForAdminsApi.class).getUsersApp().execute().body();
        app.setExternalIdRequiredOnSignup(true);
        admin.getClient(ForSuperadminsApi.class).updateApp(app.getIdentifier(), app).execute();
        
        // Create two studies
        String idA = createStudy();
        String idB = createStudy();

        // Create an external ID in each study
        String extIdA = "extA";
        String extIdB = "extB";

        // create an account, sign in and consent, assigned to study A
        TestUser user = createUser(idA, extIdA);
        String userId = user.getUserId();

        ForConsentedUsersApi userApi = user.getClient(ForConsentedUsersApi.class);
        ParticipantsApi appAdminParticipantsApi = appAdmin.getClient(ParticipantsApi.class);

        // It has the extIdA external ID
        Map<String, String> externalIds = user.getSession().getExternalIds();
        assertEquals(extIdA, externalIds.get(idA));
        assertEquals(1, externalIds.size());
        assertTrue(user.getSession().getExternalIds().values().contains(extIdA));

        // participant is associated to two studies
        StudiesApi studiesApi = appAdmin.getClient(StudiesApi.class);
        studiesApi.enrollParticipant(idB, new Enrollment().userId(userId).externalId(extIdB)).execute();
        
        StudyParticipant participant = appAdminParticipantsApi.getParticipantById(userId, false).execute().body();
        assertEquals(2, participant.getExternalIds().size());
        assertEquals(extIdA, participant.getExternalIds().get(idA));
        assertEquals(extIdB, participant.getExternalIds().get(idB));

        // admin removes a study...
        studiesApi.withdrawParticipant(idA, userId, "Note").execute();

        StudyParticipant updatedParticipant = admin.getClient(ParticipantsApi.class)
                .getParticipantById(userId, false).execute().body();
        assertEquals(1, updatedParticipant.getExternalIds().size());
        assertEquals(extIdB, updatedParticipant.getExternalIds().get(idB));
        
        // Test that withdrawing blanks out the external ID relationships
        
        userApi.withdrawFromApp(new Withdrawal().reason("Testing external IDs")).execute();
        
        StudyParticipant withdrawn = appAdminParticipantsApi.getParticipantById(userId, true).execute().body();
        
        assertEquals(0, withdrawn.getExternalIds().size());
        
        // Verify the enrollment records reflect these changes
        EnrollmentDetailList list = appAdmin.getClient(StudiesApi.class).getEnrollees(idB, "withdrawn", true, 0, 10).execute().body();
        assertEquals(1, list.getItems().size());
        assertEquals(extIdB, list.getItems().get(0).getExternalId());
    }
    
    // User can no longer add an arbitrary external ID to their account, since this represents
    // enrollment in a study, not just another way to identify the account.

    private String createStudy() throws Exception {
        String id = Tests.randomIdentifier(StudyTest.class);
        Study study = new Study().identifier(id).name("Study " + id);
        studiesApi.createStudy(study).execute();
        admin.getClient(OrganizationsApi.class).addStudySponsorship(SAGE_ID, id).execute();
        studyIdsToDelete.add(id);
        return id;
    }

    private TestUser createUser(String studyId, String externalId) throws Exception {
        String email = IntegTestUtils.makeEmail(StudyMembershipTest.class);
        SignUp signUp = new SignUp().appId(TEST_APP_ID).email(email).password("P@ssword`1");
        signUp.externalIds(ImmutableMap.of(studyId, externalId));
        TestUser user = TestUserHelper.createAndSignInUser(StudyMembershipTest.class, true, signUp);
        usersToDelete.add(user);
        return user;
    }
}