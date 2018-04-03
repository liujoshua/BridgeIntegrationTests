package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.List;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sagebionetworks.bridge.rest.ApiClientProvider;
import org.sagebionetworks.bridge.rest.RestUtils;
import org.sagebionetworks.bridge.rest.api.AppConfigsApi;
import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.api.PublicApi;
import org.sagebionetworks.bridge.rest.api.StudiesApi;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.model.AppConfig;
import org.sagebionetworks.bridge.rest.model.AppConfigList;
import org.sagebionetworks.bridge.rest.model.ClientInfo;
import org.sagebionetworks.bridge.rest.model.Criteria;
import org.sagebionetworks.bridge.rest.model.GuidVersionHolder;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.SchemaReference;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.rest.model.SurveyReference;
import org.sagebionetworks.bridge.sdk.integration.TestUserHelper.TestUser;

import com.google.common.collect.Lists;

public class AppConfigTest {
    
    private TestUser developer;
    private TestUser admin;

    private ForAdminsApi adminApi;
    private AppConfigsApi devApi;
    
    @Before
    public void before() throws IOException {
        developer = TestUserHelper.createAndSignInUser(ExternalIdsTest.class, false, Role.DEVELOPER);
        admin = TestUserHelper.getSignedInAdmin();
        
        adminApi = admin.getClient(ForAdminsApi.class);
        devApi = developer.getClient(AppConfigsApi.class);
    }
    
    @After
    public void after() throws Exception {
        AppConfigList list = devApi.getAppConfigs().execute().body();
        for (AppConfig config : list.getItems()) {
            adminApi.deleteAppConfig(config.getGuid()).execute();
        }
        try {
            developer.signOutAndDeleteUser();    
        } catch(Throwable throwable) {
        }
    }
    
    @Test
    public void crudAppConfig() throws Exception {
        StudiesApi studiesApi = developer.getClient(StudiesApi.class);
        
        SchemaReference schemaRef1 = new SchemaReference().id("boo").revision(2L);
        Tests.setVariableValueInObject(schemaRef1, "type", "SchemaReference");
        List<SchemaReference> schemaReferences = Lists.newArrayList();
        schemaReferences.add(schemaRef1);
        
        SurveyReference surveyRef1 = new SurveyReference().guid("ABC-DEF-GHI").identifier("ya").createdOn(DateTime.now(DateTimeZone.UTC));
        Tests.setVariableValueInObject(surveyRef1, "type", "SurveyReference");
        List<SurveyReference> surveyReferences = Lists.newArrayList();
        surveyReferences.add(surveyRef1);

        StudyParticipant participant = new StudyParticipant();
        participant.setExternalId("externalId");
        
        Criteria criteria = new Criteria();
        criteria.getMaxAppVersions().put("Android", 10);
        
        AppConfig appConfig = new AppConfig();
        appConfig.setLabel(Tests.randomIdentifier(AppConfigTest.class));
        appConfig.clientData(participant);
        appConfig.setCriteria(criteria);
        appConfig.setSchemaReferences(schemaReferences);
        appConfig.setSurveyReferences(surveyReferences);
        
        // Create
        GuidVersionHolder holder = devApi.createAppConfig(appConfig).execute().body();
        
        AppConfig firstOneRetrieved = devApi.getAppConfig(holder.getGuid()).execute().body();
        Tests.setVariableValueInObject(firstOneRetrieved.getSurveyReferences().get(0), "href", null);
        assertEquals(appConfig.getLabel(), firstOneRetrieved.getLabel());
        assertEquals(schemaRef1, firstOneRetrieved.getSchemaReferences().get(0));
        assertEquals(surveyRef1, firstOneRetrieved.getSurveyReferences().get(0));
        assertNotNull(firstOneRetrieved.getCreatedOn());
        assertNotNull(firstOneRetrieved.getModifiedOn());
        assertEquals(firstOneRetrieved.getCreatedOn().toString(), firstOneRetrieved.getModifiedOn().toString());
        
        StudyParticipant savedUser = RestUtils.toType(firstOneRetrieved.getClientData(), StudyParticipant.class);
        assertEquals("externalId", savedUser.getExternalId());
        
        // Change it
        appConfig.getSchemaReferences().add(schemaRef1);
        appConfig.getSurveyReferences().add(surveyRef1);
        appConfig.setGuid(holder.getGuid());
        appConfig.setVersion(holder.getVersion());
        
        // Update it.
        holder = devApi.updateAppConfig(appConfig.getGuid(), appConfig).execute().body();
        appConfig.setGuid(holder.getGuid());
        appConfig.setVersion(holder.getVersion());

        // You can retrieve this first app config.
        AppConfig secondOneRetrieved = devApi.getAppConfig(appConfig.getGuid()).execute().body();
        assertEquals(2, secondOneRetrieved.getSchemaReferences().size());
        assertEquals(2, secondOneRetrieved.getSurveyReferences().size());
        assertNotEquals(secondOneRetrieved.getCreatedOn().toString(), secondOneRetrieved.getModifiedOn().toString());
        assertEquals(secondOneRetrieved.getCreatedOn().toString(), firstOneRetrieved.getCreatedOn().toString());
        assertNotEquals(secondOneRetrieved.getModifiedOn().toString(), firstOneRetrieved.getModifiedOn().toString());
        
        // You can get it as the user (there's only one)
        AppConfig userAppConfig = studiesApi.getAppConfig(developer.getStudyId()).execute().body();
        assertNotNull(userAppConfig);
        
        // Create a second app config
        devApi.createAppConfig(appConfig).execute().body();
        appConfig = devApi.getAppConfig(appConfig.getGuid()).execute().body(); // get createdOn timestamp
        
        AppConfig shouldBeFirstOne = studiesApi.getAppConfig(developer.getStudyId()).execute().body();
        assertEquals(appConfig.getCreatedOn().toString(), shouldBeFirstOne.getCreatedOn().toString());

        ClientInfo clientInfo = new ClientInfo();
        clientInfo.appName("Integration Tests");
        clientInfo.appVersion(20);
        clientInfo.deviceName("Java");
        clientInfo.osName("Android");
        clientInfo.osVersion("0.0.0");
        clientInfo.sdkName(developer.getClientManager().getClientInfo().getSdkName());
        clientInfo.sdkVersion(developer.getClientManager().getClientInfo().getSdkVersion());
        String ua = RestUtils.getUserAgent(clientInfo);
        
        // Use the public (non-authenticated) call to verify you don't need to be signed in for this to work.
        PublicApi publicApi = getPublicClient(PublicApi.class, ua);
        
        try {
            publicApi.getAppConfig(developer.getStudyId()).execute().body();
            fail("Should have thrown an exception");
        } catch(EntityNotFoundException e) {
            // None have matched
        }
        
        appConfig.getCriteria().getMaxAppVersions().remove("Android");
        devApi.updateAppConfig(appConfig.getGuid(), appConfig).execute();
        
        // Finally... we have one, it will be returned
        AppConfig config = publicApi.getAppConfig(developer.getStudyId()).execute().body();
        assertEquals(appConfig.getGuid(), config.getGuid());
    }
    
    public <T> T getPublicClient(Class<T> clazz, String ua) {
        String baseUrl = developer.getClientManager().getHostUrl();
        String userAgent = ua;
        String acceptLanguage = "en";
        String study = developer.getStudyId();
        
        ApiClientProvider provider = new ApiClientProvider(baseUrl, userAgent, acceptLanguage, study);
        return provider.getClient(clazz);
    }
}
