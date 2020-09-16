package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
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
import org.sagebionetworks.bridge.rest.api.ExternalIdentifiersApi;
import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.rest.api.ForSuperadminsApi;
import org.sagebionetworks.bridge.rest.api.OrganizationsApi;
import org.sagebionetworks.bridge.rest.api.ParticipantsApi;
import org.sagebionetworks.bridge.rest.api.StudiesApi;
import org.sagebionetworks.bridge.rest.exceptions.ConstraintViolationException;
import org.sagebionetworks.bridge.rest.model.App;
import org.sagebionetworks.bridge.rest.model.EnrollmentList;
import org.sagebionetworks.bridge.rest.model.ExternalIdentifier;
import org.sagebionetworks.bridge.rest.model.IdentifierUpdate;
import org.sagebionetworks.bridge.rest.model.SignUp;
import org.sagebionetworks.bridge.rest.model.Study;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.rest.model.UserSessionInfo;
import org.sagebionetworks.bridge.rest.model.Withdrawal;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.util.IntegTestUtils;

import com.google.common.collect.ImmutableList;

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
            ExternalIdentifiersApi extIdsApi = appAdmin.getClient(ExternalIdentifiersApi.class);
            for (String externalId : externalIdsToDelete) {
                try {
                    extIdsApi.deleteExternalId(externalId).execute();    
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
        String extIdA = createExternalId(idA, "extA");
        String extIdB = createExternalId(idB, "extB");

        // create an account, sign in and consent, assigned to study A
        TestUser user = createUser(extIdA);
        ForConsentedUsersApi userApi = user.getClient(ForConsentedUsersApi.class);
        ParticipantsApi appAdminParticipantsApi = appAdmin.getClient(ParticipantsApi.class);

        // It has the extIdA external ID
        Map<String, String> externalIds = user.getSession().getExternalIds();
        assertEquals(extIdA, externalIds.get(idA));
        assertEquals(1, externalIds.size());
        assertTrue(user.getSession().getExternalIds().values().contains(extIdA));

        // add an external ID through the updateIdentifiers interface, should associate to B
        IdentifierUpdate update = new IdentifierUpdate().signIn(user.getSignIn()).externalIdUpdate(extIdB);
        // session updated to two studies
        UserSessionInfo info = userApi.updateUsersIdentifiers(update).execute().body();
        assertEquals(2, info.getExternalIds().size());
        assertEquals(extIdA, info.getExternalIds().get(idA));
        assertEquals(extIdB, info.getExternalIds().get(idB));

        // Error test #1: assigning an external ID that associates use to study they are already
        // associated to, throws the appropriate error
        String extIdA2 = createExternalId(idA, "extA2");
        update = new IdentifierUpdate().signIn(user.getSignIn()).externalIdUpdate(extIdA2);
        try {
            userApi.updateUsersIdentifiers(update).execute();
            fail("Should have thrown exception");
        } catch (ConstraintViolationException e) {
            assertTrue(e.getMessage().contains("Account already associated to study."));
        }

        // Error test #2: assigning the same external ID silently changes nothing
        update = new IdentifierUpdate().signIn(user.getSignIn()).externalIdUpdate(extIdA);
        info = userApi.updateUsersIdentifiers(update).execute().body();
        assertEquals(2, info.getExternalIds().size());
        assertEquals(extIdA, info.getExternalIds().get(idA));
        assertEquals(extIdB, info.getExternalIds().get(idB));
        
        // participant is associated to two studies
        StudyParticipant participant = appAdminParticipantsApi.getParticipantById(info.getId(), false).execute().body();
        assertEquals(2, participant.getExternalIds().size());
        assertEquals(extIdA, participant.getExternalIds().get(idA));
        assertEquals(extIdB, participant.getExternalIds().get(idB));

        // admin removes a study...
        participant.setStudyIds(ImmutableList.of(idB)); // no longer associated to study A
        participant.setExternalId(null); // don't add it back!
        appAdminParticipantsApi.updateParticipant(info.getId(), participant).execute();

        StudyParticipant updatedParticipant = admin.getClient(ParticipantsApi.class)
                .getParticipantById(info.getId(), false).execute().body();
        
        assertEquals(1, updatedParticipant.getExternalIds().size());
        assertEquals(extIdB, updatedParticipant.getExternalIds().get(idB));
        
        // Test that withdrawing blanks out the external ID relationships
        String userId = user.getUserId();
        
        userApi.withdrawFromApp(new Withdrawal().reason("Testing external IDs")).execute();
        
        StudyParticipant withdrawn = appAdminParticipantsApi.getParticipantById(userId, true).execute().body();
        assertEquals(0, withdrawn.getExternalIds().size());
        
        // One enrollment was removed through the legacy approach of set studyIds on a participant update. This actually
        // removes the enrollment, and is being phased out. The second approach called withdrawal and this preserves the 
        // second enrollment object to idB.
        EnrollmentList list = appAdmin.getClient(StudiesApi.class).getEnrollees(idB, "withdrawn", 0, 10).execute().body();
        assertEquals(1, list.getItems().size());
        assertEquals(extIdB, list.getItems().get(0).getExternalId());
    }
    
    @Test
    public void userCanAddExternalIdMembership() throws Exception {
        // Create two studies
        String idA = createStudy();
        String idB = createStudy();

        // Create an external ID in each study
        String extIdA1 = createExternalId(idA, "extA1");
        String extIdA2 = createExternalId(idA, "extA2");
        String extIdB = createExternalId(idB, "extB");

        // create an account, sign in and consent, assigned to study A
        TestUser user = TestUserHelper.createAndSignInUser(StudyMembershipTest.class, true);
        usersToDelete.add(user);
        
        ForConsentedUsersApi userApi = user.getClient(ForConsentedUsersApi.class);

        // add an external ID the old fashioned way, using the StudyParticipant. This works the first time because
        // the user isn't associated to a study yet
        StudyParticipant participant = new StudyParticipant().externalId(extIdA1);
        UserSessionInfo session = userApi.updateUsersParticipantRecord(participant).execute().body();
        assertEquals(extIdA1, session.getExternalIds().get(idA));
        
        // the second time will not work because now the user is associated to a study.
        participant = new StudyParticipant().externalId(extIdB);
        session = userApi.updateUsersParticipantRecord(participant).execute().body();
        assertEquals(extIdA1, session.getExternalIds().get(idA));
        assertEquals(extIdB, session.getExternalIds().get(idB));
        
        // But you cannot change an ID once set just by updating the account
        participant = new StudyParticipant().externalId(extIdA2);
        try {
            userApi.updateUsersParticipantRecord(participant).execute().body();
            fail("Should have thrown exception");
        } catch(ConstraintViolationException e) {
            assertEquals("Account already associated to study.", e.getMessage());
        }
    }

    private String createStudy() throws Exception {
        String id = Tests.randomIdentifier(StudyTest.class);
        Study study = new Study().identifier(id).name("Study " + id);
        studiesApi.createStudy(study).execute();
        admin.getClient(OrganizationsApi.class).addStudySponsorship(SAGE_ID, id).execute();
        studyIdsToDelete.add(id);
        return id;
    }

    private String createExternalId(String studyId, String id) throws Exception {
        ExternalIdentifiersApi externalIdApi = appAdmin.getClient(ExternalIdentifiersApi.class);
        ExternalIdentifier extId = new ExternalIdentifier().identifier(id + studyId).studyId(studyId);
        externalIdApi.createExternalId(extId).execute();
        externalIdsToDelete.add(extId.getIdentifier());
        return extId.getIdentifier();
    }

    private TestUser createUser(String externalId) throws Exception {
        String email = IntegTestUtils.makeEmail(StudyMembershipTest.class);
        SignUp signUp = new SignUp().appId(TEST_APP_ID).email(email).password("P@ssword`1");
        signUp.externalId(externalId);
        TestUser user = TestUserHelper.createAndSignInUser(StudyMembershipTest.class, true, signUp);
        usersToDelete.add(user);
        return user;
    }
}