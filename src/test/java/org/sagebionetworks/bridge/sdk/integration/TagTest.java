package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.List;
import java.util.Map;

import com.google.common.collect.ImmutableList;

import org.junit.Test;

import org.sagebionetworks.bridge.rest.api.TagsApi;
import org.sagebionetworks.bridge.rest.model.Tag;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;

public class TagTest {

    
    @Test
    public void test() throws Exception {
        TestUser admin = TestUserHelper.getSignedInAdmin();
        TagsApi tagsApi = admin.getClient(TagsApi.class);
        
        tagsApi.addTag(new Tag().value("cat1:tag1.1")).execute();
        tagsApi.addTag(new Tag().value("cat1:tag1.2")).execute();
        tagsApi.addTag(new Tag().value("cat2:tag2.1")).execute();
        tagsApi.addTag(new Tag().value("cat2:tag2.2")).execute();
        tagsApi.addTag(new Tag().value("tag3")).execute();
        
        Map<String, List<String>> allTags = tagsApi.getTags().execute().body();
        assertEquals(allTags.get("default"), ImmutableList.of("tag3"));
        assertEquals(allTags.get("cat1"), ImmutableList.of("tag1.1", "tag1.2"));
        assertEquals(allTags.get("cat2"), ImmutableList.of("tag2.1", "tag2.2"));
        
        tagsApi.deleteTag("cat1:tag1.1").execute();
        tagsApi.deleteTag("cat1:tag1.2").execute();
        tagsApi.deleteTag("cat2:tag2.1").execute();
        tagsApi.deleteTag("cat2:tag2.2").execute();
        tagsApi.deleteTag("tag3").execute();
        
        allTags = tagsApi.getTags().execute().body();
        assertFalse(allTags.containsKey("default") && allTags.get("default").contains("tag3"));
        assertFalse(allTags.containsKey("cat1") && allTags.get("cat1").contains("tag1.1"));
        assertFalse(allTags.containsKey("cat1") && allTags.get("cat1").contains("tag1.2"));
        assertFalse(allTags.containsKey("cat2") && allTags.get("cat2").contains("tag2.1"));
        assertFalse(allTags.containsKey("cat2") && allTags.get("cat2").contains("tag2.2"));
    }
}
