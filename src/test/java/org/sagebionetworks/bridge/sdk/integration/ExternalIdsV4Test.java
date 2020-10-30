package org.sagebionetworks.bridge.sdk.integration;

import static java.util.stream.Collectors.toSet;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.sagebionetworks.bridge.rest.model.Role.DEVELOPER;
import static org.sagebionetworks.bridge.rest.model.Role.RESEARCHER;
import static org.sagebionetworks.bridge.sdk.integration.Tests.ORG_ID_1;
import static org.sagebionetworks.bridge.sdk.integration.Tests.STUDY_ID_1;
import static org.sagebionetworks.bridge.sdk.integration.Tests.STUDY_ID_2;
import static org.sagebionetworks.bridge.util.IntegTestUtils.TEST_APP_ID;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.RandomStringUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.api.AuthenticationApi;
import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.api.ForResearchersApi;
import org.sagebionetworks.bridge.rest.api.ForSuperadminsApi;
import org.sagebionetworks.bridge.rest.api.OrganizationsApi;
import org.sagebionetworks.bridge.rest.api.ParticipantsApi;
import org.sagebionetworks.bridge.rest.api.StudiesApi;
import org.sagebionetworks.bridge.rest.model.App;
import org.sagebionetworks.bridge.rest.model.Enrollment;
import org.sagebionetworks.bridge.rest.model.ExternalIdentifier;
import org.sagebionetworks.bridge.rest.model.ExternalIdentifierList;
import org.sagebionetworks.bridge.rest.model.IdentifierHolder;
import org.sagebionetworks.bridge.rest.model.Message;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.SignUp;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;

import retrofit2.Response;

public class ExternalIdsV4Test {

    private String prefix;
    private TestUser admin;
    private TestUser researcher;
    private Set<String> usersToDelete = new HashSet<>();

    @Before
    public void before() throws Exception {
        prefix = RandomStringUtils.randomAlphabetic(5);
        admin = TestUserHelper.getSignedInAdmin();
        researcher = TestUserHelper.createAndSignInUser(ExternalIdsV4Test.class, true, Role.RESEARCHER);
    }

    @After
    public void after() throws Exception {
        for (String userId : usersToDelete) {
            admin.getClient(ForAdminsApi.class).deleteUser(userId).execute();
        }
        researcher.signOutAndDeleteUser();
    }

    @Test
    public void test() throws Exception {
        final String extIdA = prefix+Tests.randomIdentifier(ExternalIdsV4Test.class);
        final String extIdB1 = prefix+Tests.randomIdentifier(ExternalIdsV4Test.class);
        final String extIdB2 = prefix+Tests.randomIdentifier(ExternalIdsV4Test.class);

        ForSuperadminsApi superadminClient = admin.getClient(ForSuperadminsApi.class);
        ForResearchersApi researcherApi = researcher.getClient(ForResearchersApi.class);
        String userId1 = null;
        try {
            App app = superadminClient.getApp(TEST_APP_ID).execute().body();
            superadminClient.updateApp(app.getIdentifier(), app).execute();

            // Create a couple of external IDs related to different studies.
            userId1 = researcherApi.createParticipant(new SignUp().externalIds(ImmutableMap.of(STUDY_ID_1, extIdA))).execute().body().getIdentifier();
            usersToDelete.add(userId1);
            // researcherApi.createParticipant(new SignUp().externalIds(ImmutableMap.of(STUDY_ID_2, extIdB1))).execute().body().getIdentifier();
            // researcherApi.createParticipant(new SignUp().externalIds(ImmutableMap.of(STUDY_ID_2, extIdB2))).execute().body().getIdentifier();

            // The created account has been associated to the external ID and its related study
            StudyParticipant participant = researcherApi.getParticipantByExternalId(extIdA, false).execute().body();
            assertEquals(1, participant.getExternalIds().size());
            assertEquals(extIdA, participant.getExternalIds().get(STUDY_ID_1));
            assertEquals(1, participant.getStudyIds().size());
            assertEquals(STUDY_ID_1, participant.getStudyIds().get(0));
            assertTrue(participant.getExternalIds().values().contains(extIdA));

            // Cannot create another user with this external ID. This should do nothing and fail quietly.
            SignUp signUp = new SignUp().appId(TEST_APP_ID).externalIds(ImmutableMap.of(STUDY_ID_1, extIdA));
            researcher.getClient(AuthenticationApi.class).signUp(signUp).execute();
            
            Response<Message> response = researcher.getClient(AuthenticationApi.class).signUp(signUp).execute();
            assertEquals(201, response.code());

            // ID wasn't changed
            StudyParticipant participant2 = researcherApi.getParticipantByExternalId(extIdA, false).execute().body();
            assertEquals(userId1, participant2.getId());
            
            // Assign a second external ID to an existing account. This should work, and then both IDs should 
            // be usable to retrieve the account (demonstrating that this is not simply because in the interim 
            // while migrating, we're writing the external ID to the singular externalId field).
            admin.getClient(StudiesApi.class).enrollParticipant(STUDY_ID_2, 
                    new Enrollment().externalId(extIdB1).userId(userId1)).execute();

            StudyParticipant found1 = researcherApi.getParticipantByExternalId(extIdA, false).execute().body();
            StudyParticipant found2 = researcherApi.getParticipantByExternalId(extIdB1, false).execute().body();
            assertEquals(userId1, found1.getId());
            assertEquals(userId1, found2.getId());
            
            // Add one more
            String userId2 = researcherApi.createParticipant(new SignUp()
                    .externalIds(ImmutableMap.of(STUDY_ID_2, extIdB2))).execute().body().getIdentifier();
            usersToDelete.add(userId2);
            
            // Verify all are accessible through the study-specific external IDs API
            ExternalIdentifierList list1 = researcherApi.getExternalIdsForStudy(STUDY_ID_1, null, null, prefix).execute().body();
            assertEquals(ImmutableSet.of(extIdA), list1.getItems().stream()
                    .map(ExternalIdentifier::getIdentifier).collect(toSet()));
            
            ExternalIdentifierList list2 = researcherApi.getExternalIdsForStudy(STUDY_ID_2, null, null, prefix).execute().body();
            assertEquals(ImmutableSet.of(extIdB1, extIdB2), list2.getItems().stream()
                    .map(ExternalIdentifier::getIdentifier).collect(toSet()));
        } finally {
            App app = superadminClient.getApp(TEST_APP_ID).execute().body();
            superadminClient.updateApp(app.getIdentifier(), app).execute();
        }
    }

    @Test
    public void testPaging() throws Exception {
        ParticipantsApi participantsApi = admin.getClient(ParticipantsApi.class);
        List<String> extIds = Lists.newArrayListWithCapacity(10);
        for (int i=0; i < 10; i++) {
            String identifier = (i > 5) ? ((prefix+"-foo-"+i)) : (prefix+"-"+i);
            IdentifierHolder userId = participantsApi.createParticipant(
                    new SignUp().externalIds(ImmutableMap.of(STUDY_ID_1, identifier))).execute().body();
            usersToDelete.add(userId.getIdentifier());
            extIds.add(identifier);
        }
        String prefix2 = prefix + "2";
        for (int i=0; i < 10; i++) {
            String identifier = (i > 5) ? ((prefix2+"-foo-"+i)) : (prefix2+"-"+i);
            IdentifierHolder userId = participantsApi.createParticipant(
                    new SignUp().externalIds(ImmutableMap.of(STUDY_ID_2, identifier))).execute().body();
            usersToDelete.add(userId.getIdentifier());
            extIds.add(identifier);
        }
        ForResearchersApi researcherApi = researcher.getClient(ForResearchersApi.class);
        TestUser user = null;
        try {
            // pageSize=3, should have 4 pages 
            ExternalIdentifierList list = researcherApi.getExternalIdsForStudy(STUDY_ID_1, null, 3, prefix).execute().body();
            assertEquals(3, list.getItems().size());
            assertEquals(extIds.get(0), list.getItems().get(0).getIdentifier());
            assertEquals(extIds.get(1), list.getItems().get(1).getIdentifier());
            assertEquals(extIds.get(2), list.getItems().get(2).getIdentifier());
            
            list = researcherApi.getExternalIdsForStudy(STUDY_ID_1, 3, 3, prefix).execute().body();
            assertEquals(3, list.getItems().size());
            assertEquals(extIds.get(3), list.getItems().get(0).getIdentifier());
            assertEquals(extIds.get(4), list.getItems().get(1).getIdentifier());
            assertEquals(extIds.get(5), list.getItems().get(2).getIdentifier());
            
            list = researcherApi.getExternalIdsForStudy(STUDY_ID_1, 6, 3, prefix).execute().body();
            assertEquals(3, list.getItems().size());
            assertEquals(extIds.get(6), list.getItems().get(0).getIdentifier());
            assertEquals(extIds.get(7), list.getItems().get(1).getIdentifier());
            assertEquals(extIds.get(8), list.getItems().get(2).getIdentifier());
            
            list = researcherApi.getExternalIdsForStudy(STUDY_ID_1, 9, 3, prefix).execute().body();
            assertEquals(1, list.getItems().size());
            assertEquals(extIds.get(9), list.getItems().get(0).getIdentifier());
            
            // pageSize = 10, one page with no page offset
            list = researcherApi.getExternalIdsForStudy(STUDY_ID_1, null, 10, prefix).execute().body();
            assertEquals(10, list.getItems().size());
            assertEquals(new Integer(0), list.getRequestParams().getOffsetBy());
            for (int i=0; i < 10; i++) {
                assertEquals(extIds.get(i), list.getItems().get(i).getIdentifier());
            }
            
            // pageSize = 30, same thing
            list = researcherApi.getExternalIdsForStudy(STUDY_ID_1, null, 30, prefix).execute().body();
            assertEquals(10, list.getItems().size());
            assertEquals(new Integer(0), list.getRequestParams().getOffsetBy());
            for (int i=0; i < 10; i++) {
                assertEquals(extIds.get(i), list.getItems().get(i).getIdentifier());
            }
            
            // Create a researcher in org 1 that sponsors only study 1, and retrieving external IDs
            // should be filtered
            SignUp signUp = new SignUp().appId(TEST_APP_ID);
            user = new TestUserHelper.Builder(ExternalIdsV4Test.class).withRoles(RESEARCHER, DEVELOPER)
                    .withConsentUser(true).withSignUp(signUp).createAndSignInUser();
            admin.getClient(OrganizationsApi.class).addMember(ORG_ID_1, user.getUserId()).execute();
            
            ForResearchersApi scopedResearcherApi = user.getClient(ForResearchersApi.class);
            ExternalIdentifierList scopedList = scopedResearcherApi.getExternalIdsForStudy(STUDY_ID_1, null, null, null)
                    .execute().body();
            
            // Only ten of them have the study ID
            assertEquals(10, scopedList.getItems().size());
            
            // You can also filter the ids and it maintains the study scoping
            scopedList = scopedResearcherApi.getExternalIdsForStudy(STUDY_ID_1, null, null, prefix+"-foo-").execute().body();
            
            assertEquals(4, scopedList.getItems().stream()
                    .filter(id -> id.getStudyId() != null).collect(Collectors.toList()).size());
        } finally {
            for (String userId : extIds) {
                admin.getClient(ForAdminsApi.class).deleteUser(userId).execute();
            }
            if (user != null) {
                user.signOutAndDeleteUser();
            }
        }
    }

}
