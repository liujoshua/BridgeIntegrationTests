package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.api.StudiesApi;
import org.sagebionetworks.bridge.rest.exceptions.UnauthorizedException;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.Study;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;

public class SelfStudyTest {

    private TestUser admin;
    private TestUser researcher;
    private TestUser developer;
    
    @Before
    public void before() throws Exception {
        admin = TestUserHelper.getSignedInAdmin();
        researcher = TestUserHelper.createAndSignInUser(SelfStudyTest.class, false, Role.RESEARCHER);
        developer= TestUserHelper.createAndSignInUser(SelfStudyTest.class, false, Role.DEVELOPER);
    }
    
    @After
    public void after() throws Exception {
        if (researcher != null) {
            researcher.signOutAndDeleteUser();
        }
        if (developer != null) {
            developer.signOutAndDeleteUser();
        }
    }
    
    @Test
    public void getCurrentStudy() throws Exception {
        Study study = researcher.getClient(StudiesApi.class).getUsersStudy().execute().body();
        assertEquals("api", study.getIdentifier());
        
        study = developer.getClient(StudiesApi.class).getUsersStudy().execute().body();
        assertEquals("api", study.getIdentifier());
        
        study = admin.getClient(StudiesApi.class).getUsersStudy().execute().body();
        assertEquals("api", study.getIdentifier());
    }
    
    @Test(expected = UnauthorizedException.class)
    public void researcherCannotUpdateStudy() throws Exception {
        StudiesApi studiesApi = researcher.getClient(StudiesApi.class);
        Study study = studiesApi.getUsersStudy().execute().body();
        study.setName("Test");
        studiesApi.updateUsersStudy(study).execute();
    }
    
    @Test(expected = UnauthorizedException.class)
    public void adminCannotUpdateStudyThroughResearcherAPI() throws Exception {
        StudiesApi studiesApi = admin.getClient(StudiesApi.class);
        Study study = studiesApi.getUsersStudy().execute().body();
        study.setName("Test");
        studiesApi.updateUsersStudy(study).execute();
    }
}
