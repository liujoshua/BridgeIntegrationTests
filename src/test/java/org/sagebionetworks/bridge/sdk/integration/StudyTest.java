package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.common.collect.ImmutableList;
import org.apache.commons.lang3.RandomStringUtils;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.sagebionetworks.client.SynapseAdminClientImpl;
import org.sagebionetworks.client.SynapseClient;
import org.sagebionetworks.client.exceptions.SynapseException;
import org.sagebionetworks.reflection.model.PaginatedResults;
import org.sagebionetworks.repo.model.AccessControlList;
import org.sagebionetworks.repo.model.Entity;
import org.sagebionetworks.repo.model.MembershipInvtnSubmission;
import org.sagebionetworks.repo.model.Project;
import org.sagebionetworks.repo.model.ResourceAccess;
import org.sagebionetworks.repo.model.Team;
import org.sagebionetworks.repo.model.util.ModelConstants;
import retrofit2.Response;

import org.sagebionetworks.bridge.config.PropertiesConfig;
import org.sagebionetworks.bridge.rest.ClientManager;
import org.sagebionetworks.bridge.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.rest.api.StudiesApi;
import org.sagebionetworks.bridge.rest.api.UploadsApi;
import org.sagebionetworks.bridge.rest.exceptions.BadRequestException;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.exceptions.UnauthorizedException;
import org.sagebionetworks.bridge.rest.exceptions.UnsupportedVersionException;
import org.sagebionetworks.bridge.rest.model.AndroidAppLink;
import org.sagebionetworks.bridge.rest.model.AppleAppLink;
import org.sagebionetworks.bridge.rest.model.ClientInfo;
import org.sagebionetworks.bridge.rest.model.EmailTemplate;
import org.sagebionetworks.bridge.rest.model.Message;
import org.sagebionetworks.bridge.rest.model.MimeType;
import org.sagebionetworks.bridge.rest.model.OAuthProvider;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.Study;
import org.sagebionetworks.bridge.rest.model.StudyList;
import org.sagebionetworks.bridge.rest.model.Upload;
import org.sagebionetworks.bridge.rest.model.UploadFieldDefinition;
import org.sagebionetworks.bridge.rest.model.UploadFieldType;
import org.sagebionetworks.bridge.rest.model.UploadList;
import org.sagebionetworks.bridge.rest.model.UploadRequest;
import org.sagebionetworks.bridge.rest.model.UploadSession;
import org.sagebionetworks.bridge.rest.model.UploadValidationStrictness;
import org.sagebionetworks.bridge.rest.model.VersionHolder;
import org.sagebionetworks.bridge.sdk.integration.TestUserHelper.TestUser;

public class StudyTest {
    
    private TestUser admin;
    private String studyId;
    private SynapseClient synapseClient;
    private Project project;
    private Team team;

    private static final String USER_NAME = "synapse.user";
    private static final String SYNAPSE_API_KEY_NAME = "synapse.api.key";
    private static final String EXPORTER_SYNAPSE_USER_ID_NAME = "exporter.synapse.user.id";
    private static final String TEST_USER_ID_NAME = "test.synapse.user.id";

    // synapse related attributes
    private static String SYNAPSE_USER;
    private static String SYNAPSE_API_KEY;
    private static String EXPORTER_SYNAPSE_USER_ID;
    private static Long TEST_USER_ID; // test user exists in synapse
    private static final String CONFIG_FILE = "bridge-sdk-test.properties";
    private static final String DEFAULT_CONFIG_FILE = CONFIG_FILE;
    private static final String USER_CONFIG_FILE = System.getProperty("user.home") + "/" + CONFIG_FILE;

    private static final int MAX_PAGE_SIZE = 100;
    
    @BeforeClass
    public static void ensureReauthenticationIsDisabled() throws Exception {
        TestUser admin = TestUserHelper.getSignedInAdmin();
        StudiesApi studiesApi = admin.getClient(StudiesApi.class);
        
        Study study = studiesApi.getStudy("api").execute().body();
        if (study.getReauthenticationEnabled()) {
            study.setReauthenticationEnabled(false);
            studiesApi.updateStudy("api", study).execute();
        }
    }
    
    @Before
    public void before() throws IOException {
        // pre-load test user id and exporter synapse user id
        setupProperties();

        admin = TestUserHelper.getSignedInAdmin();
        synapseClient = new SynapseAdminClientImpl();
        synapseClient.setUserName(SYNAPSE_USER);
        synapseClient.setApiKey(SYNAPSE_API_KEY);
    }

    @After
    public void after() throws Exception {
        if (studyId != null) {
            admin.getClient(StudiesApi.class).deleteStudy(studyId, true).execute();
        }
        if (project != null) {
            synapseClient.deleteEntityById(project.getId());
        }
        if (team != null) {
            synapseClient.deleteTeam(team.getId());
        }
        admin.signOut();
    }

    private org.sagebionetworks.bridge.config.Config bridgeIntegTestConfig() throws IOException {
        Path localConfigPath = Paths.get(USER_CONFIG_FILE);

        if (Files.exists(localConfigPath)) {
            return new PropertiesConfig(DEFAULT_CONFIG_FILE, localConfigPath);
        } else {
            return new PropertiesConfig(DEFAULT_CONFIG_FILE);
        }
    }

    private void setupProperties() throws IOException {
        org.sagebionetworks.bridge.config.Config config = bridgeIntegTestConfig();

        SYNAPSE_USER = config.get(USER_NAME);
        SYNAPSE_API_KEY = config.get(SYNAPSE_API_KEY_NAME);
        EXPORTER_SYNAPSE_USER_ID = config.get(EXPORTER_SYNAPSE_USER_ID_NAME);
        TEST_USER_ID = Long.parseLong(config.get(TEST_USER_ID_NAME));
    }

    // Disabled this test: This test stomps the Synapse configuration in the API study. This is used by the
    // Bridge-Exporter to test the Bridge-Exporter as part of the release process. The conflict introduced in this test
    // causes Bridge-Exporter tests to fail.
    @Test
    @Ignore
    public void createSynapseProjectTeam() throws IOException, SynapseException {
        // only use developer to signin
        TestUser developer = TestUserHelper.createAndSignInUser(StudyTest.class, false, Role.DEVELOPER);
        try {
            StudiesApi studiesApi = developer.getClient(StudiesApi.class);

            // integration test with synapseclient
            // pre-setup - remove current study's project and team info
            Study currentStudy = studiesApi.getUsersStudy().execute().body();
            currentStudy.setSynapseDataAccessTeamId(null);
            currentStudy.setSynapseProjectId(null);

            studiesApi.updateUsersStudy(currentStudy).execute().body();

            // execute
            studiesApi.createSynapseProjectTeam(ImmutableList.of(TEST_USER_ID.toString())).execute().body();
            // verify study
            Study newStudy = studiesApi.getUsersStudy().execute().body();
            assertEquals(newStudy.getIdentifier(), currentStudy.getIdentifier());
            String projectId = newStudy.getSynapseProjectId();
            Long teamId = newStudy.getSynapseDataAccessTeamId();

            // verify if project and team exists
            Entity project = synapseClient.getEntityById(projectId);
            assertNotNull(project);
            assertEquals(project.getEntityType(), "org.sagebionetworks.repo.model.Project");
            this.project = (Project) project;
            Team team = synapseClient.getTeam(teamId.toString());
            assertNotNull(team);
            this.team = team;

            // project acl
            AccessControlList projectAcl = synapseClient.getACL(projectId);
            Set<ResourceAccess> projectRa =  projectAcl.getResourceAccess();
            assertNotNull(projectRa);
            assertEquals(projectRa.size(), 4); // target user, exporter and bridgepf itself --- and the new team
            // first verify exporter
            List<ResourceAccess> retListForExporter = projectRa.stream()
                    .filter(ra -> ra.getPrincipalId().equals(Long.parseLong(EXPORTER_SYNAPSE_USER_ID)))
                    .collect(Collectors.toList());

            assertNotNull(retListForExporter);
            assertEquals(retListForExporter.size(), 1); // should only have one exporter info
            ResourceAccess exporterRa = retListForExporter.get(0);
            assertNotNull(exporterRa);
            assertEquals(exporterRa.getPrincipalId().toString(), EXPORTER_SYNAPSE_USER_ID);
            assertEquals(exporterRa.getAccessType(), ModelConstants.ENITY_ADMIN_ACCESS_PERMISSIONS);
            // then verify target user
            List<ResourceAccess> retListForUser = projectRa.stream()
                    .filter(ra -> ra.getPrincipalId().equals(TEST_USER_ID))
                    .collect(Collectors.toList());

            assertNotNull(retListForUser);
            assertEquals(retListForUser.size(), 1); // should only have target user info
            ResourceAccess userRa = retListForUser.get(0);
            assertNotNull(userRa);
            assertEquals(userRa.getPrincipalId(), TEST_USER_ID);
            assertEquals(userRa.getAccessType(), ModelConstants.ENITY_ADMIN_ACCESS_PERMISSIONS);

            // membership invitation to target user
            // (teamId, inviteeId, limit, offset)
            PaginatedResults<MembershipInvtnSubmission> retInvitations =  synapseClient.getOpenMembershipInvitationSubmissions(teamId.toString(), TEST_USER_ID.toString(), 1, 0);
            List<MembershipInvtnSubmission> invitationList = retInvitations.getResults();
            assertEquals(invitationList.size(), 1); // only one invitation submission from newly created team to target user
            MembershipInvtnSubmission invtnSubmission = invitationList.get(0);
            assertEquals(invtnSubmission.getInviteeId(), TEST_USER_ID.toString());
            assertEquals(invtnSubmission.getTeamId(), teamId.toString());
        } finally {
            developer.signOutAndDeleteUser();
        }
    }

    @Test
    public void crudStudy() throws Exception {
        StudiesApi studiesApi = admin.getClient(StudiesApi.class);

        studyId = Tests.randomIdentifier(StudyTest.class);
        Study study = Tests.getStudy(studyId, null);
        assertNull("study version should be null", study.getVersion());

        // Set these flags to the non-default value to verify that studies are always created with these flags set to
        // the default value.
        study.setConsentNotificationEmailVerified(true);
        study.setStudyIdExcludedInExport(false);

        // Set validation strictness to null, to verify the default.
        study.setUploadValidationStrictness(null);

        VersionHolder holder = studiesApi.createStudy(study).execute().body();
        assertNotNull(holder.getVersion());

        Study newStudy = studiesApi.getStudy(study.getIdentifier()).execute().body();
        
        study.addDataGroupsItem("test_user"); // added by the server, required for equality of dataGroups.

        // Verify study has password/email templates
        assertTrue("autoVerificationEmailSuppressed should be true",
                newStudy.getAutoVerificationEmailSuppressed());
        assertNotNull("password policy should not be null", newStudy.getPasswordPolicy());
        assertNotNull("verify email template should not be null", newStudy.getVerifyEmailTemplate());
        assertNotNull("password reset template should not be null", newStudy.getResetPasswordTemplate());
        assertNotNull("email sign in template should not be null", newStudy.getEmailSignInTemplate());
        assertNotNull("account exists template should not be null", newStudy.getAccountExistsTemplate());
        assertEquals("name should be equal", study.getName(), newStudy.getName());
        assertEquals("minAgeOfConsent should be equal", study.getMinAgeOfConsent(), newStudy.getMinAgeOfConsent());
        assertEquals("sponsorName should be equal", study.getSponsorName(), newStudy.getSponsorName());
        assertTrue("strictUploadValidationEnabled should be true", newStudy.getStrictUploadValidationEnabled());
        assertEquals("supportEmail should be equal", study.getSupportEmail(), newStudy.getSupportEmail());
        assertEquals("technicalEmail should be equal", study.getTechnicalEmail(), newStudy.getTechnicalEmail());
        assertTrue("usesCustomExportSchedule should be true", study.getUsesCustomExportSchedule());
        assertEquals("consentNotificationEmail should be equal", study.getConsentNotificationEmail(), newStudy.getConsentNotificationEmail());
        assertEquals("userProfileAttributes should be equal", study.getUserProfileAttributes(), newStudy.getUserProfileAttributes());
        assertEquals("taskIdentifiers should be equal", study.getTaskIdentifiers(), newStudy.getTaskIdentifiers());
        assertTrue("dataGroups should be equal", Tests.assertListsEqualIgnoringOrder(study.getDataGroups(), newStudy.getDataGroups()));
        assertEquals("android minSupportedAppVersions should be equal", study.getMinSupportedAppVersions().get("Android"),
                newStudy.getMinSupportedAppVersions().get("Android"));
        assertEquals("iOS minSupportedAppVersions should be equal", study.getMinSupportedAppVersions().get("iPhone OS"),
                newStudy.getMinSupportedAppVersions().get("iPhone OS"));
        
        assertEquals("android push ARN should be equal", study.getPushNotificationARNs().get("Android"),
                newStudy.getPushNotificationARNs().get("Android"));        
        assertEquals("iOS push ARN should be equal", study.getPushNotificationARNs().get("iPhone OS"),
                newStudy.getPushNotificationARNs().get("iPhone OS"));   
        
        // Verify OAuth providers
        OAuthProvider myProvider = newStudy.getOAuthProviders().get("myProvider");
        assertEquals("OAuth provider should have clientId", "clientId", myProvider.getClientId());
        assertEquals("OAuth provider should have secret", "secret", myProvider.getSecret());
        assertEquals("OAuth provider should have endpoint", "https://www.server.com/", myProvider.getEndpoint());
        assertEquals("OAuth provider should have callbackUrl", "https://client.callback.com/",
                myProvider.getCallbackUrl());

        // Verify other defaults
        assertFalse("consentNotificationEmailVerified should be false", newStudy
                .getConsentNotificationEmailVerified());
        assertTrue("studyIdExcludedInExport should be true", newStudy.getStudyIdExcludedInExport());
        assertEquals("uploadValidationStrictness should be REPORT", UploadValidationStrictness.REPORT,
                newStudy.getUploadValidationStrictness());
        assertTrue("healthCodeExportEnabled should be true", newStudy.getHealthCodeExportEnabled());
        assertTrue("emailVerificationEnabled should be true", newStudy.getEmailVerificationEnabled());
        assertTrue("emailSignInEnabled should be true", newStudy.getEmailSignInEnabled());
        
        assertEquals(1, newStudy.getAndroidAppLinks().size());
        AndroidAppLink androidAppLink = newStudy.getAndroidAppLinks().get(0);
        assertEquals(Tests.PACKAGE, androidAppLink.getNamespace());
        assertEquals(Tests.MOBILE_APP_NAME, androidAppLink.getPackageName());
        assertEquals(1, androidAppLink.getSha256CertFingerprints().size());
        assertEquals(Tests.FINGERPRINT, androidAppLink.getSha256CertFingerprints().get(0));
        
        assertEquals(1, newStudy.getAppleAppLinks().size());
        AppleAppLink appleAppLink = newStudy.getAppleAppLinks().get(0);
        assertEquals(Tests.APP_ID, appleAppLink.getAppID());
        assertEquals(1, appleAppLink.getPaths().size());
        String path = "/" + newStudy.getIdentifier() + "/*";
        assertEquals(path, appleAppLink.getPaths().get(0));

        // assert disable study
        assertTrue(newStudy.getDisableExport());

        Long oldVersion = newStudy.getVersion();
        alterStudy(newStudy);
        studiesApi.updateStudy(newStudy.getIdentifier(), newStudy).execute().body();

        Study newerStudy = studiesApi.getStudy(newStudy.getIdentifier()).execute().body();
        assertTrue(newerStudy.getVersion() > oldVersion);

        assertFalse(newerStudy.getAutoVerificationEmailSuppressed());
        assertEquals("Altered Test Study [SDK]", newerStudy.getName());
        assertFalse(newerStudy.getStrictUploadValidationEnabled());
        assertEquals("test3@test.com", newerStudy.getSupportEmail());
        assertEquals(UploadValidationStrictness.WARNING, newerStudy.getUploadValidationStrictness());
        assertEquals("bridge-testing+test4@sagebase.org", newerStudy.getConsentNotificationEmail());

        assertEquals("subject", newerStudy.getEmailSignInTemplate().getSubject());
        assertEquals("body ${token}", newerStudy.getEmailSignInTemplate().getBody());
        assertEquals(MimeType.TEXT_HTML, newerStudy.getEmailSignInTemplate().getMimeType());

        assertEquals("Your ${studyName} account", newerStudy.getAccountExistsTemplate().getSubject());
        assertEquals("<p>${url}</p>", newerStudy.getAccountExistsTemplate().getBody());
        assertEquals(MimeType.TEXT_HTML, newerStudy.getAccountExistsTemplate().getMimeType());
        
        assertEquals("endpoint2", newerStudy.getOAuthProviders().get("myProvider").getEndpoint());
        assertEquals("callbackUrl2", newerStudy.getOAuthProviders().get("myProvider").getCallbackUrl());
        
        assertEquals("resetPasswordSmsTemplate ${url}", study.getResetPasswordSmsTemplate().getMessage());
        assertEquals("phoneSignInSmsTemplate ${token}", study.getPhoneSignInSmsTemplate().getMessage());
        assertEquals("appInstallLinkSmsTemplate ${url}", study.getAppInstallLinkSmsTemplate().getMessage());
        assertEquals("verifyPhoneSmsTemplate ${token}", study.getVerifyPhoneSmsTemplate().getMessage());
        assertEquals("accountExistsSmsTemplate ${token}", study.getAccountExistsSmsTemplate().getMessage());
        
        newerStudy.setAccountExistsTemplate(new EmailTemplate().subject("Updated subject").body("Updated ${url} body")
                .mimeType(MimeType.TEXT_HTML));        
        
        assertTrue(newerStudy.getAppleAppLinks().isEmpty());
        assertTrue(newerStudy.getAndroidAppLinks().isEmpty());
        
        // Set stuff that only an admin can set.
        newerStudy.setEmailSignInEnabled(false);
        newerStudy.setStudyIdExcludedInExport(false);

        // ConsentNotificationEmailVerified cannot be set by the update API.
        newerStudy.setConsentNotificationEmailVerified(true);

        studiesApi.updateStudy(newerStudy.getIdentifier(), newerStudy).execute().body();
        Study newestStudy = studiesApi.getStudy(newStudy.getIdentifier()).execute().body();

        assertFalse("emailSignInEnabled should be false after update", newestStudy.getEmailSignInEnabled());
        assertFalse("studyIdExcludedInExport should be false after update", newestStudy.getStudyIdExcludedInExport());
        assertFalse("consentNotificationEmailVerified should be false after update", newestStudy.
                getConsentNotificationEmailVerified());

        // logically delete a study by admin
        studiesApi.deleteStudy(studyId, false).execute();
        Study retStudy = studiesApi.getStudy(studyId).execute().body();
        assertNotNull(retStudy);

        // and try to update that deleted study
        retStudy.setSponsorName("renewed-sponsor-name");
        studiesApi.updateStudy(retStudy.getIdentifier(), retStudy).execute().body();
        Study retRenewedStudy = studiesApi.getStudy(retStudy.getIdentifier()).execute().body();
        assertEquals("renewed-sponsor-name", retRenewedStudy.getSponsorName());
        
        // Verify updates occur
        assertEquals("Updated subject", retRenewedStudy.getAccountExistsTemplate().getSubject());
        assertEquals("Updated ${url} body", retRenewedStudy.getAccountExistsTemplate().getBody());
        assertEquals(MimeType.TEXT_HTML, retRenewedStudy.getAccountExistsTemplate().getMimeType());
        
        studiesApi.deleteStudy(studyId, true).execute();
        try {
            studiesApi.getStudy(studyId).execute();
            fail("Should have thrown exception");
        } catch(EntityNotFoundException e) {
            // expected exception
        }
        studyId = null;
    }

    @Test
    public void researcherCannotAccessAnotherStudy() throws Exception {
        TestUser researcher = TestUserHelper.createAndSignInUser(StudyTest.class, false, Role.RESEARCHER);
        try {
            studyId = Tests.randomIdentifier(StudyTest.class);
            Study study = Tests.getStudy(studyId, null);

            StudiesApi adminStudiesApi = admin.getClient(StudiesApi.class);
            adminStudiesApi.createStudy(study).execute();

            try {
                StudiesApi resStudiesApi = researcher.getClient(StudiesApi.class);
                resStudiesApi.getStudy(studyId).execute();
                fail("Should not have been able to get this other study");
            } catch(UnauthorizedException e) {
                assertEquals("Unauthorized HTTP response code", 403, e.getStatusCode());
            }
        } finally {
            researcher.signOutAndDeleteUser();
        }
    }

    @Test(expected = UnauthorizedException.class)
    public void butNormalUserCannotAccessStudy() throws Exception {
        TestUser user = TestUserHelper.createAndSignInUser(StudyTest.class, false);
        try {
            StudiesApi studiesApi = user.getClient(StudiesApi.class);
            studiesApi.getUsersStudy().execute();
        } finally {
            user.signOutAndDeleteUser();
        }
    }

    @Test
    public void developerCannotChangeAdminOnlySettings() throws Exception {
        TestUser developer = TestUserHelper.createAndSignInUser(StudyTest.class, false, Role.DEVELOPER);
        try {
            StudiesApi studiesApi = developer.getClient(StudiesApi.class);

            Study study = studiesApi.getUsersStudy().execute().body();
            study.setHealthCodeExportEnabled(true);
            study.setEmailVerificationEnabled(false);
            study.setStudyIdExcludedInExport(false);
            study.setReauthenticationEnabled(true);
            studiesApi.updateUsersStudy(study).execute();

            study = studiesApi.getUsersStudy().execute().body();
            assertFalse("healthCodeExportEnabled should be false", study.getHealthCodeExportEnabled());
            assertTrue("emailVersificationEnabled should be true", study.getEmailVerificationEnabled());
            assertTrue("studyIdExcludedInExport should be true", study.getStudyIdExcludedInExport());
            assertFalse("reauthenticationEnabled should be false", study.getReauthenticationEnabled());
        } finally {
            developer.signOutAndDeleteUser();
        }
    }

    @Test
    public void resendVerifyConsentNotificationEmail() throws Exception {
        // We currently can't check an email address as part of a test. Just verify that the call succeeds.
        TestUser developer = TestUserHelper.createAndSignInUser(StudyTest.class, false, Role.DEVELOPER);
        try {
            StudiesApi studiesApi = developer.getClient(StudiesApi.class);
            Response<Message> response = studiesApi.resendVerifyEmail("consent_notification").execute();
            assertEquals(200, response.code());
        } finally {
            developer.signOutAndDeleteUser();
        }
    }

    @Test
    public void verifyConsentNotificationEmail() throws Exception {
        // We can't currently check an email address to get a real verification token. This test is mainly to make sure
        // that our Java SDK is set up correctly.
        StudiesApi studiesApi = admin.getClient(StudiesApi.class);
        try {
            studiesApi.verifyEmail(Tests.STUDY_ID, "dummy-token", "consent_notification").execute();
            fail("expected exception");
        } catch (BadRequestException ex) {
            assertTrue(ex.getMessage().contains("Email verification token has expired (or already been used)."));
        }
    }

    @Test
    public void uploadMetadataFieldDefinitions() throws Exception {
        StudiesApi adminApi = admin.getClient(StudiesApi.class);

        // Random field name, so they don't conflict.
        String fieldName = "test-field-" + RandomStringUtils.randomAlphabetic(4);
        UploadFieldDefinition originalField = new UploadFieldDefinition().name(fieldName).type(
                UploadFieldType.BOOLEAN);
        UploadFieldDefinition modifiedField = new UploadFieldDefinition().name(fieldName).type(
                UploadFieldType.INT);

        TestUser developer = TestUserHelper.createAndSignInUser(StudyTest.class, false, Role.DEVELOPER);
        try {
            StudiesApi studiesApi = developer.getClient(StudiesApi.class);
            Study study = studiesApi.getUsersStudy().execute().body();

            // Append the field to the study's metadata.
            appendToStudy(study, originalField);
            studiesApi.updateUsersStudy(study).execute();

            study = studiesApi.getUsersStudy().execute().body();
            UploadFieldDefinition returnedFieldDef = getFieldDefByName(fieldName, study);
            assertEqualFieldDefs(originalField, returnedFieldDef);

            // Non-admin can't modify the field.
            try {
                removeFieldDefByName(fieldName, study);
                appendToStudy(study, modifiedField);
                studiesApi.updateUsersStudy(study).execute();
                fail("expected exception");
            } catch (UnauthorizedException ex) {
                // Verify that the error message tells us about the field we can't modify.
                assertTrue(ex.getMessage().contains(fieldName));
            }
            study = studiesApi.getUsersStudy().execute().body();
            returnedFieldDef = getFieldDefByName(fieldName, study);
            assertEqualFieldDefs(originalField, returnedFieldDef);

            // Non-admin can't remove the field.
            try {
                removeFieldDefByName(fieldName, study);
                studiesApi.updateUsersStudy(study).execute();
                fail("expected exception");
            } catch (UnauthorizedException ex) {
                // Verify that the error message tells us about the field we can't modify.
                assertTrue(ex.getMessage().contains(fieldName));
            }
            study = studiesApi.getUsersStudy().execute().body();
            returnedFieldDef = getFieldDefByName(fieldName, study);
            assertEqualFieldDefs(originalField, returnedFieldDef);

            // Admin can modify field.
            removeFieldDefByName(fieldName, study);
            appendToStudy(study, modifiedField);
            adminApi.updateStudy(Tests.STUDY_ID, study).execute();

            study = studiesApi.getUsersStudy().execute().body();
            returnedFieldDef = getFieldDefByName(fieldName, study);
            assertEqualFieldDefs(modifiedField, returnedFieldDef);

            // Admin can delete field.
            removeFieldDefByName(fieldName, study);
            adminApi.updateStudy(Tests.STUDY_ID, study).execute();

            study = studiesApi.getUsersStudy().execute().body();
            returnedFieldDef = getFieldDefByName(fieldName, study);
            assertNull(returnedFieldDef);
        } finally {
            developer.signOutAndDeleteUser();
        }
    }

    // Helper method to append a field def to a study. Encapsulates null checks and creating the initial list.
    private static void appendToStudy(Study study, UploadFieldDefinition fieldDef) {
        if (study.getUploadMetadataFieldDefinitions() == null) {
            study.setUploadMetadataFieldDefinitions(new ArrayList<>());
        }
        study.addUploadMetadataFieldDefinitionsItem(fieldDef);
    }

    // Helper method to get a field def from a study by name. Returns null if not present.
    private static UploadFieldDefinition getFieldDefByName(String fieldName, Study study) {
        if (study.getUploadMetadataFieldDefinitions() == null) {
            return null;
        }

        for (UploadFieldDefinition oneFieldDef : study.getUploadMetadataFieldDefinitions()) {
            if (oneFieldDef.getName().equals(fieldName)) {
                return oneFieldDef;
            }
        }
        return null;
    }

    // Helper method to verify that the field is the same (name and type). Other defaults are set server side, and we
    // don't want to have to deal with that.
    private static void assertEqualFieldDefs(UploadFieldDefinition expected, UploadFieldDefinition actual) {
        assertEquals(expected.getName(), actual.getName());
        assertEquals(expected.getType(), actual.getType());
    }

    // Helper method to remove a field def from a study by name.
    private static void removeFieldDefByName(String fieldName, Study study) {
        Iterator<UploadFieldDefinition> fieldDefIter = study.getUploadMetadataFieldDefinitions().iterator();
        while (fieldDefIter.hasNext()) {
            UploadFieldDefinition oneFieldDef = fieldDefIter.next();
            if (oneFieldDef.getName().equals(fieldName)) {
                fieldDefIter.remove();
                break;
            }
        }
    }

    @Test
    public void adminCanGetAllStudies() throws Exception {
        StudiesApi studiesApi = admin.getClient(StudiesApi.class);

        StudyList studies = studiesApi.getStudies(null).execute().body();
        assertTrue("Should be more than zero studies", studies.getItems().size() > 0);
    }

    @Test
    public void userCannotAccessApisWithDeprecatedClient() throws Exception {
        StudiesApi studiesApi = admin.getClient(StudiesApi.class);
        Study study = studiesApi.getStudy(Tests.STUDY_ID).execute().body();
        // Set a minimum value that should not any other tests
        if (study.getMinSupportedAppVersions().get("Android") == null) {
            study.getMinSupportedAppVersions().put("Android", 1);
            studiesApi.updateStudy(Tests.STUDY_ID, study).execute();
        }
        TestUser user = TestUserHelper.createAndSignInUser(StudyTest.class, true);
        try {

            // This is a version zero client, note how little information we need to provide
            // for this test to pass.
            ClientInfo clientInfo = new ClientInfo();
            clientInfo.appName(Tests.APP_NAME);
            clientInfo.setOsName("Android");
            clientInfo.setAppVersion(0);

            ClientManager manager = new ClientManager.Builder()
                    .withSignIn(user.getSignIn())
                    .withClientInfo(clientInfo)
                    .build();

            ForConsentedUsersApi usersApi = manager.getClient(ForConsentedUsersApi.class);

            usersApi.getScheduledActivities("+00:00", 3, null).execute();
            fail("Should have thrown exception");

        } catch(UnsupportedVersionException e) {
            // This is good.
        } finally {
            user.signOutAndDeleteUser();
        }
    }

    @Test
    public void getStudyUploads() throws Exception {
        TestUser developer = TestUserHelper.createAndSignInUser(StudyTest.class, false, Role.DEVELOPER);
        TestUser user = TestUserHelper.createAndSignInUser(ParticipantsTest.class, true);
        TestUser user2 = TestUserHelper.createAndSignInUser(ParticipantsTest.class, true);
        try {
            UploadsApi devUploadsApi = developer.getClient(UploadsApi.class);
            DateTime startTime = DateTime.now(DateTimeZone.UTC).minusHours(2);
            DateTime endTime = startTime.plusHours(4);

            int count = devUploadsApi.getUploads(startTime, endTime, MAX_PAGE_SIZE, null).execute().body().getItems().size();

            // Create a REQUESTED record that we can retrieve through the reporting API.
            UploadRequest request = new UploadRequest();
            request.setName("upload.zip");
            request.setContentType("application/zip");
            request.setContentLength(100L);
            request.setContentMd5("ABC");

            ForConsentedUsersApi usersApi = user.getClient(ForConsentedUsersApi.class);
            UploadSession uploadSession = usersApi.requestUploadSession(request).execute().body();

            UploadSession uploadSession2 = usersApi.requestUploadSession(request).execute().body();

            Thread.sleep(1000); // This does depend on a GSI, so pause for a bit.

            // This should retrieve both of the user's uploads.
            // NOTE: This assumes that there aren't more than a few dozen uploads in the API study in the last few
            // hours.
            StudiesApi studiesApi = developer.getClient(StudiesApi.class);

            UploadList results = studiesApi.getUploads(startTime, endTime, MAX_PAGE_SIZE, null).execute().body();
          
            assertEquals(startTime, results.getRequestParams().getStartTime());
            assertEquals(endTime, results.getRequestParams().getEndTime());

            assertEquals(count+2, results.getItems().size());
            assertNotNull(getUpload(results, uploadSession.getId()));
            assertNotNull(getUpload(results, uploadSession2.getId()));

            // then test pagination by setting max pagesize to 1
            // There are at least 2 uploads, so we know there are at least 2 pages.
            UploadList pagedResults = studiesApi.getUploads(startTime, endTime, 1, null).execute().body();
            assertEquals(startTime, pagedResults.getRequestParams().getStartTime());
            assertEquals(endTime, pagedResults.getRequestParams().getEndTime());

            assertEquals(1, pagedResults.getItems().size());
            assertEquals(1, pagedResults.getRequestParams().getPageSize().intValue());
            assertNotNull(pagedResults.getNextPageOffsetKey());

            // then getupload again with offsetkey from session1
            UploadList secondPagedResults = studiesApi.getUploads(startTime, endTime, 1, pagedResults.getNextPageOffsetKey()).execute().body();
            assertEquals(startTime, secondPagedResults.getRequestParams().getStartTime());
            assertEquals(endTime, secondPagedResults.getRequestParams().getEndTime());

            assertEquals(1, secondPagedResults.getItems().size());
            assertEquals(1, secondPagedResults.getRequestParams().getPageSize().intValue());

            // then check if will set default page size if not given by user
            UploadList nullPageSizeResults = studiesApi.getUploads(startTime, endTime, null, null).execute().body();
            assertEquals(startTime, nullPageSizeResults.getRequestParams().getStartTime());
            assertEquals(endTime, nullPageSizeResults.getRequestParams().getEndTime());
            assertNotNull(nullPageSizeResults.getRequestParams().getPageSize());
            assertEquals(count+2, nullPageSizeResults.getItems().size());
            assertNotNull(getUpload(nullPageSizeResults, uploadSession.getId()));
            assertNotNull(getUpload(nullPageSizeResults, uploadSession2.getId()));
        } finally {
            if (user != null) {
                user.signOutAndDeleteUser();
            }
            if (user2 != null) {
                user2.signOutAndDeleteUser();
            }
            if (developer != null) {
                developer.signOutAndDeleteUser();
            }
        }
    }

    private Upload getUpload(UploadList results, String guid) {
        for (Upload upload : results.getItems()) {
            if (upload.getUploadId().equals(guid)) {
                return upload;
            }
        }
        return null;
    }

    private void alterStudy(Study study) {
        study.setAutoVerificationEmailSuppressed(false);
        study.setName("Altered Test Study [SDK]");
        study.setStrictUploadValidationEnabled(false);
        study.setSupportEmail("test3@test.com");
        study.setUploadValidationStrictness(UploadValidationStrictness.WARNING);
        study.setConsentNotificationEmail("bridge-testing+test4@sagebase.org");
        
        OAuthProvider provider = study.getOAuthProviders().get("myProvider");
        provider.endpoint("endpoint2");
        provider.callbackUrl("callbackUrl2");

        study.setAppleAppLinks(null);
        study.setAndroidAppLinks(null);
        
        EmailTemplate template = new EmailTemplate().subject("subject").body("body ${token}").mimeType(MimeType.TEXT_HTML);
        study.setEmailSignInTemplate(template);
    }

}
