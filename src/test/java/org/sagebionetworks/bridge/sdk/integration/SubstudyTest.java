package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;

import org.joda.time.DateTime;
import org.junit.Test;
import org.sagebionetworks.bridge.rest.api.SubstudiesApi;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.model.Substudy;
import org.sagebionetworks.bridge.rest.model.SubstudyList;
import org.sagebionetworks.bridge.rest.model.VersionHolder;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;

public class SubstudyTest {

    @Test
    public void test() throws IOException {
        TestUser admin = TestUserHelper.getSignedInAdmin();
        
        SubstudiesApi substudiesApi = admin.getClient(SubstudiesApi.class);
        
        String id = Tests.randomIdentifier(SubstudyTest.class);
        
        Substudy substudy = new Substudy().id(id).name("Substudy Test");
        
        VersionHolder holder = substudiesApi.createSubstudy(substudy).execute().body();
        substudy.setVersion(holder.getVersion());
        
        Substudy retrieved = substudiesApi.getSubstudy(id).execute().body();
        assertEquals(id, retrieved.getId());
        assertEquals("Substudy Test", retrieved.getName());
        assertTrue(retrieved.getCreatedOn().isAfter(DateTime.now().minusHours(1)));
        assertTrue(retrieved.getModifiedOn().isAfter(DateTime.now().minusHours(1)));
        DateTime lastModified1 = retrieved.getModifiedOn();
        
        substudy.name("New test name");
        VersionHolder holder2 = substudiesApi.updateSubstudy(id, substudy).execute().body();
        assertNotEquals(holder.getVersion(), holder2.getVersion());
        
        Substudy retrieved2 = substudiesApi.getSubstudy(id).execute().body();
        assertEquals("New test name", retrieved2.getName());
        assertNotEquals(lastModified1, retrieved2.getModifiedOn());
        
        SubstudyList list = substudiesApi.getSubstudies(false).execute().body();
        assertEquals(1, list.getItems().size());
        assertFalse(list.getRequestParams().isIncludeDeleted());
        
        // logically delete it
        substudiesApi.deleteSubstudy(id, false).execute();
        
        list = substudiesApi.getSubstudies(false).execute().body();
        assertTrue(list.getItems().isEmpty());
        
        list = substudiesApi.getSubstudies(true).execute().body();
        assertEquals(1, list.getItems().size());
        assertTrue(list.getRequestParams().isIncludeDeleted());
        
        // you can still retrieve it
        Substudy retrieved3 = substudiesApi.getSubstudy(id).execute().body();
        assertNotNull(retrieved3);
        
        // physically delete it
        substudiesApi.deleteSubstudy(id, true).execute();
        
        // Now it's really gone
        list = substudiesApi.getSubstudies(true).execute().body();
        assertTrue(list.getItems().isEmpty());
        
        try {
            substudiesApi.getSubstudy(id).execute();
            fail("Should have thrown an exception");
        } catch(EntityNotFoundException e) {
        }
    }
    
}
