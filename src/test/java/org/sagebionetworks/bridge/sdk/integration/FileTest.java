package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.sagebionetworks.bridge.rest.model.Role.DEVELOPER;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.api.ForDevelopersApi;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.model.FileMetadata;
import org.sagebionetworks.bridge.rest.model.FileMetadataList;
import org.sagebionetworks.bridge.rest.model.GuidVersionHolder;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;

public class FileTest {
    
    TestUser admin;
    TestUser developer;
    FileMetadata metadata;
    
    @Before
    public void before() throws Exception {
        admin = TestUserHelper.getSignedInAdmin();
        developer = TestUserHelper.createAndSignInUser(TemplateTest.class, true, DEVELOPER);
    }
    
    @After
    public void after() throws Exception {
        // in case of earlier test failure, clean up
        if (metadata != null && metadata.getGuid() != null) {
            ForAdminsApi adminsApi = admin.getClient(ForAdminsApi.class);
            try {
                adminsApi.deleteFile(metadata.getGuid(), true).execute();    
            } catch(EntityNotFoundException e) {
                // this is okay
            }
        }
        developer.signOutAndDeleteUser();
    }

    @Test
    public void crudFile() throws Exception {
        // create template
        metadata = new FileMetadata();
        metadata.setName("TestFile Name");
        metadata.setMimeType("application/json");
        metadata.setDescription("TestFile Description");
        metadata.setDeleted(true);
        
        ForDevelopersApi devsApi = developer.getClient(ForDevelopersApi.class);
        
        final GuidVersionHolder keys = devsApi.createFile(metadata).execute().body();
        metadata.setGuid(keys.getGuid());
        metadata.setVersion(keys.getVersion());
        
        FileMetadata retrieved = devsApi.getFile(metadata.getGuid()).execute().body();
        assertEquals(metadata.getName(), retrieved.getName());
        assertEquals(metadata.getMimeType(), retrieved.getMimeType());
        assertEquals(metadata.getDescription(), retrieved.getDescription());
        assertFalse(retrieved.isDeleted());
        
        // update template
        metadata.setName("TestTemplate Name Updated");
        metadata.setMimeType("application/json; utf-8");
        metadata.setDescription("TestTemplate Description Updated");
        metadata.setDeleted(false);
        
        GuidVersionHolder keys2 = devsApi.updateFile(metadata.getGuid(), metadata).execute().body();
        metadata.setGuid(keys2.getGuid());
        metadata.setVersion(keys2.getVersion());
        
        retrieved = devsApi.getFile(metadata.getGuid()).execute().body();
        assertEquals(metadata.getName(), retrieved.getName());
        assertEquals(metadata.getMimeType(), retrieved.getMimeType());
        assertEquals(metadata.getDescription(), retrieved.getDescription());
        assertFalse(retrieved.isDeleted());

        // logically delete file as an update, and through DELETE
        metadata.setDeleted(true);
        GuidVersionHolder keys3 = devsApi.updateFile(metadata.getGuid(), metadata).execute().body();
        metadata.setGuid(keys3.getGuid());
        metadata.setVersion(keys3.getVersion());
        
        retrieved = devsApi.getFile(metadata.getGuid()).execute().body();
        assertTrue(retrieved.isDeleted());
        
        metadata.setDeleted(false);
        GuidVersionHolder keys4 = devsApi.updateFile(metadata.getGuid(), metadata).execute().body();
        metadata.setGuid(keys4.getGuid());
        metadata.setVersion(keys4.getVersion());
        retrieved = devsApi.getFile(metadata.getGuid()).execute().body();
        assertFalse(retrieved.isDeleted());
        
        devsApi.deleteFile(metadata.getGuid(), false).execute();
        
        retrieved = devsApi.getFile(metadata.getGuid()).execute().body();
        assertTrue(retrieved.isDeleted());
        
        // get a page of templates without logical deletes
        FileMetadataList list = devsApi.getFiles(null, null, false).execute().body();
        assertTrue(list.getItems().stream().noneMatch((file -> file.getGuid().equals(keys.getGuid()))));
        
        // get a page of templates with logical deletes
        list = devsApi.getFiles(null, null, true).execute().body();
        assertTrue(list.getItems().stream().anyMatch((file -> file.getGuid().equals(keys.getGuid()))));
        
        // physically delete
        ForAdminsApi adminsApi = admin.getClient(ForAdminsApi.class);
        adminsApi.deleteFile(keys.getGuid(), true).execute();        
        
        // it's really deleted
        try {
            devsApi.getFile(keys.getGuid()).execute();
            fail("Should have thrown exception");
        } catch(EntityNotFoundException e) {

        }
    }
}
