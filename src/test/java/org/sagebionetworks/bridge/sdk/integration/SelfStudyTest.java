package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.api.StudiesApi;
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
    
    public void developerCanUpdateSelfStudy() throws Exception {
        StudiesApi studiesApi = developer.getClient(StudiesApi.class);
        Study study = studiesApi.getUsersStudy().execute().body();
        study.setName("Test Study");
        studiesApi.updateUsersStudy(study).execute();
    }
    
    public void researcherCanUpdateSelfStudy() throws Exception {
        StudiesApi studiesApi = researcher.getClient(StudiesApi.class);
        Study study = studiesApi.getUsersStudy().execute().body();
        study.setName("Test Study");
        studiesApi.updateUsersStudy(study).execute();
    }
}
