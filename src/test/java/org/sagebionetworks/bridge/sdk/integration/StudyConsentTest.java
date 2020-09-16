package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.sagebionetworks.bridge.sdk.integration.Tests.STUDY_ID_1;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.api.StudyConsentsApi;
import org.sagebionetworks.bridge.rest.api.SubpopulationsApi;
import org.sagebionetworks.bridge.rest.exceptions.BridgeSDKException;
import org.sagebionetworks.bridge.rest.model.GuidVersionHolder;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.StudyConsent;
import org.sagebionetworks.bridge.rest.model.StudyConsentList;
import org.sagebionetworks.bridge.rest.model.Subpopulation;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;

public class StudyConsentTest {

    private TestUser admin;
    private TestUser developer;
    private String subpopGuid;

    @Before
    public void before() throws Exception {
        admin = TestUserHelper.getSignedInAdmin();
        developer = TestUserHelper.createAndSignInUser(StudyConsentTest.class, true, Role.DEVELOPER);
    }

    @After
    public void after() throws Exception {
        if (subpopGuid != null) {
            admin.getClient(SubpopulationsApi.class).deleteSubpopulation(subpopGuid, true).execute();
        }
        if (developer != null) {
            developer.signOutAndDeleteUser();    
        }
    }

    @Test(expected=BridgeSDKException.class)
    public void cannotBeAccessedByRegularUser() throws Exception {
        TestUser user = TestUserHelper.createAndSignInUser(StudyConsentTest.class, true);
        try {
            StudyConsent consent = new StudyConsent();
            consent.setDocumentContent("<p>Test content.</p>");

            user.getClient(StudyConsentsApi.class).createConsent(user.getDefaultSubpopulation(), consent).execute();
        } finally {
            user.signOutAndDeleteUser();
        }
    }

    @Test(expected=BridgeSDKException.class)
    public void cannotBeAccessedByResearcher() throws Exception {
        TestUser researcher = TestUserHelper.createAndSignInUser(StudyConsentTest.class, true, Role.RESEARCHER);
        try {
            StudyConsent consent = new StudyConsent();
            consent.setDocumentContent("<p>Test content.</p>");

            researcher.getClient(StudyConsentsApi.class).createConsent(researcher.getDefaultSubpopulation(), consent).execute();
        } finally {
            researcher.signOutAndDeleteUser();
        }
    }
    
    @Test
    public void addAndActivateConsent() throws Exception {
        StudyConsentsApi studyConsentsApi = developer.getClient(StudyConsentsApi.class);
        SubpopulationsApi subpopulationsApi = developer.getClient(SubpopulationsApi.class);

        // Create a subpopulation to test this so we can delete the subpopulation to clean up.
        // Because we create it from scratch, we know the exact number of consents that are in it.
        // It is not required, so shouldn't prevent other tests from creating users.
        Subpopulation subpop = new Subpopulation();
        subpop.setName(Tests.randomIdentifier(StudyConsentTest.class));
        subpop.setRequired(false);
        subpop.addStudyIdsAssignedOnConsentItem(STUDY_ID_1);
        GuidVersionHolder holder = subpopulationsApi.createSubpopulation(subpop).execute().body();
        
        subpop.setGuid(holder.getGuid());
        subpop.setVersion(holder.getVersion());
        subpopGuid = holder.getGuid();
        
        StudyConsent consent = new StudyConsent();
        consent.setDocumentContent("<p>Test content</p>");
        studyConsentsApi.createConsent(subpopGuid, consent).execute();

        StudyConsentList studyConsents = studyConsentsApi.getAllConsents(subpopGuid).execute().body();

        assertEquals(2, studyConsents.getItems().size());

        StudyConsent current = studyConsentsApi.getConsent(subpopGuid, studyConsents.getItems().get(0).getCreatedOn())
                .execute().body();
        assertTrue(current.getDocumentContent().contains("<p>Test content</p>"));
        assertNotNull(current.getCreatedOn());

        studyConsentsApi.publishConsent(subpopGuid, current.getCreatedOn()).execute();

        StudyConsent published = studyConsentsApi.getPublishedConsent(subpopGuid).execute().body();
        subpop = subpopulationsApi.getSubpopulation(subpopGuid).execute().body();
        assertEquals(published.getCreatedOn(), subpop.getPublishedConsentCreatedOn());
        
        studyConsentsApi.createConsent(subpopGuid, current).execute();
        
        StudyConsent newOne = studyConsentsApi.getMostRecentConsent(subpopGuid).execute().body();
        assertTrue(newOne.getCreatedOn().isAfter(published.getCreatedOn()));
        
        StudyConsentList studyConsents2 = studyConsentsApi.getAllConsents(subpopGuid).execute().body();
        assertEquals(3, studyConsents2.getItems().size());
    }

}
