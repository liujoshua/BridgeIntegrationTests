package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.sagebionetworks.bridge.rest.model.Role.STUDY_DESIGNER;
import static org.sagebionetworks.bridge.sdk.integration.Tests.ORG_ID_2;
import static org.sagebionetworks.bridge.sdk.integration.Tests.STUDY_ID_1;
import static org.sagebionetworks.bridge.sdk.integration.Tests.STUDY_ID_2;
import static org.sagebionetworks.bridge.util.IntegTestUtils.SAGE_ID;

import java.io.IOException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.api.ForStudyDesignersApi;
import org.sagebionetworks.bridge.rest.exceptions.UnauthorizedException;
import org.sagebionetworks.bridge.rest.model.Study;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;

public class ForStudyDesignersTest {
    
    @FunctionalInterface
    public interface ThrowingConsumer<T> {
        public void accept(T t) throws IOException;
    }

    TestUser studyDesigner;
    
    @Before
    public void before() throws Exception {
        studyDesigner = TestUserHelper.createAndSignInUser(ForStudyDesignersTest.class, false, STUDY_DESIGNER);
        // Put them in org two, so we can verify they cannot see org 1
        TestUser admin = TestUserHelper.getSignedInAdmin();
        ForAdminsApi adminsApi = admin.getClient(ForAdminsApi.class);
        adminsApi.removeMember(SAGE_ID, studyDesigner.getUserId()).execute();
        adminsApi.addMember(ORG_ID_2, studyDesigner.getUserId()).execute();
    }
    
    @After
    public void after() throws Exception {
        if (studyDesigner != null) {
            studyDesigner.signOutAndDeleteUser();
        }
    }
    
    @Test
    public void test() throws Exception {
        // This isn't all the APIs...they are tested elsewhere, this is just a verification
        // that the role exists and the server recognizes it. There are no API calls that are
        // entirely unique to this role (at this time).
        ForStudyDesignersApi designerApi = studyDesigner.getClient(ForStudyDesignersApi.class);
        
        Study study = designerApi.getStudy(STUDY_ID_2).execute().body();
        assertEquals(STUDY_ID_2, study.getIdentifier());
        
        testAgainstTwoStudies(studyId -> {
            designerApi.getStudy(studyId).execute().body();
        });
        testAgainstTwoStudies(studyId -> {
            designerApi.getExternalIdsForStudy(studyId, null, null, null).execute().body();
        });
    }
    
    private void testAgainstTwoStudies(ThrowingConsumer<String> consumer) throws IOException {
        consumer.accept(STUDY_ID_2);
        try {
            consumer.accept(STUDY_ID_1);
            fail("Should have thrown exception");
        } catch(UnauthorizedException e) {
        }
    }
}
