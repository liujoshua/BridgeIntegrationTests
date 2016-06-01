package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.sdk.Roles;
import org.sagebionetworks.bridge.sdk.exceptions.UnauthorizedException;
import org.sagebionetworks.bridge.sdk.integration.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.sdk.models.studies.Study;

public class SelfStudyTest {

    private TestUser admin;
    private TestUser researcher;
    private TestUser developer;
    
    @Before
    public void before() {
        admin = TestUserHelper.getSignedInAdmin();
        researcher = TestUserHelper.createAndSignInUser(SelfStudyTest.class, false, Roles.RESEARCHER);
        developer= TestUserHelper.createAndSignInUser(SelfStudyTest.class, false, Roles.DEVELOPER);
    }
    
    @After
    public void after() {
        if (researcher != null) {
            researcher.signOutAndDeleteUser();
        }
        if (developer != null) {
            developer.signOutAndDeleteUser();
        }
    }
    
    @Test
    public void getSelfStudy() {
        Study study = researcher.getSession().getResearcherClient().getStudy();
        assertEquals("api", study.getIdentifier());
        
        study = developer.getSession().getDeveloperClient().getStudy();
        assertEquals("api", study.getIdentifier());
        
        study = admin.getSession().getAdminClient().getStudy();
        assertEquals("api", study.getIdentifier());
    }
    
    @Test(expected = UnauthorizedException.class)
    public void researcherCannotUpdateStudy() {
        Study study = researcher.getSession().getResearcherClient().getStudy();
        study.setName("Test");
        researcher.getSession().getDeveloperClient().updateStudy(study);
    }
    
    @Test(expected = UnauthorizedException.class)
    public void adminCannotUpdateStudyThroughResearcherAPI() {
        Study study = admin.getSession().getResearcherClient().getStudy();
        study.setName("Test");
        researcher.getSession().getDeveloperClient().updateStudy(study);
    }
}
