package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.fail;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sagebionetworks.bridge.rest.ClientManager;
import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.api.ForResearchersApi;
import org.sagebionetworks.bridge.rest.api.SubstudiesApi;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.model.AccountSummary;
import org.sagebionetworks.bridge.rest.model.AccountSummaryList;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.SignIn;
import org.sagebionetworks.bridge.rest.model.SignUp;
import org.sagebionetworks.bridge.rest.model.Substudy;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.util.IntegTestUtils;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

public class SubstudyFilteringTest {
    public static class UserInfo {
        private final String userId;
        private final SignIn signIn;
        public UserInfo(String userId, String email) {
            this.userId = userId;
            this.signIn = new SignIn().email(email).password(Tests.PASSWORD).study(IntegTestUtils.STUDY_ID);
        }
        public String getUserId() { return userId; }
        public SignIn getSignIn() { return signIn; }
    }

    private TestUser admin;
    private SubstudiesApi substudiesApi;
    private Set<String> substudyIdsToDelete;
    private Set<String> userIdsToDelete;
    
    @Before
    public void before() { 
        admin = TestUserHelper.getSignedInAdmin();
        substudiesApi = admin.getClient(SubstudiesApi.class);
        
        substudyIdsToDelete = new HashSet<>();
        userIdsToDelete = new HashSet<>();
    }

    @After
    public void after() {
        ForAdminsApi adminsApi = admin.getClient(ForAdminsApi.class);
        for (String userId : userIdsToDelete) {
            try {
                adminsApi.deleteUser(userId).execute();    
            } catch(Exception e) {
                e.printStackTrace();
            }
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
    public void test() throws Exception { 
        // Create two substudies
        String idA = createSubstudy();
        String idB = createSubstudy();
        
        // Create a researcher in substudy A
        SignIn researcherA = createUser(Role.RESEARCHER, idA).getSignIn();
        SignIn researcherB = createUser(Role.RESEARCHER, idB).getSignIn();
        
        // Create three accounts, one A, one B, one AB, one with nothing
        String userIdA = createUser(null, idA).getUserId();
        String userIdB = createUser(null, idB).getUserId();
        String userIdAB = createUser(null, idA, idB).getUserId();
        
        // researcherA
        ClientManager manager = new ClientManager.Builder().withSignIn(researcherA).build();
        ForResearchersApi researcherApi = manager.getClient(ForResearchersApi.class);
        
        // This researcher sees A substudy users only
        AccountSummaryList list = researcherApi.getParticipants(null, null, null, null, null, null).execute().body();
        assertAccountHasSubstudies(ImmutableSet.of(idA), list.getItems());
        assertAccountHasSubstudies(ImmutableSet.of(idA, idB), list.getItems());
        
        // researcherB
        manager = new ClientManager.Builder().withSignIn(researcherB).build();
        researcherApi = manager.getClient(ForResearchersApi.class);
        
        // This researcher sees B substudy users only
        list = researcherApi.getParticipants(null, null, null, null, null, null).execute().body();
        assertAccountHasSubstudies(ImmutableSet.of(idB), list.getItems());
        assertAccountHasSubstudies(ImmutableSet.of(idA, idB), list.getItems());
        
        // Researcher B should not be able to get a substudy A account
        try {
            researcherApi.getParticipantById(userIdA, false).execute();
            fail("Should have thrown exception");
        } catch(EntityNotFoundException e) {
        }
        // Researcher B should be able to get only substudy B accounts (even if mixed)
        researcherApi.getParticipantById(userIdB, false).execute().body();
        researcherApi.getParticipantById(userIdAB, false).execute().body();
        
        // This should apply to any call involving user in another substudy, try one (fully tested in 
        // the unit tests)
        try {
            researcherApi.getActivityEvents(userIdA).execute();
            fail("Should have thrown exception");
        } catch(EntityNotFoundException e) {
        }
    }
    
    private void assertAccountHasSubstudies(Set<String> substudies, List<AccountSummary> summaries) {
        for (AccountSummary summary : summaries) {
            Set<String> summaryIds = ImmutableSet.copyOf(summary.getSubstudyIds());
            if (summaryIds.equals(substudies)) {
                return;
            }
        }
        fail("Did not find account with substudies: " + Joiner.on(", ").join(substudies));
    }
    
    private String createSubstudy() throws Exception {
        String id = Tests.randomIdentifier(SubstudyTest.class);
        Substudy substudyA = new Substudy().id(id).name("Substudy Filtering Test");
        substudiesApi.createSubstudy(substudyA).execute();
        substudyIdsToDelete.add(id);
        return id;
    }
    
    private UserInfo createUser(Role role, String... substudyIds) throws Exception {
        String email = IntegTestUtils.makeEmail(SubstudyTest.class);
        SignUp signUp = new SignUp().email(email).password(Tests.PASSWORD).study(IntegTestUtils.STUDY_ID);
        if (role != null) {
            signUp.setRoles(ImmutableList.of(role));
        }
        signUp.substudyIds(ImmutableList.copyOf(substudyIds));
                
        ForAdminsApi adminApi = admin.getClient(ForAdminsApi.class);
        String userId = adminApi.createUser(signUp).execute().body().getId();
        userIdsToDelete.add(userId);
        return new UserInfo(userId, email);
    }
}
