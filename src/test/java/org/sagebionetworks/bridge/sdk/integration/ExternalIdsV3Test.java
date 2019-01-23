package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import org.apache.commons.lang3.RandomStringUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.api.ExternalIdentifiersApi;
import org.sagebionetworks.bridge.rest.model.ExternalIdentifierList;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;

import com.google.common.collect.Lists;

public class ExternalIdsV3Test {

    private static final int LIST_SIZE = 10;
    private static final int PAGE_SIZE = (LIST_SIZE/2);
    private static TestUser developer;
    
    @Before
    public void before() throws IOException {
        developer = TestUserHelper.createAndSignInUser(ExternalIdsV3Test.class, false, Role.DEVELOPER);
    }
    
    @After
    public void after() throws Exception {
        if (developer != null) {
            developer.signOutAndDeleteUser();    
        }
    }
    
    @Test
    public void canCRUDExternalIds() throws IOException {
        // In order to prevent this test conflicting with other tests, each identifier is "namespaced" 
        // with a random prefix that we'll use in all the queries.
        String prefix = RandomStringUtils.randomAlphabetic(4);
        
        List<String> identifiers = Lists.newArrayListWithCapacity(LIST_SIZE);
        for (int i=0; i < LIST_SIZE; i++) {
            identifiers.add(prefix+RandomStringUtils.randomAlphabetic(10));
        }
        ExternalIdentifiersApi externalIdsClient = developer.getClient(ExternalIdentifiersApi.class);
        externalIdsClient.addExternalIdsV3(identifiers).execute();
        try {
            ExternalIdentifierList page1 = externalIdsClient.getExternalIdsV3(null, PAGE_SIZE, prefix, null)
                    .execute().body();
            assertEquals(PAGE_SIZE, page1.getItems().size());
            assertNotNull(page1.getNextPageOffsetKey());
            
            String offsetKey = page1.getNextPageOffsetKey();
            ExternalIdentifierList page2 = externalIdsClient
                    .getExternalIdsV3(offsetKey, PAGE_SIZE, prefix, null).execute().body();
            assertEquals(PAGE_SIZE, page2.getItems().size());
            assertNull(page2.getNextPageOffsetKey()); // no more pages
            
            // pageKey test. two pages should have no members in common;
            assertTrue(Collections.disjoint(page1.getItems(), page2.getItems()));
            
            // assignment filter test
            page1 = externalIdsClient.getExternalIdsV3(null, null, prefix, Boolean.FALSE).execute().body();
            assertEquals(LIST_SIZE, page1.getItems().size());

            page1 = externalIdsClient.getExternalIdsV3(null, null, prefix, Boolean.TRUE).execute().body();
            assertEquals(0, page1.getItems().size());
            
            // different idFilter test (than the use of idFilter in all the tests. This time nothing should match.
            page1 = externalIdsClient.getExternalIdsV3(null, PAGE_SIZE, RandomStringUtils.randomAlphabetic(5), null)
                    .execute().body();
            assertEquals(0, page1.getItems().size());
        } finally {
            externalIdsClient.deleteExternalIdsV3(identifiers).execute();
        }
        ExternalIdentifierList page = externalIdsClient.getExternalIdsV3(null, null, prefix, null).execute().body();
        assertEquals(0, page.getItems().size());
    }
    
}
