package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.sagebionetworks.bridge.rest.model.Role.ADMIN;
import static org.sagebionetworks.bridge.rest.model.Role.DEVELOPER;
import static org.sagebionetworks.bridge.rest.model.Role.RESEARCHER;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.api.AppsApi;
import org.sagebionetworks.bridge.rest.exceptions.UnauthorizedException;
import org.sagebionetworks.bridge.rest.model.App;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;

public class SelfAppTest {

    private TestUser admin;
    private TestUser appAdmin;
    private TestUser researcher;
    private TestUser developer;
    
    @Before
    public void before() throws Exception {
        admin = TestUserHelper.getSignedInAdmin();
        appAdmin = TestUserHelper.createAndSignInUser(SelfAppTest.class, false, ADMIN);
        researcher = TestUserHelper.createAndSignInUser(SelfAppTest.class, false, RESEARCHER);
        developer= TestUserHelper.createAndSignInUser(SelfAppTest.class, false, DEVELOPER);
    }
    
    @After
    public void after() throws Exception {
        if (researcher != null) {
            researcher.signOutAndDeleteUser();
        }
        if (developer != null) {
            developer.signOutAndDeleteUser();
        }
        if (appAdmin != null) {
            appAdmin.signOutAndDeleteUser();
        }
    }
    
    @Test
    public void getCurrentApp() throws Exception {
        App app = researcher.getClient(AppsApi.class).getUsersApp().execute().body();
        assertEquals("api", app.getIdentifier());
        
        app = developer.getClient(AppsApi.class).getUsersApp().execute().body();
        assertEquals("api", app.getIdentifier());
        
        app = admin.getClient(AppsApi.class).getUsersApp().execute().body();
        assertEquals("api", app.getIdentifier());
    }
    
    @Test
    public void developerCanUpdateSelfApp() throws Exception {
        AppsApi appsApi = developer.getClient(AppsApi.class);
        App app = appsApi.getUsersApp().execute().body();
        app.setName("Test Study");
        appsApi.updateUsersApp(app).execute();
    }
    
    @Test(expected = UnauthorizedException.class)
    public void researcherCannotUpdateSelfApp() throws Exception {
        AppsApi appsApi = researcher.getClient(AppsApi.class);
        App app = appsApi.getUsersApp().execute().body();
        app.setName("Test Study");
        appsApi.updateUsersApp(app).execute();
    }
    
    @Test
    public void studyAdminCanUpdateSelfApp() throws Exception {
        AppsApi appsApi = appAdmin.getClient(AppsApi.class);
        App app = appsApi.getUsersApp().execute().body();
        app.setName("Test Study");
        appsApi.updateUsersApp(app).execute();
    }
}
