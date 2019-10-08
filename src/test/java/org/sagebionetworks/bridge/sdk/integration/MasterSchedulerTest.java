package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.exceptions.EntityAlreadyExistsException;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.model.DateTimeHolder;
import org.sagebionetworks.bridge.rest.model.MasterSchedulerConfig;
import org.sagebionetworks.bridge.rest.model.MasterSchedulerConfigList;
import org.sagebionetworks.bridge.rest.model.Message;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;

import com.fasterxml.jackson.databind.ObjectMapper;

import retrofit2.Response;

public class MasterSchedulerTest {
    private static final String SCHEDULE_ID = "test-schedule-id";
    private static final String UPDATED_CRON = "1 0 0 1 * ?";
    
    private TestUser admin;
    
    private ForAdminsApi adminApi;
    
    private MasterSchedulerConfig config;
    
    @Before
    public void before() throws Exception {
        admin = TestUserHelper.getSignedInAdmin();
        
        adminApi = admin.getClient(ForAdminsApi.class);
        config = Tests.getMastSchedulerConfig();
    }
    
    @After
    public void after() throws Exception {
        try {
            adminApi.deleteSchedulerConfig(SCHEDULE_ID).execute().body();
        } catch (EntityNotFoundException e) {
            
        }
    }
    
    @SuppressWarnings("unchecked")
    @Test
    public void testMasterSchedulerConfig() throws IOException {
        try {
            adminApi.getSchedulerConfig(SCHEDULE_ID).execute().body();
            fail("expected exception");
        } catch (EntityNotFoundException e) {
            assertEquals("MasterSchedulerConfig not found.", e.getMessage());
        }
        
        MasterSchedulerConfig result = adminApi.createSchedulerConfig(config).execute().body();
        assertEquals(result.getScheduleId(), config.getScheduleId());
        assertEquals(result.getCronSchedule(), config.getCronSchedule());
        assertEquals(result.getSqsQueueUrl(), config.getSqsQueueUrl());
        assertEquals(result.getVersion(), config.getVersion());
        
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> returnedRequestTemplateMap = mapper.convertValue(result.getRequestTemplate(), Map.class);
        assertEquals(2, returnedRequestTemplateMap.size());
        assertEquals("string", returnedRequestTemplateMap.get("a"));
        assertEquals(true, returnedRequestTemplateMap.get("b"));
        
        try {
            adminApi.createSchedulerConfig(config).execute().body();
            fail("expected exception");
        } catch (EntityAlreadyExistsException e) {
            assertEquals("MasterSchedulerConfig already exists.", e.getMessage());
        }
        
        result = adminApi.getSchedulerConfig(SCHEDULE_ID).execute().body();
        assertEquals(result.getScheduleId(), config.getScheduleId());
        assertEquals(result.getCronSchedule(), config.getCronSchedule());
        assertEquals(result.getSqsQueueUrl(), config.getSqsQueueUrl());
        assertEquals(result.getVersion(), config.getVersion());
        
        returnedRequestTemplateMap = mapper.convertValue(result.getRequestTemplate(), Map.class);
        assertEquals(2, returnedRequestTemplateMap.size());
        assertEquals("string", returnedRequestTemplateMap.get("a"));
        assertEquals(true, returnedRequestTemplateMap.get("b"));
        
        MasterSchedulerConfigList configList = adminApi.getAllSchedulerConfigs().execute().body();
        List<MasterSchedulerConfig> configs = configList.getItems();
        boolean found = false;
        for (MasterSchedulerConfig config : configs) {
            if (config.getScheduleId().equals(SCHEDULE_ID)) {
                found = true;
            }
        }
        if (!found) {
            fail("MasterSchedulerConfig not in list.");
        }
        
        MasterSchedulerConfig configV2 = Tests.getMastSchedulerConfig();
        configV2.setVersion(2L);
        try {
            adminApi.updateSchedulerConfig(SCHEDULE_ID, configV2).execute().body();
            fail("expected exception");
        } catch (EntityNotFoundException e) {
            assertEquals("MasterSchedulerConfig not found.", e.getMessage());
        }
        
        configV2.setVersion(1L);
        configV2.setScheduleId("new-schedule-id");
        configV2.setCronSchedule(UPDATED_CRON);
        
        result = adminApi.updateSchedulerConfig(SCHEDULE_ID, configV2).execute().body();
        assertEquals(result.getScheduleId(), SCHEDULE_ID);
        assertEquals(result.getCronSchedule(), UPDATED_CRON);
        assertEquals(result.getVersion(), new Long(2));
        
        MasterSchedulerConfigList updatedConfigList = adminApi.getAllSchedulerConfigs().execute().body();
        List<MasterSchedulerConfig> updatedConfigs = updatedConfigList.getItems();
        assertNotEquals(configs, updatedConfigs);
        
        try {
            adminApi.deleteSchedulerConfig("delete-schedule-id").execute().body();
            fail("expected exception");
        } catch (EntityNotFoundException e) {
            assertEquals("MasterSchedulerConfig not found.", e.getMessage());
        }
        
        Response<Message> response = adminApi.deleteSchedulerConfig(SCHEDULE_ID).execute();
        assertEquals(200, response.code());
        
        try {
            adminApi.getSchedulerConfig(SCHEDULE_ID).execute().body();
            fail("expected exception");
        } catch (EntityNotFoundException e) {
            assertEquals("MasterSchedulerConfig not found.", e.getMessage());
        }
        
        configList = adminApi.getAllSchedulerConfigs().execute().body();
        for (MasterSchedulerConfig config : configList.getItems()) {
            if (config.getScheduleId() == SCHEDULE_ID) {
                fail("MasterSchedulerConfig not deleted");
            }
        }
    }
    
    @Test
    public void testGetSchedulerStatus() throws Exception {
        DateTimeHolder dateTime = adminApi.getSchedulerStatus().execute().body();
        
        assertNotNull(dateTime);
    }
}
