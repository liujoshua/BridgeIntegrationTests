package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.joda.time.DateTime;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.joda.time.LocalDate;
import org.joda.time.format.ISODateTimeFormat;
import retrofit2.Response;

import org.sagebionetworks.bridge.rest.RestUtils;
import org.sagebionetworks.bridge.rest.api.AuthenticationApi;
import org.sagebionetworks.bridge.rest.api.ConsentsApi;
import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.rest.api.ForResearchersApi;
import org.sagebionetworks.bridge.rest.api.InternalApi;
import org.sagebionetworks.bridge.rest.api.ParticipantsApi;
import org.sagebionetworks.bridge.rest.api.SubpopulationsApi;
import org.sagebionetworks.bridge.rest.exceptions.ConsentRequiredException;
import org.sagebionetworks.bridge.rest.exceptions.EntityAlreadyExistsException;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.exceptions.InvalidEntityException;
import org.sagebionetworks.bridge.rest.model.ConsentSignature;
import org.sagebionetworks.bridge.rest.model.ConsentStatus;
import org.sagebionetworks.bridge.rest.model.GuidVersionHolder;
import org.sagebionetworks.bridge.rest.model.HealthDataRecord;
import org.sagebionetworks.bridge.rest.model.Message;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.SharingScope;
import org.sagebionetworks.bridge.rest.model.SignUp;
import org.sagebionetworks.bridge.rest.model.SmsMessage;
import org.sagebionetworks.bridge.rest.model.SmsType;
import org.sagebionetworks.bridge.rest.model.Study;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.rest.model.Subpopulation;
import org.sagebionetworks.bridge.rest.model.UserConsentHistory;
import org.sagebionetworks.bridge.rest.model.UserSessionInfo;
import org.sagebionetworks.bridge.rest.model.Withdrawal;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.util.IntegTestUtils;

import java.util.List;
import java.util.Map;

@Category(IntegrationSmokeTest.class)
@SuppressWarnings("unchecked")
public class ConsentTest {
    private static final String FAKE_IMAGE_DATA = "VGVzdCBzdHJpbmc=";

    private static TestUser adminUser;
    private static TestUser phoneOnlyTestUser;
    private static TestUser researchUser;

    @BeforeClass
    public static void before() throws Exception {
        // Get admin API.
        adminUser = TestUserHelper.getSignedInAdmin();

        // Make researcher.
        researchUser = TestUserHelper.createAndSignInUser(ConsentTest.class, true, Role.RESEARCHER);

        // Make phone user.
        IntegTestUtils.deletePhoneUser(researchUser);
        SignUp phoneOnlyUser = new SignUp().study(IntegTestUtils.STUDY_ID).consent(true).phone(IntegTestUtils.PHONE);
        phoneOnlyTestUser = new TestUserHelper.Builder(AuthenticationTest.class).withConsentUser(true)
                .withSignUp(phoneOnlyUser).createAndSignInUser();

        // Verify necessary flags (health code export) are enabled
        ForAdminsApi adminApi = adminUser.getClient(ForAdminsApi.class);
        Study study = adminApi.getUsersStudy().execute().body();
        study.setHealthCodeExportEnabled(true);
        adminApi.updateStudy(study.getIdentifier(), study).execute();
    }

    @AfterClass
    public static void deleteResearcher() throws Exception {
        if (researchUser != null) {
            researchUser.signOutAndDeleteUser();
        }
    }

    @AfterClass
    public static void deletePhoneUser() throws Exception {
        if (phoneOnlyTestUser != null) {
            phoneOnlyTestUser.signOutAndDeleteUser();
        }
    }

    @Test
    public void canToggleDataSharing() throws Exception {
        TestUser testUser = TestUserHelper.createAndSignInUser(ConsentTest.class, true);
        ForConsentedUsersApi userApi = testUser.getClient(ForConsentedUsersApi.class);
        try {
            // starts out with no sharing
            UserSessionInfo session = testUser.getSession();
            assertEquals(SharingScope.NO_SHARING, session.getSharingScope());

            // Change, verify in-memory session changed, verify after signing in again that server state has changed
            StudyParticipant participant = new StudyParticipant();

            participant.sharingScope(SharingScope.SPONSORS_AND_PARTNERS);
            userApi.updateUsersParticipantRecord(participant).execute();
            
            participant = userApi.getUsersParticipantRecord(false).execute().body();
            assertEquals(SharingScope.SPONSORS_AND_PARTNERS, participant.getSharingScope());

            // Do the same thing in reverse, setting to no sharing
            participant = new StudyParticipant();
            participant.sharingScope(SharingScope.NO_SHARING);

            userApi.updateUsersParticipantRecord(participant).execute();

            participant = userApi.getUsersParticipantRecord(true).execute().body();
            assertEquals(SharingScope.NO_SHARING, participant.getSharingScope());
            
            Map<String,List<UserConsentHistory>> map = participant.getConsentHistories();
            UserConsentHistory history = map.get(IntegTestUtils.STUDY_ID).get(0);
            
            assertEquals(IntegTestUtils.STUDY_ID, history.getSubpopulationGuid());
            assertNotNull(history.getConsentCreatedOn());
            assertNotNull(history.getName());
            assertNotNull(history.getBirthdate());
            assertTrue(history.getSignedOn().isAfter(DateTime.now().minusHours(1)));
            assertTrue(history.isHasSignedActiveConsent());
            
            AuthenticationApi authApi = testUser.getClient(AuthenticationApi.class);
            authApi.signOut().execute();
        } finally {
            testUser.signOutAndDeleteUser();
        }
    }
    
    // BRIDGE-1594
    @Test
    public void giveConsentAndWithdrawTwice() throws Exception {
        TestUser developer = TestUserHelper.createAndSignInUser(ConsentTest.class, true, Role.DEVELOPER);
        TestUser user = TestUserHelper.createAndSignInUser(ConsentTest.class, false);
        SubpopulationsApi subpopsApi = developer.getClientManager().getClient(SubpopulationsApi.class);
        GuidVersionHolder keys = null;
        try {
            
            Subpopulation subpop = new Subpopulation();
            subpop.setName("Optional additional consent");
            subpop.setRequired(false);
            keys = subpopsApi.createSubpopulation(subpop).execute().body();
            
            ConsentSignature signature = new ConsentSignature();
            signature.setName("Test User");
            signature.setScope(SharingScope.NO_SHARING);
            signature.setBirthdate(LocalDate.parse("1970-04-04"));
            
            Withdrawal withdrawal = new Withdrawal();
            withdrawal.setReason("A reason.");
            
            // Now, this user will consent to both consents, then withdraw from the required consent, 
            // then withdraw from the optional consent, and this should work where it didn't before.
            ForConsentedUsersApi usersApi = user.getClientManager().getClient(ForConsentedUsersApi.class);
            
            usersApi.createConsentSignature(user.getStudyId(), signature).execute();
            usersApi.createConsentSignature(keys.getGuid(), signature).execute();
            
            // Withdrawing the optional consent first, you should then be able to get the second consent
            usersApi.withdrawConsentFromSubpopulation(keys.getGuid(), withdrawal).execute();
            
            user.signOut();
            user.signInAgain();
            
            usersApi.withdrawConsentFromSubpopulation(user.getStudyId(), withdrawal).execute();
            
            user.signOut();
            try {
                user.signInAgain();
                fail("Should have thrown an exception.");
            } catch(ConsentRequiredException e) {
                UserSessionInfo session = e.getSession();
                for (ConsentStatus status : session.getConsentStatuses().values()) {
                    assertFalse(status.isConsented());
                }
                assertFalse(RestUtils.isUserConsented(session));
            }
        } finally {
            adminUser.getClient(SubpopulationsApi.class).deleteSubpopulation(keys.getGuid(), true).execute();
            user.signOutAndDeleteUser();
            developer.signOutAndDeleteUser();
        }
    }

    @Test
    public void giveAndGetConsent() throws Exception {
        giveAndGetConsentHelper("Eggplant McTester", new LocalDate(1970, 1, 1), null, null);
    }

    @Test
    public void giveAndGetConsentWithSignatureImage() throws Exception {
        giveAndGetConsentHelper("Eggplant McTester", new LocalDate(1970, 1, 1), FAKE_IMAGE_DATA, "image/fake");
    }

    @Test
    public void signedInUserMustGiveConsent() throws Exception {
        TestUser user = TestUserHelper.createAndSignInUser(ConsentTest.class, false);
        try {
            ForConsentedUsersApi userApi = user.getClient(ForConsentedUsersApi.class);
            assertFalse("User has not consented", user.getSession().isConsented());
            try {
                userApi.getSchedules().execute();
                fail("Should have required consent.");
            } catch(ConsentRequiredException e) {
                assertEquals("Exception is a 412 Precondition Failed", 412, e.getStatusCode());
            }
            
            LocalDate date = new LocalDate(1970, 10, 10);
            ConsentSignature signature = new ConsentSignature().name(user.getEmail())
                    .birthdate(date).scope(SharingScope.SPONSORS_AND_PARTNERS);
            userApi.createConsentSignature(user.getDefaultSubpopulation(), signature).execute();
            
            UserSessionInfo session = user.signInAgain();
            
            assertTrue("User has consented", session.isConsented());
            // This should succeed
            userApi.getSchedules().execute();
        } finally {
            user.signOutAndDeleteUser();
        }
    }

    @Test(expected=InvalidEntityException.class)
    public void userMustMeetMinAgeRequirements() throws Exception {
        TestUser user = null;
        try {
            user = TestUserHelper.createAndSignInUser(ConsentTest.class, false);
        } catch(ConsentRequiredException e) {
            // this is expected when you sign in.
        }
        try {
            ForConsentedUsersApi userApi = user.getClient(ForConsentedUsersApi.class);
            try {
                userApi.getSchedules();
            } catch(ConsentRequiredException e) {
                // this is what we're expecting now
            }
            LocalDate date = LocalDate.now();
            ConsentSignature signature = new ConsentSignature().name(user.getEmail())
                    .birthdate(date).scope(SharingScope.SPONSORS_AND_PARTNERS);
            userApi.createConsentSignature(user.getDefaultSubpopulation(), signature).execute();
        } finally {
            user.signOutAndDeleteUser();
        }
    }

    @Test
    public void jsonSerialization() throws Exception {
        // setup
        String sigJson = "{\n" +
                "   \"name\":\"Jason McSerializer\",\n" +
                "   \"birthdate\":\"1985-12-31\",\n" +
                "   \"imageData\":\"" + FAKE_IMAGE_DATA + "\",\n" +
                "   \"imageMimeType\":\"image/fake\"\n" +
                "}";

        // de-serialize and validate
        ConsentSignature sig = RestUtils.GSON.fromJson(sigJson, ConsentSignature.class);
        
        assertEquals("(ConsentSignature instance) name matches", "Jason McSerializer", sig.getName());
        assertEquals("(ConsentSignature instance) birthdate matches", "1985-12-31",
                sig.getBirthdate().toString(ISODateTimeFormat.date()));
        assertEquals("(ConsentSignature instance) imageData matches", FAKE_IMAGE_DATA, sig.getImageData());
        assertEquals("(ConsentSignature instance) imageMimeType matches", "image/fake", sig.getImageMimeType());

        // re-serialize, then parse as a raw map to validate the JSON
        String reserializedJson = RestUtils.GSON.toJson(sig);
        Map<String, String> jsonAsMap = RestUtils.GSON.fromJson(reserializedJson, Map.class);
        assertEquals("JSON map has exactly 4 elements", 4, jsonAsMap.size());
        assertEquals("(JSON map) name matches", "Jason McSerializer", jsonAsMap.get("name"));
        assertEquals("(JSON map) birthdate matches", "1985-12-31", jsonAsMap.get("birthdate"));
        assertEquals("(JSON map) imageData matches", FAKE_IMAGE_DATA, jsonAsMap.get("imageData"));
        assertEquals("(JSON map) imageMimeType matches", "image/fake", jsonAsMap.get("imageMimeType"));
    }

    // helper method to test consent with and without images
    private static void giveAndGetConsentHelper(String name, LocalDate birthdate, String imageData,
            String imageMimeType) throws Exception {
        TestUser testUser = TestUserHelper.createAndSignInUser(ConsentTest.class, false);
        
        ConsentSignature sig = new ConsentSignature().name(name).birthdate(birthdate).imageData(imageData)
                .imageMimeType(imageMimeType).scope(SharingScope.ALL_QUALIFIED_RESEARCHERS);
        try {
            ForConsentedUsersApi userApi = testUser.getClient(ForConsentedUsersApi.class);
            
            assertFalse("User has not consented", testUser.getSession().isConsented());
            assertFalse(RestUtils.isUserConsented(testUser.getSession()));

            // get consent should fail if the user hasn't given consent
            try {
                userApi.getConsentSignature(testUser.getDefaultSubpopulation()).execute();
                fail("ConsentRequiredException not thrown");
            } catch (ConsentRequiredException ex) {
                // expected
            }

            // give consent
            UserSessionInfo session = userApi.createConsentSignature(testUser.getDefaultSubpopulation(), sig).execute().body();
            
            // Session should be updated to reflect this consent.
            ConsentStatus status = session.getConsentStatuses().get(testUser.getDefaultSubpopulation());
            assertTrue(status.isConsented());
            assertTrue(status.isSignedMostRecentConsent());
            
            // Participant record includes the sharing scope that was set
            StudyParticipant participant = userApi.getUsersParticipantRecord(false).execute().body();
            assertEquals(SharingScope.ALL_QUALIFIED_RESEARCHERS, participant.getSharingScope());
            
            // Session now shows consent...
            session = testUser.signInAgain();
            assertTrue(RestUtils.isUserConsented(session));
            
            // get consent and validate that it's the same consent
            ConsentSignature sigFromServer = userApi.getConsentSignature(testUser.getDefaultSubpopulation()).execute().body();
            assertEquals("name matches", name, sigFromServer.getName());
            assertEquals("birthdate matches", birthdate, sigFromServer.getBirthdate());
            assertEquals("imageData matches", imageData, sigFromServer.getImageData());
            assertEquals("imageMimeType matches", imageMimeType, sigFromServer.getImageMimeType());
            assertNotNull(sigFromServer.getSignedOn());
            
            // giving consent again will throw
            try {
                // See BRIDGE-1568
                sig = new ConsentSignature().name(sig.getName()).birthdate(sig.getBirthdate())
                        .scope(SharingScope.ALL_QUALIFIED_RESEARCHERS).imageData(sig.getImageData())
                        .imageMimeType(sig.getImageMimeType());
                userApi.createConsentSignature(testUser.getDefaultSubpopulation(), sig).execute();
                fail("EntityAlreadyExistsException not thrown");
            } catch (EntityAlreadyExistsException ex) {
                // expected
            }
            
            // The remote session should also reflect the sharing scope
            AuthenticationApi authApi = testUser.getClient(AuthenticationApi.class);
            authApi.signOut().execute();
            
            session = testUser.signInAgain();
            assertEquals(SharingScope.ALL_QUALIFIED_RESEARCHERS, session.getSharingScope());
            assertTrue(RestUtils.isUserConsented(session));

            // withdraw consent
            Withdrawal withdrawal = new Withdrawal().reason("Withdrawing test user from study");
            userApi = testUser.getClient(ForConsentedUsersApi.class);
            session = userApi.withdrawConsentFromSubpopulation(testUser.getDefaultSubpopulation(), withdrawal).execute()
                    .body();
            
            // Session should reflect the withdrawal of consent
            status = session.getConsentStatuses().get(testUser.getDefaultSubpopulation());
            assertFalse(status.isConsented());
            assertFalse(status.isSignedMostRecentConsent());
            assertNull(status.getSignedOn());
            
            // Get the consent signature and verify it is withdrawn. You can't get it as the test 
            // user... the user is withdrawn! 
            ParticipantsApi participantsApi = researchUser.getClient(ParticipantsApi.class);
            StudyParticipant retrieved = participantsApi.getParticipantById(testUser.getUserId(), true).execute().body();
            
            List<UserConsentHistory> history = retrieved.getConsentHistories().get(testUser.getDefaultSubpopulation());
            assertTrue( history.get(0).getWithdrewOn().isAfter(DateTime.now().minusMinutes(2)) );
            
            // This method should now (immediately) throw a ConsentRequiredException
            try {
                userApi.getSchedules().execute();
                fail("Should have thrown exception");
            } catch(ConsentRequiredException e) {
                // what we want
            }
        } finally {
            testUser.signOutAndDeleteUser();
        }
    }
    
    @Test
    public void canResendConsentAgreement() throws Exception {
        TestUser testUser = TestUserHelper.createAndSignInUser(ConsentTest.class, true);
        try {
            ForConsentedUsersApi userApi = testUser.getClient(ForConsentedUsersApi.class);
            userApi.resendConsentAgreement(testUser.getDefaultSubpopulation()).execute();
        } finally {
            testUser.signOutAndDeleteUser();
        }
    }

    @Test
    public void canResendConsentAgreementForPhone() throws Exception {
        // Request phone consent.
        Response<Message> response = phoneOnlyTestUser.getClient(ForConsentedUsersApi.class)
                .resendConsentAgreement(phoneOnlyTestUser.getDefaultSubpopulation()).execute();
        assertEquals(200, response.code());

        // Verify message logs contains the expected message.
        SmsMessage message = adminUser.getClient(InternalApi.class).getMostRecentSmsMessage(phoneOnlyTestUser
                .getUserId()).execute().body();
        assertEquals(phoneOnlyTestUser.getPhone().getNumber(), message.getPhoneNumber());
        assertNotNull(message.getMessageId());
        assertEquals(SmsType.TRANSACTIONAL, message.getSmsType());
        assertEquals(phoneOnlyTestUser.getStudyId(), message.getStudyId());

        // Message body isn't constrained by the test, so just check that it exists.
        assertNotNull(message.getMessageBody());

        // Clock skew on Jenkins can be known to go as high as 10 minutes. For a robust test, simply check that the
        // message was sent within the last hour.
        assertTrue(message.getSentOn().isAfter(DateTime.now().minusHours(1)));

        // Verify the health code matches.
        StudyParticipant participant = researchUser.getClient(ForResearchersApi.class).getParticipantById(
                phoneOnlyTestUser.getUserId(), false).execute().body();
        assertEquals(participant.getHealthCode(), message.getHealthCode());

        // Verify the SMS message log was written to health data.
        Thread.sleep(2000);
        DateTime messageSentOn = message.getSentOn();
        List<HealthDataRecord> recordList = phoneOnlyTestUser.getClient(InternalApi.class).getHealthDataByCreatedOn(
                messageSentOn, messageSentOn).execute().body().getItems();
        HealthDataRecord smsMessageRecord = recordList.stream()
                .filter(r -> r.getSchemaId().equals("sms-messages-sent-from-bridge")).findAny().get();
        assertEquals("sms-messages-sent-from-bridge", smsMessageRecord.getSchemaId());
        assertEquals(1, smsMessageRecord.getSchemaRevision().intValue());

        // SMS Message log saves the date as epoch milliseconds.
        assertEquals(messageSentOn.getMillis(), smsMessageRecord.getCreatedOn().getMillis());

        // Verify data.
        Map<String, String> recordDataMap = RestUtils.toType(smsMessageRecord.getData(), Map.class);
        assertEquals("Transactional", recordDataMap.get("smsType"));
        assertNotNull(recordDataMap.get("messageBody"));
        assertEquals(messageSentOn.getMillis(), DateTime.parse(recordDataMap.get("sentOn")).getMillis());
    }

    @Test
    public void canWithdrawFromStudy() throws Exception {
        TestUser testUser = TestUserHelper.createAndSignInUser(ConsentTest.class, true);
        try {
            UserSessionInfo session = testUser.getSession();

            // Can get activities without an error... user is indeed consented.
            ForConsentedUsersApi userApi = testUser.getClient(ForConsentedUsersApi.class);
            userApi.getScheduledActivities("+00:00", 1, null).execute();
            
            assertTrue(RestUtils.isUserConsented(session));
            
            Withdrawal withdrawal = new Withdrawal().reason("I'm just a test user.");
            testUser.getClient(ForConsentedUsersApi.class).withdrawFromStudy(withdrawal).execute();

            try {
                testUser.signInAgain();
                fail("Should have thrown exception");
            } catch(EntityNotFoundException e) {
            }
        } finally {
            testUser.signOutAndDeleteUser();
        }
    }
    
    @Test
    public void canWithdrawParticipantFromStudy() throws Exception {
        TestUser testUser = TestUserHelper.createAndSignInUser(ConsentTest.class, true);
        String userId = testUser.getSession().getId();
        try {
            ParticipantsApi participantsApi = researchUser.getClient(ParticipantsApi.class);
            
            Withdrawal withdrawal = new Withdrawal().reason("Reason for withdrawal.");
            Message message = participantsApi.withdrawParticipantFromStudy(userId, withdrawal).execute().body();
            assertEquals("User has been withdrawn from the study.", message.getMessage());
            
            // Retrieve the account and verify it has been processed correctly.
            StudyParticipant theUser = participantsApi.getParticipantById(userId, true).execute().body();
            assertEquals(SharingScope.NO_SHARING, theUser.getSharingScope());
            assertFalse(theUser.isNotifyByEmail());
            assertNull(theUser.getEmail());
            assertFalse(theUser.isEmailVerified());
            assertNull(theUser.getPhone());
            assertFalse(theUser.isPhoneVerified());
            assertNull(theUser.getExternalId());
            for (List<UserConsentHistory> histories : theUser.getConsentHistories().values()) {
                for (UserConsentHistory oneHistory : histories) {
                    assertNotNull(oneHistory.getWithdrewOn());
                }
            }
        } finally {
            testUser.signOutAndDeleteUser();
        }
    }
}
