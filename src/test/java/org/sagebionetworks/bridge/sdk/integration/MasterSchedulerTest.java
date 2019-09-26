package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.exceptions.EntityAlreadyExistsException;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.model.DateTimeHolder;
import org.sagebionetworks.bridge.rest.model.MasterSchedulerConfig;
import org.sagebionetworks.bridge.rest.model.Message;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;

import retrofit2.Response;

public class MasterSchedulerTest {
    private static final String SCHEDULE_ID = "test-schedule-id";
    
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
    
    @Test
    public void testSchedulerConfigExists() throws Exception {
        try {
            adminApi.getSchedulerConfig(SCHEDULE_ID).execute().body();
            fail("expected exception");
        } catch (EntityNotFoundException e) {
            assertEquals("MasterSchedulerConfig not found.", e.getMessage());
        }
    }
    
    @Test
    public void testSchedulerConfigCreate() throws Exception {
        MasterSchedulerConfig result = adminApi.createSchedulerConfig(config).execute().body();
        
        assertNotNull(result);
        
        try {
            adminApi.createSchedulerConfig(config).execute().body();
            fail("expected exception");
        } catch (EntityAlreadyExistsException e) {
            assertEquals("MasterSchedulerConfig already exists.", e.getMessage());
        }
    }
    
    @Test
    public void testSchedulerConfigUpdate() throws Exception {
        adminApi.createSchedulerConfig(config).execute().body();
        
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
        configV2.setCronSchedule("updatedCronSchedule");
        MasterSchedulerConfig updatedResult = adminApi.updateSchedulerConfig(SCHEDULE_ID, configV2).execute().body();
        
        assertEquals(updatedResult.getScheduleId(), SCHEDULE_ID);
        assertEquals(updatedResult.getCronSchedule(), "updatedCronSchedule");
        assertEquals(updatedResult.getVersion(), new Long(2));
    }
    
    @Test
    public void testDeleteSchedulerConfig() throws Exception {
        adminApi.createSchedulerConfig(config).execute().body();
        
        try {
            adminApi.deleteSchedulerConfig("delete-schedule-id").execute().body();
        } catch (EntityNotFoundException e) {
            assertEquals("MasterSchedulerConfig not found.", e.getMessage());
        }
        
        Response<Message> response = adminApi.deleteSchedulerConfig(SCHEDULE_ID).execute();
        assertEquals(200, response.code());
    }
    
    @Test
    public void testGetSchedulerStatus() throws Exception {
        DateTimeHolder dateTime = adminApi.getSchedulerStatus().execute().body();
        
        assertNull(dateTime.getDateTime());
    }
}
