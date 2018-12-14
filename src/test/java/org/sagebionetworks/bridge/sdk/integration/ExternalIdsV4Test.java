package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sagebionetworks.bridge.rest.api.AuthenticationApi;
import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.api.ForResearchersApi;
import org.sagebionetworks.bridge.rest.exceptions.InvalidEntityException;
import org.sagebionetworks.bridge.rest.model.ExternalIdentifier;
import org.sagebionetworks.bridge.rest.model.Message;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.SignUp;
import org.sagebionetworks.bridge.rest.model.Study;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.rest.model.Substudy;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.util.IntegTestUtils;

import retrofit2.Response;

public class ExternalIdsV4Test {

    private TestUser admin;
    private TestUser researcher;
    
    @Before
    public void before() throws Exception { 
        admin = TestUserHelper.getSignedInAdmin();
        researcher = TestUserHelper.createAndSignInUser(ExternalIdsV4Test.class, true, Role.RESEARCHER);
    }
    
    @After
    public void after() throws Exception {
        researcher.signOutAndDeleteUser();
    }
    
    @Test
    public void test() throws Exception {
        final String idA = Tests.randomIdentifier(ExternalIdsV4Test.class);
        final String idB = Tests.randomIdentifier(ExternalIdsV4Test.class);
        
        final String extIdA = Tests.randomIdentifier(ExternalIdsV4Test.class);
        final String extIdB = Tests.randomIdentifier(ExternalIdsV4Test.class);
        
        ForAdminsApi adminClient = admin.getClient(ForAdminsApi.class);
        ForResearchersApi researcherApi = researcher.getClient(ForResearchersApi.class);
        String userId = null;
        try {
            Study study = adminClient.getUsersStudy().execute().body();
            study.setExternalIdValidationEnabled(true);
            adminClient.updateStudy(study.getIdentifier(), study).execute();
            
            // Create some substudies
            Substudy substudyA = new Substudy().id(idA).name("Substudy " + idA);
            Substudy substudyB = new Substudy().id(idB).name("Substudy " + idB);
            adminClient.createSubstudy(substudyA).execute();
            adminClient.createSubstudy(substudyB).execute();
            
            // Creating an external ID without a substudy now fails
            try {
                researcherApi.createExternalId(new ExternalIdentifier().identifier(extIdA)).execute();
                fail("Should have thrown an exception");
            } catch(InvalidEntityException e) {
            }
            
            // Create a couple of external IDs related to different substudies.
            ExternalIdentifier extId1 = new ExternalIdentifier().identifier(extIdA).substudyId(idA);
            ExternalIdentifier extId2 = new ExternalIdentifier().identifier(extIdB).substudyId(idB);
            researcherApi.createExternalId(extId1).execute();
            researcherApi.createExternalId(extId2).execute();
            
            // Sign up a user with an external ID specified. Just one of them: we don't have plans to 
            // allow the assignment of multiple external IDs on sign up. Adding new substudies is probably
            // going to happen by signing additional consents, but that's TBD.
            SignUp signUp = new SignUp().study(IntegTestUtils.STUDY_ID);
            signUp.setExternalId(extIdA);
            researcher.getClient(AuthenticationApi.class).signUp(signUp).execute();
            
            // The created account has been associated to the external ID and its related substudy
            StudyParticipant participant = researcherApi.getParticipantByExternalId(extIdA, false).execute().body();
            userId = participant.getId();
            assertEquals(1, participant.getExternalIds().size());
            assertEquals(extIdA, participant.getExternalIds().get(idA));
            assertEquals(1, participant.getSubstudyIds().size());
            assertEquals(idA, participant.getSubstudyIds().get(0));
            assertEquals(extIdA, participant.getExternalId());
            
            // Cannot create another user with this external ID. This should do nothing and fail quietly.
            Response<Message> response = researcher.getClient(AuthenticationApi.class).signUp(signUp).execute();
            assertEquals(201, response.code());
            
            StudyParticipant participant2 = researcherApi.getParticipantByExternalId(extIdA, false).execute().body();
            assertEquals(userId, participant2.getId());
            
        } finally {
            Study study = adminClient.getUsersStudy().execute().body();
            study.setExternalIdValidationEnabled(false);
            adminClient.updateStudy(study.getIdentifier(), study).execute();
            
            adminClient.deleteUser(userId).execute();    
            researcherApi.deleteExternalId(extIdA).execute();
            researcherApi.deleteExternalId(extIdB).execute();
            adminClient.deleteSubstudy(idA, true).execute();
            adminClient.deleteSubstudy(idB, true).execute();
        }
        
    }
}
