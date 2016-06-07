package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.List;

import org.apache.commons.lang3.RandomStringUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.sdk.ExternalIdentifiersClient;
import org.sagebionetworks.bridge.sdk.Roles;
import org.sagebionetworks.bridge.sdk.integration.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.sdk.models.PagedResourceList;
import org.sagebionetworks.bridge.sdk.models.accounts.ExternalIdentifier;

import com.google.common.collect.Lists;

public class ExternalIdsTest {

    private static final int LIST_SIZE = 10;
    private static final int PAGE_SIZE = (LIST_SIZE/2);
    private static TestUser developer;
    
    @Before
    public void before() {
        developer = TestUserHelper.createAndSignInUser(ExternalIdsTest.class, false, Roles.DEVELOPER);
    }
    
    @After
    public void after() {
        if (developer != null) {
            developer.signOutAndDeleteUser();    
        }
    }
    
    @Test
    public void canCRUDExternalIds() {
        // In order to prevent this test conflicting with other tests, each identifier is "namespaced" 
        // with a random prefix that we'll use in all the queries.
        String prefix = RandomStringUtils.randomAlphabetic(4);
        
        List<String> identifiers = Lists.newArrayListWithCapacity(LIST_SIZE);
        for (int i=0; i < LIST_SIZE; i++) {
            identifiers.add(prefix+RandomStringUtils.randomAlphabetic(10));
        }
        ExternalIdentifiersClient externalIdsClient = developer.getSession().getExternalIdentifiersClient();
        externalIdsClient.addExternalIds(identifiers);
        try {
            PagedResourceList<ExternalIdentifier> page1 = externalIdsClient.getExternalIds(null, PAGE_SIZE, prefix, null);
            assertEquals(PAGE_SIZE, page1.getItems().size());
            assertEquals(LIST_SIZE, page1.getTotal());
            assertNotNull(page1.getOffsetKey());
            
            String offsetKey = page1.getOffsetKey();    
            PagedResourceList<ExternalIdentifier> page2 = externalIdsClient.getExternalIds(offsetKey, PAGE_SIZE, prefix, null);
            assertEquals(PAGE_SIZE, page2.getItems().size());
            assertEquals(LIST_SIZE, page2.getTotal());
            assertNull(page2.getOffsetKey()); // no more pages
            
            // pageKey test. two pages should have no members in common;
            assertTrue(Collections.disjoint(page1.getItems(), page2.getItems()));

            // assignment filter test
            page1 = externalIdsClient.getExternalIds(null, null, prefix, Boolean.FALSE);
            assertEquals(LIST_SIZE, page1.getTotal());
            
            page1 = externalIdsClient.getExternalIds(null, null, prefix, Boolean.TRUE);
            assertEquals(0, page1.getTotal());
            
            // different idFilter test (than the use of idFilter in all the tests. This time nothing should match.
            page1 = externalIdsClient.getExternalIds(null, PAGE_SIZE, RandomStringUtils.randomAlphabetic(5), null);
            assertEquals(0, page1.getTotal());
        } finally {
            externalIdsClient.deleteExternalIds(identifiers);
        }
        PagedResourceList<ExternalIdentifier> page = externalIdsClient.getExternalIds(null, null, prefix, null);
        assertEquals(0, page.getTotal());
    }
    
}
