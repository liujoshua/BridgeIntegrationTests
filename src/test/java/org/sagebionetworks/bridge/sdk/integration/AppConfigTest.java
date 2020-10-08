package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.sagebionetworks.bridge.sdk.integration.Tests.ORG_ID_1;
import static org.sagebionetworks.bridge.sdk.integration.Tests.randomIdentifier;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.RandomStringUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sagebionetworks.bridge.rest.ApiClientProvider;
import org.sagebionetworks.bridge.rest.RestUtils;
import org.sagebionetworks.bridge.rest.api.AppConfigsApi;
import org.sagebionetworks.bridge.rest.api.AppsApi;
import org.sagebionetworks.bridge.rest.api.AssessmentsApi;
import org.sagebionetworks.bridge.rest.api.FilesApi;
import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.rest.api.OrganizationsApi;
import org.sagebionetworks.bridge.rest.api.PublicApi;
import org.sagebionetworks.bridge.rest.api.SurveysApi;
import org.sagebionetworks.bridge.rest.api.UploadSchemasApi;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.exceptions.InvalidEntityException;
import org.sagebionetworks.bridge.rest.model.AppConfig;
import org.sagebionetworks.bridge.rest.model.AppConfigElement;
import org.sagebionetworks.bridge.rest.model.Assessment;
import org.sagebionetworks.bridge.rest.model.AssessmentReference;
import org.sagebionetworks.bridge.rest.model.ClientInfo;
import org.sagebionetworks.bridge.rest.model.ConfigReference;
import org.sagebionetworks.bridge.rest.model.Criteria;
import org.sagebionetworks.bridge.rest.model.FileMetadata;
import org.sagebionetworks.bridge.rest.model.FileReference;
import org.sagebionetworks.bridge.rest.model.FileRevision;
import org.sagebionetworks.bridge.rest.model.FileRevisionList;
import org.sagebionetworks.bridge.rest.model.GuidCreatedOnVersionHolder;
import org.sagebionetworks.bridge.rest.model.GuidVersionHolder;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.SchedulePlan;
import org.sagebionetworks.bridge.rest.model.SchemaReference;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.rest.model.Survey;
import org.sagebionetworks.bridge.rest.model.SurveyReference;
import org.sagebionetworks.bridge.rest.model.UploadFieldDefinition;
import org.sagebionetworks.bridge.rest.model.UploadFieldType;
import org.sagebionetworks.bridge.rest.model.UploadSchema;
import org.sagebionetworks.bridge.rest.model.UploadSchemaType;
import org.sagebionetworks.bridge.rest.model.VersionHolder;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

public class AppConfigTest {
    private static final int MIN_MAX_TEST_VALUE = 1000;
    private static final Integer ONE = new Integer(1);
    
    private TestUser developer;
    private TestUser admin;
    private TestUser user;

    private ForAdminsApi adminApi;
    private AppConfigsApi appConfigsApi;
    private UploadSchemasApi schemasApi;
    private SurveysApi surveysApi;
    private FilesApi filesApi;
    private AssessmentsApi assessmentsApi;
    private List<String> configsToDelete = new ArrayList<>();
    
    private GuidCreatedOnVersionHolder surveyKeys;
    private UploadSchema schemaKeys;
    private GuidVersionHolder fileKeys;
    private String assessmentGuid;
    private AppConfigElement element;
    
    @Before
    public void before() throws IOException {
        admin = TestUserHelper.getSignedInAdmin();
        
        developer = TestUserHelper.createAndSignInUser(AppConfigTest.class, false, Role.DEVELOPER);
        user = TestUserHelper.createAndSignInUser(AppConfigTest.class, true);
        
        admin.getClient(OrganizationsApi.class).addMember(ORG_ID_1, developer.getUserId()).execute();

        adminApi = admin.getClient(ForAdminsApi.class);
        appConfigsApi = developer.getClient(AppConfigsApi.class);
        schemasApi = developer.getClient(UploadSchemasApi.class);
        surveysApi = developer.getClient(SurveysApi.class);
        filesApi = developer.getClient(FilesApi.class);
        assessmentsApi = developer.getClient(AssessmentsApi.class);

        // App configs with no criteria will conflict with the run of this test. Set the range on these
        // for Android to 1-1.
        List<AppConfig> appConfigs = appConfigsApi.getAppConfigs(false).execute().body().getItems();
        for (AppConfig appConfig : appConfigs) {
            Map<String,Integer> minMap = appConfig.getCriteria().getMinAppVersions();
            Map<String,Integer> maxMap = appConfig.getCriteria().getMaxAppVersions();
            
            if (!(ONE.equals(minMap.get("Android"))) || !(ONE.equals(maxMap.get("Android")))) {
                minMap.put("Android", 1);
                maxMap.put("Android", 1);
                appConfigsApi.updateAppConfig(appConfig.getGuid(), appConfig).execute();
            }
        }
    }

    @After
    public void deleteAppConfigs() throws Exception {
        for (String configGuid : configsToDelete) {
            adminApi.deleteAppConfig(configGuid, true).execute();
        }
    }
    
    @After
    public void deleteSurveys() throws IOException {
        if (surveyKeys != null) {
            admin.getClient(SurveysApi.class).deleteSurvey(
                surveyKeys.getGuid(), surveyKeys.getCreatedOn(), true).execute();
        }
    }
    
    @After
    public void deleteUploadSchema() throws IOException {
        if (schemaKeys != null) {
            adminApi.deleteAllRevisionsOfUploadSchema(schemaKeys.getSchemaId(), true).execute();
        }
    }
    
    @After
    public void deleteFile() throws Exception {
        if (fileKeys != null) {
            adminApi.deleteFile(fileKeys.getGuid(), true).execute();
        }
    }
    
    @After
    public void deleteAssessment() throws Exception {
        if (assessmentGuid != null) {
            adminApi.deleteAssessment(assessmentGuid, true).execute();
        }
    }
    
    @After
    public void deleteAppConfigElement() throws IOException {
        if (element != null) {
            adminApi.deleteAppConfigElement(element.getId(), element.getRevision(), true).execute();    
        }
    }

    @After
    public void deleteDeveloper() throws IOException {
        if (developer != null) {
            developer.signOutAndDeleteUser();            
        }
    }
    
    @After
    public void deleteUser() throws IOException {
        if (user != null) {
            user.signOutAndDeleteUser();
        }
    }

    @SuppressWarnings("deprecation")
    @Test
    public void crudAppConfig() throws Exception {
        String id = randomIdentifier(AssessmentTest.class);
        Assessment unsavedAssessment = new Assessment()
                .identifier(id)
                .title("Title")
                .summary("Summary")
                .validationStatus("Not validated")
                .normingStatus("Not normed")
                .osName("Both")
                .ownerId(ORG_ID_1);
        
        Assessment assessment = assessmentsApi.createAssessment(unsavedAssessment).execute().body();
        assessmentGuid = assessment.getGuid();
        
        FileMetadata meta = new FileMetadata();
        meta.setName("test file");
        fileKeys = filesApi.createFile(meta).execute().body();
        
        File file = new File("src/test/resources/file-test/test.pdf");
        RestUtils.uploadHostedFileToS3(filesApi, fileKeys.getGuid(), file);
        
        FileRevisionList list = filesApi.getFileRevisions(fileKeys.getGuid(), 0, 5).execute().body();
        FileRevision revision = list.getItems().get(0);
        FileReference fileRef = new FileReference().guid(revision.getFileGuid()).createdOn(revision.getCreatedOn());
        
        Survey survey = TestSurvey.getSurvey(AppConfigTest.class);
        survey.setIdentifier(Tests.randomIdentifier(this.getClass()));
        surveyKeys = surveysApi.createSurvey(survey).execute().body();
        surveysApi.publishSurvey(surveyKeys.getGuid(), surveyKeys.getCreatedOn(), false).execute();
        
        UploadFieldDefinition fieldDef = new UploadFieldDefinition();
        fieldDef.setName("field");
        fieldDef.setType(UploadFieldType.STRING);
        UploadSchema schema = new UploadSchema();
        schema.setName("Schema");
        schema.setSchemaId("schemaId"+RandomStringUtils.randomAlphabetic(4));
        schema.setSchemaType(UploadSchemaType.IOS_DATA);
        schema.setFieldDefinitions(Lists.newArrayList(fieldDef));
        
        // create it
        schemaKeys = schemasApi.createOrUpdateUploadSchema(schema).execute().body();
        AppsApi studiesApi = developer.getClient(AppsApi.class);
        int initialCount = appConfigsApi.getAppConfigs(false).execute().body().getItems().size();

        SurveyReference surveyRef1 = new SurveyReference().guid(surveyKeys.getGuid()).createdOn(surveyKeys.getCreatedOn());

        StudyParticipant participant = new StudyParticipant();
        participant.setExternalId("externalId");
        
        Criteria criteria = new Criteria();
        criteria.setMaxAppVersions(ImmutableMap.of("Android", 10));
        
        AppConfig appConfig = new AppConfig();
        appConfig.label(Tests.randomIdentifier(AppConfigTest.class));
        appConfig.clientData(participant);
        appConfig.criteria(criteria);
        appConfig.surveyReferences(ImmutableList.of(surveyRef1));
        appConfig.fileReferences(ImmutableList.of(fileRef));
        appConfig.assessmentReferences(ImmutableList.of(new AssessmentReference().guid(assessmentGuid)));
        
        // Create
        GuidVersionHolder holder = appConfigsApi.createAppConfig(appConfig).execute().body();
        
        AppConfig firstOneRetrieved = appConfigsApi.getAppConfig(holder.getGuid()).execute().body();
        configsToDelete.add(holder.getGuid());
        
        // Let's verify resolution of the identifiers...
        AppConfig resolvedAppConfig = user.getClient(ForConsentedUsersApi.class)
                .getConfigForApp(user.getAppId()).execute().body();
        AssessmentReference retAssessmentRef = resolvedAppConfig.getAssessmentReferences().get(0);
        assertEquals(assessmentGuid, retAssessmentRef.getGuid());
        assertEquals(id, retAssessmentRef.getId());
        assertNull(retAssessmentRef.getSharedId());
        assertTrue(retAssessmentRef.getConfigHref().contains("/v1/assessments/" + assessmentGuid + "/config"));

        Tests.setVariableValueInObject(firstOneRetrieved.getSurveyReferences().get(0), "href", null);
        assertEquals(appConfig.getLabel(), firstOneRetrieved.getLabel());
        
        assertSurveyReference(surveyRef1, firstOneRetrieved.getSurveyReferences().get(0));
        assertNotNull(firstOneRetrieved.getCreatedOn());
        assertNotNull(firstOneRetrieved.getModifiedOn());
        assertEquals(firstOneRetrieved.getCreatedOn().toString(), firstOneRetrieved.getModifiedOn().toString());
        
        StudyParticipant savedUser = RestUtils.toType(firstOneRetrieved.getClientData(), StudyParticipant.class);
        assertEquals("externalId", savedUser.getExternalId());
        
        FileReference retrievedFileRef = firstOneRetrieved.getFileReferences().get(0);
        assertEquals(retrievedFileRef.getGuid(), revision.getFileGuid());
        assertEquals(retrievedFileRef.getCreatedOn().toString(), revision.getCreatedOn().toString());
        
        // Change it. You can no longer add duplicate or invalid references, so just add a second reference
        SchemaReference schemaRef1 = new SchemaReference().id(schemaKeys.getSchemaId())
                .revision(schemaKeys.getRevision());
        List<SchemaReference> schemaReferences = Lists.newArrayList(schemaRef1);
        
        appConfig.setSchemaReferences(schemaReferences);
        appConfig.setFileReferences(ImmutableList.of());
        appConfig.setGuid(holder.getGuid());
        appConfig.setVersion(holder.getVersion());
        
        // Update it.
        holder = appConfigsApi.updateAppConfig(appConfig.getGuid(), appConfig).execute().body();
        appConfig.setGuid(holder.getGuid());
        appConfig.setVersion(holder.getVersion());

        // Retrieve again, it is updated
        AppConfig secondOneRetrieved = appConfigsApi.getAppConfig(appConfig.getGuid()).execute().body();
        assertEquals(1, secondOneRetrieved.getSchemaReferences().size());
        assertEquals(1, secondOneRetrieved.getSurveyReferences().size());
        assertSchemaReference(schemaRef1, secondOneRetrieved.getSchemaReferences().get(0));
        assertSurveyReference(surveyRef1, secondOneRetrieved.getSurveyReferences().get(0));
        assertNotEquals(secondOneRetrieved.getCreatedOn().toString(), secondOneRetrieved.getModifiedOn().toString());
        assertEquals(secondOneRetrieved.getCreatedOn().toString(), firstOneRetrieved.getCreatedOn().toString());
        assertNotEquals(secondOneRetrieved.getModifiedOn().toString(), firstOneRetrieved.getModifiedOn().toString());
        assertTrue(secondOneRetrieved.getFileReferences().isEmpty());
        
        // You can get it as the user
        AppConfig userAppConfig = studiesApi.getConfigForApp(developer.getAppId()).execute().body();
        assertNotNull(userAppConfig);
        
        // Create a second app config
        GuidVersionHolder secondHolder = appConfigsApi.createAppConfig(appConfig).execute().body();
        configsToDelete.add(secondHolder.getGuid());
        appConfig = appConfigsApi.getAppConfig(appConfig.getGuid()).execute().body(); // get createdOn timestamp
        
        assertEquals(initialCount+2, appConfigsApi.getAppConfigs(false).execute().body().getItems().size());

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
            // This should not match the clientInfo provided
            publicApi.getConfigForApp(developer.getAppId()).execute().body();
            fail("Should have thrown an exception");
        } catch(EntityNotFoundException e) {
            // None have matched
        }
        
        appConfig.getCriteria().getMaxAppVersions().remove("Android");
        appConfigsApi.updateAppConfig(appConfig.getGuid(), appConfig).execute();
        
        // Having changed the config to match the criteria, we should be able to retrieve it.
        AppConfig config = publicApi.getConfigForApp(developer.getAppId()).execute().body();
        assertEquals(appConfig.getGuid(), config.getGuid());

        // test logical deletion
        appConfigsApi.deleteAppConfig(config.getGuid(), false).execute(); // flag does not matter here however
        
        // We can retrieve it individually and it is marked as deleted
        AppConfig deletedConfig = appConfigsApi.getAppConfig(config.getGuid()).execute().body();
        assertTrue(deletedConfig.isDeleted());
        
        int updatedCount = appConfigsApi.getAppConfigs(false).execute().body().getItems().size();
        assertTrue((initialCount+1) == updatedCount);
        
        updatedCount = appConfigsApi.getAppConfigs(true).execute().body().getItems().size();
        assertTrue((initialCount+2) == updatedCount);
        
        // This should *really* delete the config from the db in order to clean up, so 
        adminApi.deleteAppConfig(config.getGuid(), true).execute();
        try {
            appConfigsApi.getAppConfig(config.getGuid()).execute();
            fail("Should have thrown exception");
        } catch(EntityNotFoundException e) {
            configsToDelete.remove(config.getGuid());
        }
    }
    
    @Test
    public void canPhysicallyDeleteLogicallyDeletedAppConfig() throws Exception {
        AppConfig appConfig = new AppConfig();
        appConfig.setLabel(Tests.randomIdentifier(AppConfigTest.class));
        appConfig.setCriteria(new Criteria());
        
        GuidVersionHolder keys = appConfigsApi.createAppConfig(appConfig).execute().body();
        
        appConfigsApi.deleteAppConfig(keys.getGuid(), false).execute();
        
        AppConfig retrieved = appConfigsApi.getAppConfig(keys.getGuid()).execute().body();
        assertTrue(retrieved.isDeleted());
        
        admin.getClient(AppConfigsApi.class).deleteAppConfig(keys.getGuid(), true).execute();
        
        try {
            appConfigsApi.getAppConfig(keys.getGuid()).execute();
            fail("Should have thrown an exception.");
        } catch(EntityNotFoundException e) {
            configsToDelete.remove(keys.getGuid());
        }
    }
    
    @Test
    public void appConfigWithElements() throws Exception {
        user.setClientInfo(new ClientInfo().appName(Tests.APP_NAME).appVersion(MIN_MAX_TEST_VALUE)
                .deviceName("SomeAndroid").osName("Android").osVersion("2.0.0")
                .sdkName(developer.getClientManager().getClientInfo().getSdkName())
                .sdkVersion(developer.getClientManager().getClientInfo().getSdkVersion()));
        
        String elementId = Tests.randomIdentifier(AppConfigTest.class);
        // Create an app config element
        element = new AppConfigElement().id(elementId).revision(1L).data(Tests.getSimpleSchedulePlan());
        
        VersionHolder version = appConfigsApi.createAppConfigElement(element).execute().body();
        element.setVersion(version.getVersion());
        
        // include it, return it from an app config (with content)
        Map<String,Integer> map = Maps.newHashMap();
        map.put("Android", MIN_MAX_TEST_VALUE);
        Criteria criteria = new Criteria().minAppVersions(map).maxAppVersions(map);
        
        AppConfig config = new AppConfig().label("A test config").criteria(criteria)
                .configReferences(ImmutableList.of(new ConfigReference().id(elementId).revision(1L)));
        
        GuidVersionHolder guidVersion = appConfigsApi.createAppConfig(config).execute().body();
        configsToDelete.add(guidVersion.getGuid());
        config.setGuid(guidVersion.getGuid());
        config.setVersion(guidVersion.getVersion());
        
        AppConfig retrieved = appConfigsApi.getAppConfig(config.getGuid()).execute().body();
        assertEquals(elementId, retrieved.getConfigReferences().get(0).getId());
        assertEquals(new Long(1), retrieved.getConfigReferences().get(0).getRevision());
        
        // Verify that for the user, the config is included in the app config itself
        ForConsentedUsersApi userApi = user.getClient(ForConsentedUsersApi.class);
        AppConfig usersAppConfig = userApi.getConfigForApp(user.getAppId()).execute().body();
        
        SchedulePlan plan = RestUtils.toType(usersAppConfig.getConfigElements().get(elementId), SchedulePlan.class);        
        assertEquals("Cron-based schedule", plan.getLabel());
        
        // update the element, verify the config is updated
        element.setData(Tests.getPersistentSchedulePlan());
        version = appConfigsApi.updateAppConfigElement(element.getId(), element.getRevision(), element).execute().body();
        element.setVersion(version.getVersion());
        
        // The user's config has been correctly updated
        usersAppConfig = userApi.getConfigForApp(user.getAppId()).execute().body();
        SchedulePlan secondPlan = RestUtils.toType(usersAppConfig.getConfigElements().get(elementId), SchedulePlan.class);
        assertEquals("Persistent schedule", secondPlan.getLabel());

        // delete the element, verify the config is returned, but without it.
        admin.getClient(AppConfigsApi.class).deleteAppConfigElement(element.getId(), element.getRevision(), false).execute();
        
        try {
            // This should now be invalid because it references a non-existent element.
            appConfigsApi.updateAppConfig(config.getGuid(), config).execute();
            fail("This should throw an exception");
        } catch(InvalidEntityException e) {
        }
    }
    
    public void assertSchemaReference(SchemaReference expected, SchemaReference actual) {
        assertEquals(expected.getId(), actual.getId());
        assertEquals(expected.getRevision(), actual.getRevision());
        
    }
    
    public void assertSurveyReference(SurveyReference expected, SurveyReference actual) {
        assertEquals(expected.getGuid(), actual.getGuid());
        assertEquals(expected.getCreatedOn(), actual.getCreatedOn());
    }
    
    public <T> T getPublicClient(Class<T> clazz, String ua) {
        String baseUrl = developer.getClientManager().getHostUrl();
        String userAgent = ua;
        String acceptLanguage = "en";
        String appId = developer.getAppId();
        
        ApiClientProvider provider = new ApiClientProvider(baseUrl, userAgent, acceptLanguage, appId);
        return provider.getClient(clazz);
    }
}
