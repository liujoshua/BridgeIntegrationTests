package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.sagebionetworks.bridge.rest.model.MimeType.TEXT_PLAIN;
import static org.sagebionetworks.bridge.rest.model.Role.DEVELOPER;
import static org.sagebionetworks.bridge.rest.model.TemplateType.EMAIL_ACCOUNT_EXISTS;

import com.google.common.collect.ImmutableMap;

import org.joda.time.DateTime;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.api.ForDevelopersApi;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.model.Criteria;
import org.sagebionetworks.bridge.rest.model.GuidVersionHolder;
import org.sagebionetworks.bridge.rest.model.Template;
import org.sagebionetworks.bridge.rest.model.TemplateList;
import org.sagebionetworks.bridge.rest.model.TemplateRevision;
import org.sagebionetworks.bridge.rest.model.TemplateRevisionList;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;

public class TemplateTest {
    
    TestUser admin;
    TestUser developer;
    Template template;
    
    @Before
    public void before() throws Exception {
        admin = TestUserHelper.getSignedInAdmin();
        developer = TestUserHelper.createAndSignInUser(TemplateTest.class, true, DEVELOPER);
    }
    
    @After
    public void after() throws Exception {
        // in case of earlier test failure, clean up
        if (template != null && template.getGuid() != null) {
            ForAdminsApi adminsApi = admin.getClient(ForAdminsApi.class);
            try {
                adminsApi.deleteTemplate(template.getGuid(), true).execute();    
            } catch(EntityNotFoundException e) {
                // this is okay
            }
        }
        developer.signOutAndDeleteUser();
    }

    @Test
    public void crudTemplate() throws Exception {
        Criteria criteria = new Criteria();
        criteria.setMinAppVersions(ImmutableMap.of("Android", 10));
        criteria.setMaxAppVersions(ImmutableMap.of("Android", 100));
        
        // create template
        template = new Template();
        template.setCriteria(criteria);
        template.setName("TestTemplate Name");
        template.setDescription("TestTemplate Description");
        template.setTemplateType(EMAIL_ACCOUNT_EXISTS);
        // These should be ignored
        template.setDeleted(true);
        
        ForDevelopersApi devsApi = developer.getClient(ForDevelopersApi.class);
        
        GuidVersionHolder keys = devsApi.createTemplate(template).execute().body();
        template.setGuid(keys.getGuid());
        template.setVersion(keys.getVersion());
        
        Template retrieved = devsApi.getTemplate(template.getGuid()).execute().body();
        assertEquals(template.getName(), retrieved.getName());
        assertEquals(template.getDescription(), retrieved.getDescription());
        assertEquals(EMAIL_ACCOUNT_EXISTS, retrieved.getTemplateType());
        assertFalse(retrieved.isDeleted());
        assertEquals(10, (int)retrieved.getCriteria().getMinAppVersions().get("Android"));
        assertEquals(100, (int)retrieved.getCriteria().getMaxAppVersions().get("Android"));
        
        // update template
        template.setName("TestTemplate Name Updated");
        template.setDescription("TestTemplate Description Updated");
        template.setDeleted(false);
        criteria.setMinAppVersions(ImmutableMap.of("Android", 11));
        criteria.setMaxAppVersions(ImmutableMap.of("Android", 99));
        
        keys = devsApi.updateTemplate(template.getGuid(), template).execute().body();
        template.setGuid(keys.getGuid());
        template.setVersion(keys.getVersion());
        
        retrieved = devsApi.getTemplate(template.getGuid()).execute().body();
        assertEquals(template.getName(), retrieved.getName());
        assertEquals(template.getDescription(), retrieved.getDescription());
        assertFalse(retrieved.isDeleted());
        assertEquals(11, (int)retrieved.getCriteria().getMinAppVersions().get("Android"));
        assertEquals(99, (int)retrieved.getCriteria().getMaxAppVersions().get("Android"));

        // logically delete template as an update, and through DELETE
        
        template.setDeleted(true);
        keys = devsApi.updateTemplate(template.getGuid(), template).execute().body();
        template.setGuid(keys.getGuid());
        template.setVersion(keys.getVersion());
        
        retrieved = devsApi.getTemplate(template.getGuid()).execute().body();
        assertTrue(retrieved.isDeleted());
        
        template.setDeleted(false);
        keys = devsApi.updateTemplate(template.getGuid(), template).execute().body();
        template.setGuid(keys.getGuid());
        template.setVersion(keys.getVersion());
        retrieved = devsApi.getTemplate(template.getGuid()).execute().body();
        assertFalse(retrieved.isDeleted());
        
        devsApi.deleteTemplate(template.getGuid(), false).execute();
        
        retrieved = devsApi.getTemplate(template.getGuid()).execute().body();
        assertTrue(retrieved.isDeleted());
        
        // get a page of templates without logical deletes
        TemplateList list = devsApi.getTemplates(EMAIL_ACCOUNT_EXISTS.name(), null, null, false).execute()
                .body();
        assertTrue(list.getItems().stream().noneMatch((template -> template.getGuid().equals(template.getGuid()))));
        
        // get a page of templates with logical deletes
        list = devsApi.getTemplates(EMAIL_ACCOUNT_EXISTS.name(), null, null, true).execute().body();
        assertTrue(list.getItems().stream().anyMatch((template -> template.getGuid().equals(template.getGuid()))));
        
        // physically delete
        ForAdminsApi adminsApi = admin.getClient(ForAdminsApi.class);
        adminsApi.deleteTemplate(template.getGuid(), true).execute();        
        
        // it's really deleted
        try {
            devsApi.getTemplate(template.getGuid()).execute();
            fail("Should have thrown exception");
        } catch(EntityNotFoundException e) {

        }
    }
    
    @Test
    public void templateRevisionTest() throws Exception {
        template = new Template();
        template.setName("TestTemplate Name");
        template.setDescription("TestTemplate Description");
        template.setTemplateType(EMAIL_ACCOUNT_EXISTS);

        ForDevelopersApi devsApi = developer.getClient(ForDevelopersApi.class);
        
        GuidVersionHolder keys = devsApi.createTemplate(template).execute().body();
        template.setGuid(keys.getGuid());
        template.setVersion(keys.getVersion());
        
        TemplateRevision revision1 = new TemplateRevision();
        revision1.setSubject("Subject 1");
        revision1.setDocumentContent("Content 1");
        revision1.setMimeType(TEXT_PLAIN);
        DateTime timestamp1 = devsApi.createTemplateRevision(template.getGuid(), revision1).execute().body().getCreatedOn();
        
        TemplateRevision revision2 = new TemplateRevision();
        revision2.setSubject("Subject 2");
        revision2.setDocumentContent("Content 2");
        revision2.setMimeType(TEXT_PLAIN);
        DateTime timestamp2 = devsApi.createTemplateRevision(template.getGuid(), revision2).execute().body().getCreatedOn();
        
        TemplateRevisionList list = devsApi.getTemplateRevisions(template.getGuid(), 0, 5).execute().body();
        assertEquals(list.getItems().size(), 3); // the starting default, plus the two we created after that
        
        // publish the first one (after the default)
        TemplateRevision pubRevision = devsApi.getTemplateRevision(
                template.getGuid(), list.getItems().get(1).getCreatedOn()).execute().body();
        assertEquals("Subject 1", pubRevision.getSubject());
        assertEquals("Content 1", pubRevision.getDocumentContent());
        assertEquals(developer.getUserId(), pubRevision.getCreatedBy());
        assertEquals(TEXT_PLAIN, pubRevision.getMimeType());
        assertEquals(timestamp1, pubRevision.getCreatedOn());
        
        devsApi.publishTemplateRevision(template.getGuid(), timestamp1).execute();
        
        Template retrieved = devsApi.getTemplate(template.getGuid()).execute().body();
        assertEquals(retrieved.getPublishedCreatedOn(), timestamp1);
        
        // Finally, we can move the publication timestamp.
        devsApi.publishTemplateRevision(template.getGuid(), timestamp2).execute();
        retrieved = devsApi.getTemplate(template.getGuid()).execute().body();
        assertEquals(retrieved.getPublishedCreatedOn(), timestamp2);
    }
}
