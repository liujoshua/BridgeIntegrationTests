package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.sagebionetworks.bridge.rest.api.ForResearchersApi;
import org.sagebionetworks.bridge.rest.api.ForWorkersApi;
import org.sagebionetworks.bridge.rest.api.ParticipantsApi;
import org.sagebionetworks.bridge.rest.model.AccountSummary;
import org.sagebionetworks.bridge.rest.model.AccountSummaryList;
import org.sagebionetworks.bridge.rest.model.AccountSummarySearch;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.SignUp;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.util.IntegTestUtils;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

public class AccountSummarySearchTest {

    private static final ArrayList<String> TEST_USER_GROUPS = Lists.newArrayList("test_user", "sdk-int-1");
    private static final ArrayList<String> TAGGED_USER_GROUPS = Lists.newArrayList("group1", "sdk-int-1");
    private static final ArrayList<String> FRENCH_USER_GROUPS = Lists.newArrayList("sdk-int-1");
    private static TestUser testUser;
    private static TestUser taggedUser;
    private static TestUser frenchUser;
    
    private static TestUser researcher;
    private static TestUser worker;
    
    @BeforeClass
    public static void before() throws Exception {
        testUser = new TestUserHelper.Builder(AccountSummarySearchTest.class)
                .withSignUp((SignUp) new SignUp().email(IntegTestUtils.makeEmail(AccountSummarySearchTest.class))
                        .languages(Lists.newArrayList("es"))
                        .dataGroups(TEST_USER_GROUPS)).createUser();
        taggedUser = new TestUserHelper.Builder(AccountSummarySearchTest.class)
                .withSignUp((SignUp) new SignUp().email(IntegTestUtils.makeEmail(AccountSummarySearchTest.class))
                        .languages(Lists.newArrayList("es"))
                        .dataGroups(TAGGED_USER_GROUPS)).createUser();
        frenchUser = new TestUserHelper.Builder(AccountSummarySearchTest.class)
                .withSignUp((SignUp) new SignUp().email(IntegTestUtils.makeEmail(AccountSummarySearchTest.class))
                        .languages(Lists.newArrayList("fr"))
                        .dataGroups(FRENCH_USER_GROUPS)).createUser();
    }
    
    @AfterClass
    public static void deleteTestUser() throws Exception {
        if (testUser != null) {
           testUser.signOutAndDeleteUser();
        }
    }
    
    @AfterClass
    public static void deleteTaggedUser() throws Exception {
        if (taggedUser != null) {
           taggedUser.signOutAndDeleteUser();
        }
    }
    
    @AfterClass
    public static void deleteFrenchUser() throws Exception {
        if (frenchUser != null) {
           frenchUser.signOutAndDeleteUser();
        }
    }
    
    @AfterClass
    public static void deleteResearcher() throws Exception {
        if (researcher != null) {
           researcher.signOutAndDeleteUser();
        }
    }
    
    @AfterClass
    public static void deleteWorker() throws Exception {
        if (worker != null) {
           worker.signOutAndDeleteUser();
        }
    }
    
    @Test
    public void testSearchingApiForResearcher() throws Exception {
        researcher = TestUserHelper.createAndSignInUser(AccountSummarySearchTest.class, false, Role.RESEARCHER);
        ForResearchersApi researcherApi = researcher.getClient(ForResearchersApi.class);

        testSuite(search -> researcherApi.searchAccountSummaries(search).execute().body());
    }
    
    @Test
    public void testSearchForParticipantApi() throws Exception {
        researcher = TestUserHelper.createAndSignInUser(AccountSummarySearchTest.class, false, Role.RESEARCHER);
        ParticipantsApi participantsApi = researcher.getClient(ParticipantsApi.class);

        testSuite(search -> participantsApi.searchAccountSummaries(search).execute().body());
    }
    
    @Test
    public void testSearchingApiForWorker() throws Exception {
        worker = TestUserHelper.createAndSignInUser(AccountSummarySearchTest.class, false, Role.WORKER);
        ForWorkersApi workerApi = worker.getClient(ForWorkersApi.class);
        
        testSuite(search -> workerApi.searchAccountSummaries("api", search).execute().body());
    }
    
    @FunctionalInterface
    public abstract interface ThrowingFunction<S,T> {
        public T apply(S s) throws Exception;
    }
    
    private void testSuite(ThrowingFunction<AccountSummarySearch, AccountSummaryList> supplier) throws Exception {
        // Successful language search
        AccountSummarySearch search = new AccountSummarySearch().language("fr");
        AccountSummaryList list = supplier.apply(search);
        Set<String> userIds = mapUserIds(list);
        assertFalse(userIds.contains(testUser.getUserId()));
        assertFalse(userIds.contains(taggedUser.getUserId()));
        assertTrue(userIds.contains(frenchUser.getUserId()));
        assertEquals("fr", list.getRequestParams().getLanguage());
        
        // Unsuccessful language search
        search = new AccountSummarySearch().language("en");
        list = supplier.apply(search);
        userIds = mapUserIds(list);
        assertFalse(userIds.contains(testUser.getUserId()));
        assertFalse(userIds.contains(taggedUser.getUserId()));
        assertFalse(userIds.contains(frenchUser.getUserId()));
        assertEquals("en", list.getRequestParams().getLanguage());
        
        // Successful "allOfGroups" search
        search = new AccountSummarySearch().allOfGroups(TAGGED_USER_GROUPS);
        list = supplier.apply(search);
        userIds = mapUserIds(list);
        assertFalse(userIds.contains(testUser.getUserId()));
        assertTrue(userIds.contains(taggedUser.getUserId()));
        assertFalse(userIds.contains(frenchUser.getUserId()));
        listsMatch(TAGGED_USER_GROUPS, list.getRequestParams().getAllOfGroups());
        
        // This is a data group that spans all three accounts..
        search = new AccountSummarySearch().allOfGroups(FRENCH_USER_GROUPS);
        list = supplier.apply(search);
        userIds = mapUserIds(list);
        assertTrue(userIds.contains(testUser.getUserId()));
        assertTrue(userIds.contains(taggedUser.getUserId()));
        assertTrue(userIds.contains(frenchUser.getUserId()));
        listsMatch(FRENCH_USER_GROUPS, list.getRequestParams().getAllOfGroups());
        
        // This pulls up nothing
        search = new AccountSummarySearch().allOfGroups(Lists.newArrayList("sdk-int-2"));
        list = supplier.apply(search);
        userIds = mapUserIds(list);
        assertFalse(userIds.contains(testUser.getUserId()));
        assertFalse(userIds.contains(taggedUser.getUserId()));
        assertFalse(userIds.contains(frenchUser.getUserId()));
        listsMatch(Lists.newArrayList("sdk-int-2"), list.getRequestParams().getAllOfGroups());
        
        // Successful "noneOfGroups" search
        search = new AccountSummarySearch().noneOfGroups(Lists.newArrayList("group1"));
        list = supplier.apply(search);
        userIds = mapUserIds(list);
        assertTrue(userIds.contains(testUser.getUserId()));
        assertFalse(userIds.contains(taggedUser.getUserId()));
        assertTrue(userIds.contains(frenchUser.getUserId()));
        listsMatch(Lists.newArrayList("group1"), list.getRequestParams().getNoneOfGroups());
        
        // This is a data group that spans all three accounts..
        search = new AccountSummarySearch().noneOfGroups(FRENCH_USER_GROUPS);
        list = supplier.apply(search);
        userIds = mapUserIds(list);
        assertFalse(userIds.contains(testUser.getUserId()));
        assertFalse(userIds.contains(taggedUser.getUserId()));
        assertFalse(userIds.contains(frenchUser.getUserId()));
        listsMatch(FRENCH_USER_GROUPS, list.getRequestParams().getNoneOfGroups());
        
        // This pulls up everything we're looking for
        search = new AccountSummarySearch().noneOfGroups(Lists.newArrayList("sdk-int-2"));
        list = supplier.apply(search);
        userIds = mapUserIds(list);
        assertTrue(userIds.contains(testUser.getUserId()));
        assertTrue(userIds.contains(taggedUser.getUserId()));
        assertTrue(userIds.contains(frenchUser.getUserId()));
        listsMatch(Lists.newArrayList("sdk-int-2"), list.getRequestParams().getNoneOfGroups());
        
        // mixed works
        search = new AccountSummarySearch().allOfGroups(Lists.newArrayList("sdk-int-1"))
                .noneOfGroups(Lists.newArrayList("group1"));
        list = supplier.apply(search);
        userIds = mapUserIds(list);
        assertTrue(userIds.contains(testUser.getUserId()));
        assertFalse(userIds.contains(taggedUser.getUserId()));
        assertTrue(userIds.contains(frenchUser.getUserId()));
        listsMatch(Lists.newArrayList("sdk-int-1"), list.getRequestParams().getAllOfGroups());
        listsMatch(Lists.newArrayList("group1"), list.getRequestParams().getNoneOfGroups());
        
        search = new AccountSummarySearch().allOfGroups(Lists.newArrayList("sdk-int-1")).language("fr")
                .noneOfGroups(Lists.newArrayList("sdk-int-2"));
        list = supplier.apply(search);
        userIds = mapUserIds(list);
        assertFalse(userIds.contains(testUser.getUserId()));
        assertFalse(userIds.contains(taggedUser.getUserId()));
        assertTrue(userIds.contains(frenchUser.getUserId()));
    }
    
    private void listsMatch(List<String> list1, List<String> list2) {
        if (list1.size() != list2.size()) {
            fail("Lists are not the same size, cannot be equal");
        }
        assertEquals(Sets.newHashSet(list1), Sets.newHashSet(list2));
    }
    
    private Set<String> mapUserIds(AccountSummaryList list) {
        return list.getItems().stream().map(AccountSummary::getId).collect(Collectors.toSet());
    }
}
