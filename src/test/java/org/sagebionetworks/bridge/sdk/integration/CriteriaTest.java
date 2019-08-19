package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.sagebionetworks.bridge.rest.model.Role.DEVELOPER;
import static org.sagebionetworks.bridge.util.IntegTestUtils.STUDY_ID;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.ApiClientProvider;
import org.sagebionetworks.bridge.rest.RestUtils;
import org.sagebionetworks.bridge.rest.api.AppConfigsApi;
import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.rest.model.AppConfig;
import org.sagebionetworks.bridge.rest.model.Criteria;
import org.sagebionetworks.bridge.rest.model.GuidVersionHolder;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;

public class CriteriaTest {

    TestUser developer;
    TestUser user;
    
    Set<String> appConfigGuids;
    
    @Before
    public void before() throws Exception {
        appConfigGuids = new HashSet<>();
        developer = TestUserHelper.createAndSignInUser(CriteriaTest.class, false, DEVELOPER);
        user = TestUserHelper.createAndSignInUser(CriteriaTest.class, true);
    }
    
    @After
    public void after() throws Exception {
        TestUser admin = TestUserHelper.getSignedInAdmin();
        for (String oneGuid : appConfigGuids) {
            admin.getClient(ForAdminsApi.class).deleteAppConfig(oneGuid, true).execute();
        }
        if (developer != null) {
            developer.signOutAndDeleteUser();    
        }
        if (user != null) {
            user.signOutAndDeleteUser();
        }
    }
    
    @Test
    public void languageFilteringWorksByBestFit() throws IOException {
        AppConfigsApi appConfigsApi = developer.getClient(AppConfigsApi.class);
        
        AppConfig enAppConfig = new AppConfig().label("en app config").criteria(new Criteria().language("en"));
        AppConfig frAppConfig = new AppConfig().label("fr app config").criteria(new Criteria().language("fr"));
        AppConfig zhAppConfig = new AppConfig().label("zh app config").criteria(new Criteria().language("zh"));
        
        GuidVersionHolder keys = appConfigsApi.createAppConfig(enAppConfig).execute().body();
        appConfigGuids.add(keys.getGuid());
        
        keys = appConfigsApi.createAppConfig(frAppConfig).execute().body();
        appConfigGuids.add(keys.getGuid());
        
        keys = appConfigsApi.createAppConfig(zhAppConfig).execute().body();
        appConfigGuids.add(keys.getGuid());
        
        String userAgent = RestUtils.getUserAgent(user.getClientManager().getClientInfo());
        String acceptLanguage = "fr-CH, fr;q=0.9, en;q=0.8, de;q=0.7, *;q=0.5";
        
        // Force the accept language header, not what is set as a default for integration tests.
        ApiClientProvider provider = new ApiClientProvider(user.getClientManager().getHostUrl(),
                userAgent, acceptLanguage, STUDY_ID);
        
        ForConsentedUsersApi userApi = provider.getClient(ForConsentedUsersApi.class);
        AppConfig appConfig = userApi.getAppConfigForStudy(user.getStudyId()).execute().body();
        // voilà, c'est en francais
        assertEquals("fr", appConfig.getCriteria().getLanguage());
    }
}
