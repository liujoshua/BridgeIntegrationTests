package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sagebionetworks.bridge.rest.api.ExternalIdentifiersApi;
import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.rest.api.ParticipantsApi;
import org.sagebionetworks.bridge.rest.api.SubstudiesApi;
import org.sagebionetworks.bridge.rest.exceptions.ConstraintViolationException;
import org.sagebionetworks.bridge.rest.model.ExternalIdentifier;
import org.sagebionetworks.bridge.rest.model.IdentifierUpdate;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.SignUp;
import org.sagebionetworks.bridge.rest.model.Study;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.rest.model.Substudy;
import org.sagebionetworks.bridge.rest.model.UserSessionInfo;
import org.sagebionetworks.bridge.rest.model.Withdrawal;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.util.IntegTestUtils;

import com.google.common.collect.ImmutableList;

public class SubstudyMembershipTest {
    private TestUser admin;
    private TestUser studyAdmin;
    private SubstudiesApi substudiesApi;
    private Set<String> substudyIdsToDelete;
    private Set<String> externalIdsToDelete;
    private Set<TestUser> usersToDelete;

    @Before
    public void before() throws Exception {
        admin = TestUserHelper.getSignedInAdmin();
        studyAdmin = TestUserHelper.createAndSignInUser(SubstudyMembershipTest.class, false, Role.DEVELOPER,
                Role.RESEARCHER, Role.ADMIN); // to change substudy membership, user must also be an admin.
        substudiesApi = admin.getClient(SubstudiesApi.class);

        Study study = admin.getClient(ForAdminsApi.class).getUsersStudy().execute().body();
        study.setExternalIdRequiredOnSignup(true);
        admin.getClient(ForAdminsApi.class).updateStudy(study.getIdentifier(), study).execute();

        substudyIdsToDelete = new HashSet<>();
        externalIdsToDelete = new HashSet<>();
        usersToDelete = new HashSet<>();
    }

    @After
    public void after() throws Exception {
        ForAdminsApi adminsApi = admin.getClient(ForAdminsApi.class);
        Study study = adminsApi.getUsersStudy().execute().body();
        study.setExternalIdRequiredOnSignup(false);
        admin.getClient(ForAdminsApi.class).updateStudy(study.getIdentifier(), study).execute();

        // This can only happen after external ID management is disabled.
        for (TestUser user : usersToDelete) {
            try {
                user.signOutAndDeleteUser();
            } catch(Exception e) {
                e.printStackTrace();
            }
        }
        if (studyAdmin != null) {
            ExternalIdentifiersApi extIdsApi = studyAdmin.getClient(ExternalIdentifiersApi.class);
            for (String externalId : externalIdsToDelete) {
                try {
                    extIdsApi.deleteExternalId(externalId).execute();    
                } catch(Exception e) {
                    e.printStackTrace();
                }
            }
            studyAdmin.signOutAndDeleteUser();
        }
        for (String substudyId : substudyIdsToDelete) {
            try {
                adminsApi.deleteSubstudy(substudyId, true).execute();    
            } catch(Exception e) {
                e.printStackTrace();
            }
            
        }
    }

    @Test
    public void addingExternalIdsAssociatesToSubstudy() throws Exception {
        // Create two substudies
        String idA = createSubstudy();
        String idB = createSubstudy();

        // Create an external ID in each substudy
        String extIdA = createExternalId(idA, "extA");
        String extIdB = createExternalId(idB, "extB");

        // create an account, sign in and consent, assigned to substudy A
        TestUser user = createUser(extIdA);
        ForConsentedUsersApi userApi = user.getClient(ForConsentedUsersApi.class);
        ParticipantsApi participantsApi = studyAdmin.getClient(ParticipantsApi.class);

        Map<String, String> externalIds = user.getSession().getExternalIds();
        assertEquals(extIdA, externalIds.get(idA));
        assertEquals(1, externalIds.size());
        assertTrue(user.getSession().getExternalIds().values().contains(extIdA));

        // add an external ID through the updateIdentifiers interface, should associate to B
        IdentifierUpdate update = new IdentifierUpdate().signIn(user.getSignIn()).externalIdUpdate(extIdB);
        // session updated to two substudies
        UserSessionInfo info = userApi.updateUsersIdentifiers(update).execute().body();
        assertEquals(2, info.getExternalIds().size());
        assertEquals(extIdA, info.getExternalIds().get(idA));
        assertEquals(extIdB, info.getExternalIds().get(idB));

        // Error test #1: assigning an external ID that associates use to substudy they are already
        // associated to, throws the appropriate error
        String extIdA2 = createExternalId(idA, "extA2");
        update = new IdentifierUpdate().signIn(user.getSignIn()).externalIdUpdate(extIdA2);
        try {
            userApi.updateUsersIdentifiers(update).execute();
            fail("Should have thrown exception");
        } catch (ConstraintViolationException e) {
            assertTrue(e.getMessage().contains("Account already associated to substudy."));
        }

        // Error test #2: assigning the same external ID silently changes nothing
        update = new IdentifierUpdate().signIn(user.getSignIn()).externalIdUpdate(extIdA);
        info = userApi.updateUsersIdentifiers(update).execute().body();
        assertEquals(2, info.getExternalIds().size());
        assertEquals(extIdA, info.getExternalIds().get(idA));
        assertEquals(extIdB, info.getExternalIds().get(idB));
        
        // participant is associated to two substudies
        StudyParticipant participant = participantsApi.getParticipantById(info.getId(), false).execute().body();
        assertEquals(2, participant.getExternalIds().size());
        assertEquals(extIdA, participant.getExternalIds().get(idA));
        assertEquals(extIdB, participant.getExternalIds().get(idB));

        // admin removes a substudy...
        participant.setSubstudyIds(ImmutableList.of(idB)); // no longer associated to substudy A
        participantsApi.updateParticipant(info.getId(), participant).execute();

        StudyParticipant updatedParticipant = participantsApi.getParticipantById(info.getId(), false).execute().body();
        
        assertEquals(1, updatedParticipant.getExternalIds().size());
        assertEquals(extIdB, updatedParticipant.getExternalIds().get(idB));
        
        // Test that withdrawing blanks out the external ID relationships
        String userId = user.getUserId();
        
        userApi.withdrawFromStudy(new Withdrawal().reason("Testing external IDs")).execute();
        
        // External IDs are not erased
        StudyParticipant withdrawn = participantsApi.getParticipantById(userId, true).execute().body();
        assertEquals(1, withdrawn.getExternalIds().size());
        assertEquals(extIdB, withdrawn.getExternalIds().get(idB));
    }

    private String createSubstudy() throws Exception {
        String id = Tests.randomIdentifier(SubstudyTest.class);
        Substudy substudy = new Substudy().id(id).name("Substudy " + id);
        substudiesApi.createSubstudy(substudy).execute();
        substudyIdsToDelete.add(id);
        return id;
    }

    private String createExternalId(String substudyId, String id) throws Exception {
        ExternalIdentifiersApi externalIdApi = studyAdmin.getClient(ExternalIdentifiersApi.class);
        ExternalIdentifier extId = new ExternalIdentifier().identifier(id + substudyId).substudyId(substudyId);
        externalIdApi.createExternalId(extId).execute();
        externalIdsToDelete.add(extId.getIdentifier());
        return extId.getIdentifier();
    }

    private TestUser createUser(String externalId) throws Exception {
        String email = IntegTestUtils.makeEmail(SubstudyMembershipTest.class);
        SignUp signUp = new SignUp().study(IntegTestUtils.STUDY_ID).email(email).password("P@ssword`1");
        signUp.externalId(externalId);
        TestUser user = TestUserHelper.createAndSignInUser(SubstudyMembershipTest.class, true, signUp);
        usersToDelete.add(user);
        return user;
    }
}
