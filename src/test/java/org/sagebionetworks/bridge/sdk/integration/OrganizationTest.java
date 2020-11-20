package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.sagebionetworks.bridge.rest.model.Role.ADMIN;
import static org.sagebionetworks.bridge.rest.model.Role.DEVELOPER;
import static org.sagebionetworks.bridge.rest.model.Role.RESEARCHER;
import static org.sagebionetworks.bridge.sdk.integration.Tests.ORG_ID_1;
import static org.sagebionetworks.bridge.sdk.integration.Tests.STUDY_ID_1;
import static org.sagebionetworks.bridge.util.IntegTestUtils.SAGE_ID;

import java.io.IOException;
import java.util.stream.Collectors;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.api.AssessmentsApi;
import org.sagebionetworks.bridge.rest.api.OrganizationsApi;
import org.sagebionetworks.bridge.rest.api.ParticipantsApi;
import org.sagebionetworks.bridge.rest.api.SharedAssessmentsApi;
import org.sagebionetworks.bridge.rest.api.StudiesApi;
import org.sagebionetworks.bridge.rest.exceptions.BadRequestException;
import org.sagebionetworks.bridge.rest.exceptions.ConstraintViolationException;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.model.AccountSummary;
import org.sagebionetworks.bridge.rest.model.AccountSummaryList;
import org.sagebionetworks.bridge.rest.model.AccountSummarySearch;
import org.sagebionetworks.bridge.rest.model.Assessment;
import org.sagebionetworks.bridge.rest.model.Message;
import org.sagebionetworks.bridge.rest.model.Organization;
import org.sagebionetworks.bridge.rest.model.OrganizationList;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.StudyList;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;

public class OrganizationTest {
    private TestUser admin;
    private TestUser orgAdmin;
    private TestUser user;
    
    private String orgId1;
    private Organization org1;
    
    private String orgId2;
    private Organization org2;

    private String orgId3;
    private Organization org3;

    private Assessment assessment;

    @Before
    public void before() throws Exception {
        admin = TestUserHelper.getSignedInAdmin();
    }

    @After
    public void deleteUser() throws Exception {
        if (user != null) {
            user.signOutAndDeleteUser();
        }
    }
    
    @After
    public void deleteOrgAdmin() throws Exception {
        if (orgAdmin != null) {
            orgAdmin.signOutAndDeleteUser();
        }
    }
    
    @After
    public void after() throws Exception {
        OrganizationsApi orgApi = admin.getClient(OrganizationsApi.class);
        if (org1 != null && orgId1 != null) {
            orgApi.deleteOrganization(orgId1).execute();    
        }
        if (org2 != null && orgId2 != null) {
            orgApi.deleteOrganization(orgId2).execute();
        }
        if (org3 != null && orgId3 != null) {
            orgApi.deleteOrganization(orgId3).execute();
        }
        AssessmentsApi assessmentsApi = admin.getClient(AssessmentsApi.class);
        SharedAssessmentsApi sharedAssessmentsApi = admin.getClient(SharedAssessmentsApi.class);
        if (assessment != null) {
            try {
                assessmentsApi.deleteAssessment(assessment.getGuid(), true);
            } catch (Exception ignored) {
            }
            try {
                sharedAssessmentsApi.deleteSharedAssessment(assessment.getGuid(), true);
            } catch (Exception ignored) {
            }
        }
    }
    
    @Test
    public void test() throws Exception {
        OrganizationsApi orgApi = admin.getClient(OrganizationsApi.class);
        
        orgId1 = Tests.randomIdentifier(OrganizationTest.class);

        Organization newOrg = new Organization();
        newOrg.setIdentifier(orgId1);
        newOrg.setName("Test Name");
        newOrg.setDescription("A description");
        
        org1 = orgApi.createOrganization(newOrg).execute().body();
        assertNotNull(org1.getVersion());
        assertNotNull(org1.getCreatedOn());
        assertNotNull(org1.getModifiedOn());
        
        org1.setDescription("Description updated");
        org1.setIdentifier("not-the-identifier"); // this has no effect.
        Organization updated = orgApi.updateOrganization(orgId1, org1).execute().body();
        assertEquals(orgId1, updated.getIdentifier());
        assertEquals("Description updated", updated.getDescription());
        assertTrue(updated.getVersion() > org1.getVersion());
        
        OrganizationList list = orgApi.getOrganizations(0, 50).execute().body();
        assertEquals(Integer.valueOf(0), list.getRequestParams().getOffsetBy());
        assertEquals(Integer.valueOf(50), list.getRequestParams().getPageSize());
        
        Organization found = findOrganization(list, orgId1);
        assertNull(found.getCreatedOn());
        assertNull(found.getModifiedOn());
        assertEquals("Test Name", found.getName());
        assertEquals(orgId1, found.getIdentifier());
        assertEquals("Description updated", found.getDescription());
        
        found = orgApi.getOrganization(orgId1).execute().body();
        assertNotNull(found.getCreatedOn());
        assertNotNull(found.getModifiedOn());
        assertNotNull(found.getVersion());
        assertEquals("Organization", found.getType());
        assertEquals("Test Name", found.getName());
        assertEquals(orgId1, found.getIdentifier());
        assertEquals("Description updated", found.getDescription());
        
        Message message = orgApi.deleteOrganization(orgId1).execute().body();
        org1 = null;
        assertEquals("Organization deleted.", message.getMessage());
        
        try {
            orgApi.getOrganization(orgId1).execute().body();
            fail("Should have thrown exception");
        } catch(EntityNotFoundException e) {
        }
        
        list = orgApi.getOrganizations(null, null).execute().body();
        found = findOrganization(list, orgId1);
        assertNull(found);
    }
    
    @Test
    public void testMembership() throws Exception {
        // Create an organization
        OrganizationsApi superadminOrgApi = admin.getClient(OrganizationsApi.class);
        
        orgId1 = Tests.randomIdentifier(OrganizationTest.class);
        Organization newOrg1 = new Organization();
        newOrg1.setIdentifier(orgId1);
        newOrg1.setName("Test Org 1");
        org1 = superadminOrgApi.createOrganization(newOrg1).execute().body();
        
        orgId2 = Tests.randomIdentifier(OrganizationTest.class);
        Organization newOrg2 = new Organization();
        newOrg2.setIdentifier(orgId2);
        newOrg2.setName("Test Org 2");
        org2 = superadminOrgApi.createOrganization(newOrg2).execute().body();
        
        // Create an admin in organization 1, with researcher permissions to access the participant APIs
        orgAdmin = TestUserHelper.createAndSignInUser(OrganizationTest.class, false, ADMIN, RESEARCHER);
        superadminOrgApi.addMember(orgId1, orgAdmin.getUserId()).execute();
        OrganizationsApi appAdminOrgApi = orgAdmin.getClient(OrganizationsApi.class);
        
        // session should show organizational membership
        orgAdmin.signInAgain();
        assertEquals(orgId1, orgAdmin.getSession().getOrgMembership());
        
        // create a user. TestUserHelper puts admins in the Sage Bionetworks organization, so for this
        // test, remove the user first.
        user = TestUserHelper.createAndSignInUser(OrganizationTest.class, true, DEVELOPER);
        admin.getClient(OrganizationsApi.class).removeMember(SAGE_ID, user.getUserId()).execute();
        
        // the user is unassigned and should appear in the unassigned API
        AccountSummarySearch search = new AccountSummarySearch();
        AccountSummaryList list = orgAdmin.getClient(OrganizationsApi.class)
                .getUnassignedAdminAccounts(search).execute().body();
        assertTrue(list.getItems().stream().anyMatch((summary) -> summary.getId().equals(user.getUserId())));
        
        // cannot change organizational affiliation on an update
        ParticipantsApi participantsApi = orgAdmin.getClient(ParticipantsApi.class);
        StudyParticipant participant = participantsApi.getParticipantById(user.getUserId(), false).execute().body();
        participant.setOrgMembership(orgId2);
        participantsApi.updateParticipant(user.getUserId(), participant).execute();
        
        // Membership has not changed. It didn't throw an error, it did nothing.
        participant = participantsApi.getParticipantById(user.getUserId(), false).execute().body();
        assertNull(participant.getOrgMembership());

        // add someone to the organization
        appAdminOrgApi.addMember(orgId1, user.getUserId()).execute();
        participant = participantsApi.getParticipantById(user.getUserId(), false).execute().body();
        assertEquals(orgId1, participant.getOrgMembership());
        
        // The account should now be listed as a member
        list = appAdminOrgApi.getMembers(orgId1, new AccountSummarySearch()).execute().body();
        assertEquals(ImmutableSet.of(user.getEmail(), orgAdmin.getEmail()), 
                list.getItems().stream().map(AccountSummary::getEmail).collect(Collectors.toSet()));
        assertEquals(Integer.valueOf(2), list.getTotal()); // the admin and the user
        for (AccountSummary summary : list.getItems()) { 
            assertEquals(orgId1, summary.getOrgMembership());
        }
        
        // This user is no longer in the unassigned users list
        list = orgAdmin.getClient(OrganizationsApi.class)
                .getUnassignedAdminAccounts(search).execute().body();
        assertFalse(list.getItems().stream().anyMatch((summary) -> summary.getId().equals(user.getUserId())));

        // can remove someone from the organization
        appAdminOrgApi.removeMember(orgId1, user.getUserId()).execute();
        
        // account is no longer listed as a member
        list = appAdminOrgApi.getMembers(orgId1, new AccountSummarySearch()).execute().body();
        assertEquals(ImmutableSet.of(orgAdmin.getEmail()), 
                list.getItems().stream().map(AccountSummary::getEmail).collect(Collectors.toSet()));
        assertEquals(Integer.valueOf(1), list.getTotal());
        
        list = orgAdmin.getClient(OrganizationsApi.class)
                .getUnassignedAdminAccounts(search).execute().body();
        assertTrue(list.getItems().stream().anyMatch((summary) -> summary.getId().equals(user.getUserId())));
    }
    
    @Test
    public void testSponsorship() throws Exception {
        OrganizationsApi adminOrgApi = admin.getClient(OrganizationsApi.class);
        try {
            // In essence, let's clean this up before we test. It throws an exception if
            // not associated.
            try {
                adminOrgApi.removeStudySponsorship(ORG_ID_1, STUDY_ID_1).execute();    
            } catch(BadRequestException e) {
            }
            
            adminOrgApi.addStudySponsorship(ORG_ID_1, STUDY_ID_1).execute();
            
            StudyList list = adminOrgApi.getSponsoredStudies(ORG_ID_1, null, null).execute().body();
            assertTrue(list.getItems().stream().anyMatch((study) -> study.getIdentifier().equals(STUDY_ID_1)));
            
            adminOrgApi.removeStudySponsorship(ORG_ID_1, STUDY_ID_1).execute();

            list = adminOrgApi.getSponsoredStudies(ORG_ID_1, null, null).execute().body();
            assertFalse(list.getItems().stream().anyMatch((study) -> study.getIdentifier().equals(STUDY_ID_1)));
        } finally {
            // This must be added back after the test.
            adminOrgApi.addStudySponsorship(ORG_ID_1, STUDY_ID_1).execute();
        }
    }

    @Test
    public void testDeleteWithAssessment() throws IOException {
        // Create an organization
        OrganizationsApi orgApi = admin.getClient(OrganizationsApi.class);
        orgId3 = Tests.randomIdentifier(OrganizationTest.class);
        org3 = new Organization();
        org3.setIdentifier(orgId3);
        org3.setName("Test Name");
        org3.setDescription("A description");

        org3 = orgApi.createOrganization(org3).execute().body();

        AssessmentsApi assessmentApi = admin.getClient(AssessmentsApi.class);
        Assessment unsavedAssessment = new Assessment()
                .identifier(Tests.randomIdentifier(Assessment.class))
                .title("Title")
                .summary("Summary")
                .validationStatus("Not validated")
                .normingStatus("Not normed")
                .osName("Both")
                .ownerId(orgId3);

        assessment = assessmentApi.createAssessment(unsavedAssessment).execute().body();
        assertNotNull(assessment);

        try {
            orgApi.deleteOrganization(orgId3).execute();
            fail("Should have thrown an exception");
        } catch (ConstraintViolationException ignored) {
        }

        assessmentApi.publishAssessment(assessment.getGuid(), null).execute().body();
        assessmentApi.deleteAssessment(assessment.getGuid(), true).execute();
        try {
            orgApi.deleteOrganization(orgId3).execute();
            fail("Should have thrown an exception");
        } catch (ConstraintViolationException ignored) {
        }

        SharedAssessmentsApi sharedAssessmentsApi = admin.getClient(SharedAssessmentsApi.class);
        Assessment shared = sharedAssessmentsApi.getLatestSharedAssessmentRevision(assessment.getIdentifier()).execute().body();
        sharedAssessmentsApi.deleteSharedAssessment(shared.getGuid(), true).execute();

        orgApi.deleteOrganization(orgId3).execute();
        orgId3 = null;
        org3 = null;
    }

    private Organization findOrganization(OrganizationList list, String id) {
        for (Organization org : list.getItems()) {
            if (org.getIdentifier().equals(id)) {
                return org;
            }
        }
        return null;
    }
    
}
