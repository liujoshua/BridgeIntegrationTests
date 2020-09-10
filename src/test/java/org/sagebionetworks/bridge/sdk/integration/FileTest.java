package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.sagebionetworks.bridge.rest.model.FileRevisionStatus.AVAILABLE;
import static org.sagebionetworks.bridge.rest.model.FileRevisionStatus.PENDING;
import static org.sagebionetworks.bridge.rest.model.Role.DEVELOPER;

import java.io.File;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.RestUtils;
import org.sagebionetworks.bridge.rest.api.FilesApi;
import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.api.ForDevelopersApi;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.model.FileMetadata;
import org.sagebionetworks.bridge.rest.model.FileMetadataList;
import org.sagebionetworks.bridge.rest.model.FileRevision;
import org.sagebionetworks.bridge.rest.model.FileRevisionList;
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
        developer = TestUserHelper.createAndSignInUser(FileTest.class, false, DEVELOPER);
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
        metadata.setDescription("TestFile Description");
        metadata.setDeleted(true);
        
        ForDevelopersApi devsApi = developer.getClient(ForDevelopersApi.class);
        
        final GuidVersionHolder keys = devsApi.createFile(metadata).execute().body();
        metadata.setGuid(keys.getGuid());
        metadata.setVersion(keys.getVersion());
        
        FileMetadata retrieved = devsApi.getFile(metadata.getGuid()).execute().body();
        assertEquals(metadata.getName(), retrieved.getName());
        assertEquals(metadata.getDescription(), retrieved.getDescription());
        assertFalse(retrieved.isDeleted());
        
        // update template
        metadata.setName("TestTemplate Name Updated");
        metadata.setDescription("TestTemplate Description Updated");
        metadata.setDeleted(false);
        
        GuidVersionHolder keys2 = devsApi.updateFile(metadata.getGuid(), metadata).execute().body();
        metadata.setGuid(keys2.getGuid());
        metadata.setVersion(keys2.getVersion());
        
        retrieved = devsApi.getFile(metadata.getGuid()).execute().body();
        assertEquals(metadata.getName(), retrieved.getName());
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
    
    @Test
    public void canCrudFileRevision() throws Exception {
        try {
            metadata = new FileMetadata();
            metadata.setName("TestFile Name");
            metadata.setDescription("TestFile Description");
            metadata.setDeleted(true);
            
            ForDevelopersApi devsApi = developer.getClient(ForDevelopersApi.class);
            
            final GuidVersionHolder keys = devsApi.createFile(metadata).execute().body();
            metadata.setGuid(keys.getGuid());
            metadata.setVersion(keys.getVersion());
    
            FilesApi filesApi = developer.getClient(FilesApi.class);
            File file = new File("src/test/resources/file-test/test.pdf");
            String url = RestUtils.uploadHostedFileToS3(filesApi, metadata.getGuid(), file);
            
            FileRevisionList list = devsApi.getFileRevisions(metadata.getGuid(), 0, 5).execute().body();
            FileRevision rev = list.getItems().get(0);
            assertEquals(url, rev.getDownloadURL());
            assertEquals("test.pdf", rev.getName());
            assertEquals("application/pdf", rev.getMimeType());
            assertTrue(rev.getSize() > 0L);
            assertEquals(rev.getStatus(), AVAILABLE);
            
            FileRevision oneRev = devsApi.getFileRevision(metadata.getGuid(), rev.getCreatedOn()).execute().body();
            assertEquals(url, oneRev.getDownloadURL());
            assertEquals("test.pdf", oneRev.getName());
            assertEquals("application/pdf", oneRev.getMimeType());
            assertTrue(oneRev.getSize() > 0L);
            assertEquals(AVAILABLE, oneRev.getStatus());
            
            // verify the pending object too
            FileRevision revision = new FileRevision();
            revision.setFileGuid(rev.getFileGuid());
            revision.setName(file.getName());
            revision.setMimeType("application/pdf");
            FileRevision updated = devsApi.createFileRevision(rev.getFileGuid(), revision).execute().body();
            assertEquals(updated.getStatus(), PENDING);
            assertTrue(updated.getUploadURL() != null);
            assertTrue(updated.getDownloadURL() != null);
            
        } finally {
            if (metadata != null) {
                ForAdminsApi adminsApi = admin.getClient(ForAdminsApi.class);
                adminsApi.deleteFile(metadata.getGuid(), true).execute();        
            }
        }
    }
}
