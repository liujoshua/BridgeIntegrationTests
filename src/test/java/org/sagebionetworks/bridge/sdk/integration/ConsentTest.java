package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.joda.time.LocalDate;
import org.joda.time.format.ISODateTimeFormat;
import org.sagebionetworks.bridge.sdk.integration.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.sdk.rest.RestUtils;
import org.sagebionetworks.bridge.sdk.rest.api.AuthenticationApi;
import org.sagebionetworks.bridge.sdk.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.sdk.rest.api.ParticipantsApi;
import org.sagebionetworks.bridge.sdk.rest.exceptions.ConsentRequiredException;
import org.sagebionetworks.bridge.sdk.rest.exceptions.EntityAlreadyExistsException;
import org.sagebionetworks.bridge.sdk.rest.exceptions.InvalidEntityException;
import org.sagebionetworks.bridge.sdk.rest.model.ConsentSignature;
import org.sagebionetworks.bridge.sdk.rest.model.EmptyPayload;
import org.sagebionetworks.bridge.sdk.rest.model.Message;
import org.sagebionetworks.bridge.sdk.rest.model.Role;
import org.sagebionetworks.bridge.sdk.rest.model.SharingScope;
import org.sagebionetworks.bridge.sdk.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.sdk.rest.model.UserSessionInfo;
import org.sagebionetworks.bridge.sdk.rest.model.Withdrawal;

import java.util.Map;

@Category(IntegrationSmokeTest.class)
@SuppressWarnings("unchecked")
public class ConsentTest {
    private static final String FAKE_IMAGE_DATA = "VGVzdCBzdHJpbmc=";

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
            
            participant = userApi.getUsersParticipantRecord().execute().body();
            assertEquals(SharingScope.SPONSORS_AND_PARTNERS, participant.getSharingScope());

            // Do the same thing in reverse, setting to no sharing
            participant = new StudyParticipant();
            participant.sharingScope(SharingScope.NO_SHARING);

            userApi.updateUsersParticipantRecord(participant).execute();

            participant = userApi.getUsersParticipantRecord().execute().body();
            assertEquals(SharingScope.NO_SHARING, participant.getSharingScope());

            AuthenticationApi authApi = testUser.getClient(AuthenticationApi.class);
            authApi.signOut(new EmptyPayload()).execute();
        } finally {
            testUser.signOutAndDeleteUser();
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
            assertFalse("User has not consented", user.getSession().getConsented());
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
            
            assertTrue("User has consented", session.getConsented());
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
        ObjectMapper jsonObjectMapper = RestUtils.MAPPER;

        // de-serialize and validate
        ConsentSignature sig = jsonObjectMapper.readValue(sigJson, ConsentSignature.class);
        
        assertEquals("(ConsentSignature instance) name matches", "Jason McSerializer", sig.getName());
        assertEquals("(ConsentSignature instance) birthdate matches", "1985-12-31",
                sig.getBirthdate().toString(ISODateTimeFormat.date()));
        assertEquals("(ConsentSignature instance) imageData matches", FAKE_IMAGE_DATA, sig.getImageData());
        assertEquals("(ConsentSignature instance) imageMimeType matches", "image/fake", sig.getImageMimeType());

        // re-serialize, then parse as a raw map to validate the JSON
        String reserializedJson = jsonObjectMapper.writeValueAsString(sig);
        Map<String, String> jsonAsMap = jsonObjectMapper.readValue(reserializedJson, Map.class);
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
        
        ConsentSignature sig = new ConsentSignature().name(name).birthdate(birthdate)
                .imageData(imageData) .imageMimeType(imageMimeType);
        sig.setScope(SharingScope.ALL_QUALIFIED_RESEARCHERS);
        try {
            ForConsentedUsersApi userApi = testUser.getClient(ForConsentedUsersApi.class);

            assertFalse("User has not consented", testUser.getSession().getConsented());
            assertFalse(RestUtils.isUserConsented(testUser.getSession()));

            // get consent should fail if the user hasn't given consent
            try {
                userApi.getConsentSignature(testUser.getDefaultSubpopulation()).execute();
                fail("ConsentRequiredException not thrown");
            } catch (ConsentRequiredException ex) {
                // expected
            }

            // give consent
            userApi.createConsentSignature(testUser.getDefaultSubpopulation(), sig).execute();
            
            // Participant record shows sharing scope has been set
            StudyParticipant participant = userApi.getUsersParticipantRecord().execute().body();
            assertEquals(SharingScope.ALL_QUALIFIED_RESEARCHERS, participant.getSharingScope());
            
            // Session now shows consent...
            UserSessionInfo session = testUser.signInAgain();
            assertTrue(RestUtils.isUserConsented(session));
            
            // get consent and validate that it's the same consent
            ConsentSignature sigFromServer = userApi.getConsentSignature(testUser.getDefaultSubpopulation()).execute().body();
            
            assertEquals("name matches", name, sigFromServer.getName());
            assertEquals("birthdate matches", birthdate, sigFromServer.getBirthdate());

            assertEquals("imageData matches", imageData, sigFromServer.getImageData());
            assertEquals("imageMimeType matches", imageMimeType, sigFromServer.getImageMimeType());
            
            // giving consent again will throw
            try {
                // See BRIDGE-1568
                sig = new ConsentSignature().name(sig.getName()).birthdate(sig.getBirthdate())
                        .scope(SharingScope.ALL_QUALIFIED_RESEARCHERS).imageData(sig.getImageData())
                        .imageMimeType(sig.getImageMimeType());
                Message message = userApi.createConsentSignature(testUser.getDefaultSubpopulation(), sig).execute().body();
                fail("EntityAlreadyExistsException not thrown: " + message.getMessage());
            } catch (EntityAlreadyExistsException ex) {
                // expected
            }
            
            // The remote session should also reflect the sharing scope
            AuthenticationApi authApi = testUser.getClient(AuthenticationApi.class);
            authApi.signOut(new EmptyPayload()).execute();
            
            session = testUser.signInAgain();
            assertEquals(SharingScope.ALL_QUALIFIED_RESEARCHERS, session.getSharingScope());
            assertTrue(RestUtils.isUserConsented(session));

            // withdraw consent
            Withdrawal withdrawal = new Withdrawal().reason("Withdrawing test user from study");
            userApi = testUser.getClient(ForConsentedUsersApi.class);
            userApi.withdrawConsentFromSubpopulation(testUser.getDefaultSubpopulation(), withdrawal).execute();
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
    public void canEmailConsentAgreement() throws Exception {
        TestUser testUser = TestUserHelper.createAndSignInUser(ConsentTest.class, true);
        try {
            ForConsentedUsersApi userApi = testUser.getClient(ForConsentedUsersApi.class);
            userApi.emailConsentAgreement(testUser.getDefaultSubpopulation(), new EmptyPayload()).execute();
        } finally {
            testUser.signOutAndDeleteUser();
        }
    }
    
    @Test
    public void canWithdrawFromAllConsentsInStudy() throws Exception {
        TestUser researchUser = TestUserHelper.createAndSignInUser(ConsentTest.class, true, Role.RESEARCHER);
        TestUser testUser = TestUserHelper.createAndSignInUser(ConsentTest.class, true);
        try {
            UserSessionInfo session = testUser.getSession();

            // Can get activities without an error... user is indeed consented.
            ForConsentedUsersApi userApi = testUser.getClient(ForConsentedUsersApi.class);
            userApi.getScheduledActivities("+00:00", 1, null).execute();
            
            assertTrue(RestUtils.isUserConsented(session));
            
            ParticipantsApi participantsApi = researchUser.getClient(ParticipantsApi.class);
            Withdrawal withdrawal = new Withdrawal().reason("I'm just a test user.");
            
            participantsApi.withdrawParticipantFromStudy(session.getId(), withdrawal).execute();

            testUser.signOut();
            try {
                testUser.signInAgain();
            } catch(ConsentRequiredException e) {
                assertEquals(SharingScope.NO_SHARING, session.getSharingScope());
                assertFalse(RestUtils.isUserConsented(e.getSession()));
            }
        } finally {
            try {
                testUser.signOutAndDeleteUser();    
            } finally {
                researchUser.signOutAndDeleteUser();    
            }
        }
    }
}
