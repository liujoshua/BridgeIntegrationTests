package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.sagebionetworks.bridge.rest.model.Role.ADMIN;
import static org.sagebionetworks.bridge.rest.model.Role.DEVELOPER;
import static org.sagebionetworks.bridge.rest.model.Role.RESEARCHER;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.api.StudiesApi;
import org.sagebionetworks.bridge.rest.exceptions.UnauthorizedException;
import org.sagebionetworks.bridge.rest.model.Study;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;

public class SelfStudyTest {

    private TestUser admin;
    private TestUser studyAdmin;
    private TestUser researcher;
    private TestUser developer;
    
    @Before
    public void before() throws Exception {
        admin = TestUserHelper.getSignedInAdmin();
        studyAdmin = TestUserHelper.createAndSignInUser(SelfStudyTest.class, false, ADMIN);
        researcher = TestUserHelper.createAndSignInUser(SelfStudyTest.class, false, RESEARCHER);
        developer= TestUserHelper.createAndSignInUser(SelfStudyTest.class, false, DEVELOPER);
    }
    
    @After
    public void after() throws Exception {
        if (researcher != null) {
            researcher.signOutAndDeleteUser();
        }
        if (developer != null) {
            developer.signOutAndDeleteUser();
        }
        if (studyAdmin != null) {
            studyAdmin.signOutAndDeleteUser();
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
    
    @Test
    public void developerCanUpdateSelfStudy() throws Exception {
        StudiesApi studiesApi = developer.getClient(StudiesApi.class);
        Study study = studiesApi.getUsersStudy().execute().body();
        study.setName("Test Study");
        studiesApi.updateUsersStudy(study).execute();
    }
    
    @Test(expected = UnauthorizedException.class)
    public void researcherCannotUpdateSelfStudy() throws Exception {
        StudiesApi studiesApi = researcher.getClient(StudiesApi.class);
        Study study = studiesApi.getUsersStudy().execute().body();
        study.setName("Test Study");
        studiesApi.updateUsersStudy(study).execute();
    }
    
    @Test
    public void studyAdminCanUpdateSelfStudy() throws Exception {
        StudiesApi studiesApi = studyAdmin.getClient(StudiesApi.class);
        Study study = studiesApi.getUsersStudy().execute().body();
        study.setName("Test Study");
        studiesApi.updateUsersStudy(study).execute();
    }
}
