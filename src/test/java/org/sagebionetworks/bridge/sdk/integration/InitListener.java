package org.sagebionetworks.bridge.sdk.integration;

import static org.sagebionetworks.bridge.sdk.integration.Tests.ORG_ID_1;
import static org.sagebionetworks.bridge.sdk.integration.Tests.ORG_ID_2;
import static org.sagebionetworks.bridge.sdk.integration.Tests.SAGE_ID;
import static org.sagebionetworks.bridge.sdk.integration.Tests.SAGE_NAME;
import static org.sagebionetworks.bridge.sdk.integration.Tests.STUDY_ID_1;
import static org.sagebionetworks.bridge.sdk.integration.Tests.STUDY_ID_2;
import static org.sagebionetworks.bridge.util.IntegTestUtils.TEST_APP_ID;

import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.RunListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.sagebionetworks.bridge.rest.api.OrganizationsApi;
import org.sagebionetworks.bridge.rest.api.StudiesApi;
import org.sagebionetworks.bridge.rest.api.SubpopulationsApi;
import org.sagebionetworks.bridge.rest.exceptions.ConstraintViolationException;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.model.Organization;
import org.sagebionetworks.bridge.rest.model.Study;
import org.sagebionetworks.bridge.rest.model.Subpopulation;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;

/**
 * We have some frequently used model classes that need specific relationships for
 * our tests to work given our permissions model. This listener sets these up one
 * time before running all our integration tests. These objects are safe to leave 
 * between runs of our tests in the API study (there is no cleanup).
 */
public class InitListener extends RunListener {
    private static final Logger LOG = LoggerFactory.getLogger(InitListener.class);
    
    private boolean testRunInitialized;
    
    @Override
    public void testRunStarted(Description description) throws Exception {
        if (testRunInitialized) {
            return;
        }
        // Must do this first to initialize the logger correctly
        TestUser admin = TestUserHelper.getSignedInAdmin();
        
        LOG.info("Initializing some frequently used test objects...");
        
        StudiesApi studiesApi = admin.getClient(StudiesApi.class);
        try {
            studiesApi.getStudy(STUDY_ID_1).execute();
        } catch(EntityNotFoundException e) {
            Study study = new Study().identifier(STUDY_ID_1).name(STUDY_ID_1);
            studiesApi.createStudy(study).execute();
            LOG.info("  Creating study “{}”", STUDY_ID_1);
        }
        try {
            studiesApi.getStudy(STUDY_ID_2).execute();
        } catch(EntityNotFoundException e) {
            Study study = new Study().identifier(STUDY_ID_2).name(STUDY_ID_2);
            studiesApi.createStudy(study).execute();
            LOG.info("  Creating study “{}”", STUDY_ID_2);
        }
        
        OrganizationsApi orgsApi = admin.getClient(OrganizationsApi.class);
        try {
            orgsApi.getOrganization(ORG_ID_1).execute();
        } catch(EntityNotFoundException e) {
            Organization org = new Organization().identifier(ORG_ID_1).name(ORG_ID_1)
                    .description("Org 1 sponsors study 1 only");
            orgsApi.createOrganization(org).execute();
            LOG.info("  Creating organization “{}”", ORG_ID_1);
        }
        try {
            orgsApi.getOrganization(ORG_ID_2).execute();
        } catch(EntityNotFoundException e) {
            Organization org = new Organization().identifier(ORG_ID_2).name(ORG_ID_2)
                    .description("Org 2 does not sponsor any studies");
            orgsApi.createOrganization(org).execute();
            LOG.info("  Creating organization “{}”", ORG_ID_2);
        }
        try {
            orgsApi.getOrganization(SAGE_ID).execute();
        } catch(EntityNotFoundException e) {
            Organization org = new Organization().identifier(SAGE_ID).name(SAGE_NAME)
                    .description("Sage sponsors study1 and study2");
            orgsApi.createOrganization(org).execute();
            LOG.info("  Creating organization “{}”", ORG_ID_2);
        }
        try {
            orgsApi.addStudySponsorship(SAGE_ID, STUDY_ID_1).execute();
            LOG.info("  {} sponsoring study “{}”", SAGE_NAME, STUDY_ID_1);
        } catch(ConstraintViolationException e) {
        }
        try {
            orgsApi.addStudySponsorship(SAGE_ID, STUDY_ID_2).execute();
            LOG.info("  {} sponsoring study “{}”", SAGE_NAME, STUDY_ID_2);
        } catch(ConstraintViolationException e) {
        }
        try {
            orgsApi.addStudySponsorship(ORG_ID_1, STUDY_ID_1).execute();
            LOG.info("  “{}” sponsoring study “{}”", ORG_ID_1, STUDY_ID_1);
        } catch(ConstraintViolationException e) {
        }
        try {
            orgsApi.addStudySponsorship(ORG_ID_2, STUDY_ID_2).execute();
            LOG.info("  “{}” sponsoring study “{}”", ORG_ID_2, STUDY_ID_2);
        } catch(ConstraintViolationException e) {
        }
        
        SubpopulationsApi subpopApi = admin.getClient(SubpopulationsApi.class);
        Subpopulation subpop = subpopApi.getSubpopulation(TEST_APP_ID).execute().body();
        if (subpop.getStudyIdsAssignedOnConsent().isEmpty()) {
            subpop.getStudyIdsAssignedOnConsent().add(STUDY_ID_1);
            subpopApi.updateSubpopulation(subpop.getGuid(), subpop).execute();
            LOG.info("  “{}” consent now enrolls participants in study “{}”", subpop.getGuid(), STUDY_ID_1);
        }
        
        testRunInitialized = true;
    }
    @Override
    public void testRunFinished(Result result) throws Exception {
        // noop
    }
}
