package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.sagebionetworks.bridge.sdk.integration.Tests.ORG_ID_1;
import static org.sagebionetworks.bridge.sdk.integration.Tests.STUDY_ID_1;
import static org.sagebionetworks.bridge.sdk.integration.Tests.STUDY_ID_2;
import static org.sagebionetworks.bridge.util.IntegTestUtils.TEST_APP_ID;

import java.util.List;
import java.util.stream.Collectors;

import org.apache.commons.lang3.RandomStringUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.ClientManager;
import org.sagebionetworks.bridge.rest.api.AuthenticationApi;
import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.api.ForResearchersApi;
import org.sagebionetworks.bridge.rest.api.ForSuperadminsApi;
import org.sagebionetworks.bridge.rest.api.OrganizationsApi;
import org.sagebionetworks.bridge.rest.api.ParticipantsApi;
import org.sagebionetworks.bridge.rest.exceptions.InvalidEntityException;
import org.sagebionetworks.bridge.rest.model.App;
import org.sagebionetworks.bridge.rest.model.ExternalIdentifier;
import org.sagebionetworks.bridge.rest.model.ExternalIdentifierList;
import org.sagebionetworks.bridge.rest.model.IdentifierUpdate;
import org.sagebionetworks.bridge.rest.model.Message;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.SignIn;
import org.sagebionetworks.bridge.rest.model.SignUp;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;

import com.google.common.collect.Lists;

import retrofit2.Response;

public class ExternalIdsV4Test {

    private String prefix;
    private TestUser admin;
    private TestUser researcher;

    @Before
    public void before() throws Exception {
        prefix = RandomStringUtils.randomAlphabetic(5);
        admin = TestUserHelper.getSignedInAdmin();
        researcher = TestUserHelper.createAndSignInUser(ExternalIdsV4Test.class, true, Role.RESEARCHER);
    }

    @After
    public void after() throws Exception {
        researcher.signOutAndDeleteUser();
    }

    @Test
    public void test() throws Exception {
        final String extIdA = prefix+Tests.randomIdentifier(ExternalIdsV4Test.class);
        final String extIdB1 = prefix+Tests.randomIdentifier(ExternalIdsV4Test.class);
        final String extIdB2 = prefix+Tests.randomIdentifier(ExternalIdsV4Test.class);

        ForSuperadminsApi superadminClient = admin.getClient(ForSuperadminsApi.class);
        ForAdminsApi adminClient = admin.getClient(ForAdminsApi.class);
        ForResearchersApi researcherApi = researcher.getClient(ForResearchersApi.class);
        String userId = null;
        try {
            App app = superadminClient.getApp(TEST_APP_ID).execute().body();
            superadminClient.updateApp(app.getIdentifier(), app).execute();

            // Create some studies
            // Creating an external ID without a study now fails
            try {
                researcherApi.createExternalId(new ExternalIdentifier().identifier(extIdA)).execute();
                fail("Should have thrown an exception");
            } catch (InvalidEntityException e) {
            }

            // Create a couple of external IDs related to different studies.
            ExternalIdentifier extId1 = new ExternalIdentifier().identifier(extIdA).studyId(STUDY_ID_1);
            ExternalIdentifier extId2 = new ExternalIdentifier().identifier(extIdB1).studyId(STUDY_ID_2);
            ExternalIdentifier extId3 = new ExternalIdentifier().identifier(extIdB2).studyId(STUDY_ID_2);
            researcherApi.createExternalId(extId1).execute();
            researcherApi.createExternalId(extId2).execute();
            researcherApi.createExternalId(extId3).execute();

            // Verify all were created
            ExternalIdentifierList list = researcherApi.getExternalIds(null, null, prefix, false).execute().body();
            boolean foundExtId1 = false;
            boolean foundExtId2 = false;
            boolean foundExtId3 = false;
            for (ExternalIdentifier id : list.getItems()) {
                if (id.getIdentifier().equals(extId1.getIdentifier())) {
                    foundExtId1 = true;
                }
                if (id.getIdentifier().equals(extId2.getIdentifier())) {
                    foundExtId2 = true;
                }
                if (id.getIdentifier().equals(extId3.getIdentifier())) {
                    foundExtId3 = true;
                }
            }
            assertTrue(foundExtId1 & foundExtId2 & foundExtId3);
            
            // Sign up a user with an external ID specified. Just one of them: we don't have plans to
            // allow the assignment of multiple external IDs on sign up. Adding new studies is probably
            // going to happen by signing additional consents, but that's TBD.
            SignUp signUp = new SignUp().appId(TEST_APP_ID);
            signUp.setPassword(Tests.PASSWORD);
            signUp.setExternalId(extIdA);
            researcher.getClient(AuthenticationApi.class).signUp(signUp).execute();

            // The created account has been associated to the external ID and its related study
            StudyParticipant participant = researcherApi.getParticipantByExternalId(extIdA, false).execute().body();
            userId = participant.getId();
            assertEquals(1, participant.getExternalIds().size());
            assertEquals(extIdA, participant.getExternalIds().get(STUDY_ID_1));
            assertEquals(1, participant.getStudyIds().size());
            assertEquals(STUDY_ID_1, participant.getStudyIds().get(0));
            assertTrue(participant.getExternalIds().values().contains(extIdA));

            // Cannot create another user with this external ID. This should do nothing and fail quietly.
            Response<Message> response = researcher.getClient(AuthenticationApi.class).signUp(signUp).execute();
            assertEquals(201, response.code());

            StudyParticipant participant2 = researcherApi.getParticipantByExternalId(extIdA, false).execute().body();
            assertEquals(userId, participant2.getId());
            
            // verify filtering by assignment since we have assigned one record
            ExternalIdentifierList all = researcherApi.getExternalIds(null, 50, prefix, null).execute().body();
            
            ExternalIdentifierList assigned = researcherApi.getExternalIds(null, 50, prefix, true).execute().body();
            assertEquals(1, assigned.getItems().size());
            for (ExternalIdentifier id : assigned.getItems()) {
                assertTrue(id.isAssigned());
            }

            // In this test only, all=2 so assigned and unassigned are both 1, but there can be external IDs
            // in the test app left over from test failures, manual testing, etc.
            ExternalIdentifierList unassigned = researcherApi.getExternalIds(null, 50, prefix, false).execute().body();
            assertEquals(all.getItems().size()-1, unassigned.getItems().size());
            for (ExternalIdentifier id : unassigned.getItems()) {
                assertFalse(id.isAssigned());
            }

            // Assign a second external ID to an existing account. This should work, and then both IDs should 
            // be usable to retrieve the account (demonstrating that this is not simply because in the interim 
            // while migrating, we're writing the external ID to the singular externalId field).
            
            SignIn signIn = new SignIn().appId(TEST_APP_ID);
            signIn.setExternalId(extIdA);
            signIn.setPassword(Tests.PASSWORD);
            
            ClientManager userManager = new ClientManager.Builder().withSignIn(signIn).build();
            
            IdentifierUpdate identifierUpdate = new IdentifierUpdate().signIn(signIn).externalIdUpdate(extIdB2);
            userManager.getClient(ParticipantsApi.class).updateUsersIdentifiers(identifierUpdate).execute();

            StudyParticipant found1 = researcherApi.getParticipantByExternalId(extIdA, false).execute().body();
            StudyParticipant found2 = researcherApi.getParticipantByExternalId(extIdB2, false).execute().body();
            assertEquals(userId, found1.getId());
            assertEquals(userId, found2.getId());
        } finally {
            App app = superadminClient.getApp(TEST_APP_ID).execute().body();
            superadminClient.updateApp(app.getIdentifier(), app).execute();
            
            if (userId != null) {
                adminClient.deleteUser(userId).execute();    
            }
            adminClient.deleteExternalId(extIdA).execute();
            adminClient.deleteExternalId(extIdB1).execute();
            adminClient.deleteExternalId(extIdB2).execute();
        }
    }

    @Test
    public void testPaging() throws Exception {
        List<ExternalIdentifier> ids = Lists.newArrayListWithCapacity(10);
        for (int i=0; i < 10; i++) {
            String studyId = (i % 2 == 0) ? STUDY_ID_1 : STUDY_ID_2;
            String identifier = (i > 5) ? ((prefix+"-foo-"+i)) : (prefix+"-"+i);
            ExternalIdentifier id = new ExternalIdentifier().identifier(identifier).studyId(studyId);
            ids.add(id);
        }
        
        ForAdminsApi adminClient = admin.getClient(ForAdminsApi.class);
        ForResearchersApi researcherApi = researcher.getClient(ForResearchersApi.class);
        TestUser user = null;
        try {
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
            
            // Create a researcher in organization 1 that sponsors only study 1, and run this stuff again, 
            // it should be filtered.
            SignUp signUp = new SignUp().appId(TEST_APP_ID);
            user = new TestUserHelper.Builder(ExternalIdsV4Test.class)
                    .withRoles(Role.RESEARCHER, Role.DEVELOPER)
                    .withConsentUser(true).withSignUp(signUp).createAndSignInUser();
            admin.getClient(OrganizationsApi.class).addMember(ORG_ID_1, user.getUserId()).execute();
            
            ForResearchersApi scopedResearcherApi = user.getClient(ForResearchersApi.class);
            ExternalIdentifierList scopedList = scopedResearcherApi.getExternalIds(null, null, null, null)
                    .execute().body();
            
            // Only five of them have the study ID
            assertEquals(5, scopedList.getItems().stream()
                    .filter(id -> id.getStudyId() != null).collect(Collectors.toList()).size());
            
            // You can also filter the ids and it maintains the study scoping
            scopedList = scopedResearcherApi.getExternalIds(null, null, prefix+"-foo-", null).execute().body();
            
            assertEquals(2, scopedList.getItems().stream()
                    .filter(id -> id.getStudyId() != null).collect(Collectors.toList()).size());
        } finally {
            if (user != null) {
                user.signOutAndDeleteUser();    
            }
            for (int i=0; i < 10; i++) {
                adminClient.deleteExternalId(ids.get(i).getIdentifier()).execute();    
            }
        }
    }

}
