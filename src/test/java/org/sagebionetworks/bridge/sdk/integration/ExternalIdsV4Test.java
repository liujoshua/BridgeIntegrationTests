package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sagebionetworks.bridge.rest.api.AuthenticationApi;
import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.api.ForResearchersApi;
import org.sagebionetworks.bridge.rest.exceptions.InvalidEntityException;
import org.sagebionetworks.bridge.rest.model.ExternalIdentifier;
import org.sagebionetworks.bridge.rest.model.ExternalIdentifierList;
import org.sagebionetworks.bridge.rest.model.Message;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.SignUp;
import org.sagebionetworks.bridge.rest.model.Study;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.rest.model.Substudy;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.util.IntegTestUtils;

import com.google.common.collect.Lists;

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
            } catch (InvalidEntityException e) {
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

    @Test
    public void testPaging() throws Exception {
        final String idA = Tests.randomIdentifier(ExternalIdsV4Test.class);
        final String idB = Tests.randomIdentifier(ExternalIdsV4Test.class);
        
        final String prefix = Tests.randomIdentifier(ExternalIdsV4Test.class);
        List<ExternalIdentifier> ids = Lists.newArrayListWithCapacity(10);
        for (int i=0; i < 10; i++) {
            String substudyId = (i % 2 == 0) ? idA : idB;
            String identifier = (i > 5) ? ((prefix+"-foo-"+i)) : (prefix+"-"+i);
            ExternalIdentifier id = new ExternalIdentifier().identifier(identifier).substudyId(substudyId);
            ids.add(id);
        }
        
        ForAdminsApi adminClient = admin.getClient(ForAdminsApi.class);
        ForResearchersApi researcherApi = researcher.getClient(ForResearchersApi.class);
        TestUser user = null;
        try {
            // Create substudy
            Substudy substudyA = new Substudy().id(idA).name("Substudy " + idA);
            Substudy substudyB = new Substudy().id(idB).name("Substudy " + idB);
            adminClient.createSubstudy(substudyA).execute();
            adminClient.createSubstudy(substudyB).execute();
            
            // Create enough external IDs to page
            for (int i=0; i < 10; i++) {
                researcherApi.createExternalId(ids.get(i)).execute();    
            }
            // pageSize=3, should have 4 pages 
            ExternalIdentifierList list = researcherApi.getExternalIds(null, 3, prefix, null).execute().body();
            assertEquals(3, list.getItems().size());
            assertEquals(ids.get(0).getIdentifier(), list.getItems().get(0).getIdentifier());
            assertEquals(ids.get(1).getIdentifier(), list.getItems().get(1).getIdentifier());
            assertEquals(ids.get(2).getIdentifier(), list.getItems().get(2).getIdentifier());
            
            list = researcherApi.getExternalIds(list.getNextPageOffsetKey(), 3, prefix, null).execute().body();
            assertEquals(3, list.getItems().size());
            assertEquals(ids.get(3).getIdentifier(), list.getItems().get(0).getIdentifier());
            assertEquals(ids.get(4).getIdentifier(), list.getItems().get(1).getIdentifier());
            assertEquals(ids.get(5).getIdentifier(), list.getItems().get(2).getIdentifier());
            
            list = researcherApi.getExternalIds(list.getNextPageOffsetKey(), 3, prefix, null).execute().body();
            assertEquals(3, list.getItems().size());
            assertEquals(ids.get(6).getIdentifier(), list.getItems().get(0).getIdentifier());
            assertEquals(ids.get(7).getIdentifier(), list.getItems().get(1).getIdentifier());
            assertEquals(ids.get(8).getIdentifier(), list.getItems().get(2).getIdentifier());
            
            list = researcherApi.getExternalIds(list.getNextPageOffsetKey(), 3, prefix, null).execute().body();
            assertEquals(1, list.getItems().size());
            assertEquals(ids.get(9).getIdentifier(), list.getItems().get(0).getIdentifier());
            
            // pageSize = 10, one page with no offset key
            list = researcherApi.getExternalIds(null, 10, prefix, null).execute().body();
            assertEquals(10, list.getItems().size());
            assertNull(list.getNextPageOffsetKey());
            for (int i=0; i < 10; i++) {
                assertEquals(ids.get(i).getIdentifier(), list.getItems().get(i).getIdentifier());
            }
            
            // pageSize = 30, same thing
            list = researcherApi.getExternalIds(null, 30, prefix, null).execute().body();
            assertEquals(10, list.getItems().size());
            assertNull(list.getNextPageOffsetKey());
            for (int i=0; i < 10; i++) {
                assertEquals(ids.get(i).getIdentifier(), list.getItems().get(i).getIdentifier());
            }
            
            // Create a researcher in study B, and run this stuff again, it should be filtered
            String substudyId = ids.get(0).getSubstudyId();
            SignUp signUp = new SignUp().study(IntegTestUtils.STUDY_ID);
            signUp.setExternalId(ids.get(0).getIdentifier());
            user = new TestUserHelper.Builder(ExternalIdsV4Test.class)
                    .withRoles(Role.RESEARCHER, Role.DEVELOPER)
                    .withConsentUser(true).withSignUp(signUp).createAndSignInUser();
            
            ForResearchersApi scopedResearcherApi = user.getClient(ForResearchersApi.class);
            ExternalIdentifierList scopedList = scopedResearcherApi.getExternalIds(null, null, null, null)
                    .execute().body();
            assertEquals(5, scopedList.getItems().size());
            for (int i=0; i < 5; i++) {
                assertEquals(substudyId, scopedList.getItems().get(i).getSubstudyId());
            }
            
            // You can also filter the ids and it maintains the substudy scoping
            scopedList = scopedResearcherApi.getExternalIds(null, null, prefix+"-foo-", null).execute().body();
            assertEquals(2, scopedList.getItems().size()); // only two that are also suffixed with -foo-
            for (int i=0; i < 2; i++) {
                assertEquals(substudyId, scopedList.getItems().get(i).getSubstudyId());
            }
            
        } finally {
            if (user != null) {
                user.signOutAndDeleteUser();    
            }
            for (int i=0; i < 10; i++) {
                researcherApi.deleteExternalId(ids.get(i).getIdentifier()).execute();    
            }
            adminClient.deleteSubstudy(idA, true).execute();
            adminClient.deleteSubstudy(idB, true).execute();
        }
    }

}
