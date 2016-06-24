package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Map;

import org.joda.time.DateTimeZone;
import org.joda.time.LocalDate;
import org.joda.time.format.ISODateTimeFormat;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.sagebionetworks.bridge.sdk.ClientProvider;
import org.sagebionetworks.bridge.sdk.Session;
import org.sagebionetworks.bridge.sdk.UserClient;
import org.sagebionetworks.bridge.sdk.exceptions.ConsentRequiredException;
import org.sagebionetworks.bridge.sdk.exceptions.EntityAlreadyExistsException;
import org.sagebionetworks.bridge.sdk.exceptions.InvalidEntityException;
import org.sagebionetworks.bridge.sdk.integration.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.sdk.models.accounts.ConsentSignature;
import org.sagebionetworks.bridge.sdk.models.accounts.SharingScope;
import org.sagebionetworks.bridge.sdk.models.accounts.SignInCredentials;
import org.sagebionetworks.bridge.sdk.models.accounts.StudyParticipant;
import org.sagebionetworks.bridge.sdk.models.subpopulations.ConsentStatus;
import org.sagebionetworks.bridge.sdk.utils.Utilities;

import com.fasterxml.jackson.databind.ObjectMapper;

@Category(IntegrationSmokeTest.class)
@SuppressWarnings("unchecked")
public class ConsentTest {
    private static final String FAKE_IMAGE_DATA = "VGhpcyBpc24ndCBhIHJlYWwgaW1hZ2Uu";

    @Test
    public void canToggleDataSharing() {
        TestUser testUser = TestUserHelper.createAndSignInUser(ConsentTest.class, true);
        UserClient userClient = testUser.getSession().getUserClient();
        Session session = testUser.getSession();
        try {
            // starts out with no sharing
            assertEquals(SharingScope.NO_SHARING, session.getStudyParticipant().getSharingScope());
            
            // Change, verify in-memory session changed, verify after signing in again that server state has changed
            StudyParticipant participant = new StudyParticipant.Builder().withSharingScope(SharingScope.SPONSORS_AND_PARTNERS).build();
            userClient.saveStudyParticipant(participant);
            assertEquals(SharingScope.SPONSORS_AND_PARTNERS, session.getStudyParticipant().getSharingScope());
            
            participant = userClient.getStudyParticipant();
            assertEquals(SharingScope.SPONSORS_AND_PARTNERS, participant.getSharingScope());
            
            // Do the same thing in reverse, setting to no sharing
            participant = new StudyParticipant.Builder().withSharingScope(SharingScope.NO_SHARING).build();
            userClient.saveStudyParticipant(participant);

            assertEquals(SharingScope.NO_SHARING, session.getStudyParticipant().getSharingScope());

            participant = userClient.getStudyParticipant();
            assertEquals(SharingScope.NO_SHARING, participant.getSharingScope());
            
            session.signOut();
        } finally {
            testUser.signOutAndDeleteUser();
        }
    }

    @Test
    public void giveAndGetConsent() {
        giveAndGetConsentHelper("Eggplant McTester", new LocalDate(1970, 1, 1), null, null);
    }

    @Test
    public void giveAndGetConsentWithSignatureImage() {
        giveAndGetConsentHelper("Eggplant McTester", new LocalDate(1970, 1, 1), FAKE_IMAGE_DATA, "image/fake");
    }

    @Test
    public void signedInUserMustGiveConsent() {
        TestUser user = TestUserHelper.createAndSignInUser(ConsentTest.class, false);
        try {
            UserClient userClient = user.getSession().getUserClient();
            assertFalse("User has not consented", user.getSession().isConsented());
            try {
                userClient.getSchedules();
                fail("Should have required consent.");
            } catch(ConsentRequiredException e) {
                assertEquals("Exception is a 412 Precondition Failed", 412, e.getStatusCode());
            }
            LocalDate date = new LocalDate(1970, 10, 10);
            userClient.consentToResearch(user.getDefaultSubpopulation(),
                    new ConsentSignature(user.getEmail(), date, null, null), SharingScope.SPONSORS_AND_PARTNERS);
            assertTrue("User has consented", user.getSession().isConsented());
            userClient.getSchedules();
        } finally {
            user.signOutAndDeleteUser();
        }
    }

    @Test(expected=InvalidEntityException.class)
    public void userMustMeetMinAgeRequirements() {
        TestUser user = TestUserHelper.createAndSignInUser(ConsentTest.class, false);
        try {
            UserClient userClient = user.getSession().getUserClient();
            LocalDate date = LocalDate.now(); // impossibly young.
            userClient.consentToResearch(user.getDefaultSubpopulation(), 
                    new ConsentSignature(user.getEmail(), date, null, null), SharingScope.ALL_QUALIFIED_RESEARCHERS);
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
        ObjectMapper jsonObjectMapper = Utilities.getMapper();

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
            String imageMimeType) {
        TestUser testUser = TestUserHelper.createAndSignInUser(ConsentTest.class, false);
        ConsentSignature sig = new ConsentSignature(name, birthdate, imageData, imageMimeType);
        try {
            UserClient userClient = testUser.getSession().getUserClient();
            assertFalse("User has not consented", testUser.getSession().isConsented());
            assertFalse(ConsentStatus.isUserConsented(testUser.getSession().getConsentStatuses()));

            // get consent should fail if the user hasn't given consent
            try {
                userClient.getConsentSignature(testUser.getDefaultSubpopulation());
                fail("ConsentRequiredException not thrown");
            } catch (ConsentRequiredException ex) {
                // expected
            }

            // give consent
            userClient.consentToResearch(testUser.getDefaultSubpopulation(), sig, SharingScope.ALL_QUALIFIED_RESEARCHERS);
            
            // The local session should reflect consent status & sharing scope
            assertEquals(SharingScope.ALL_QUALIFIED_RESEARCHERS, testUser.getSession().getStudyParticipant().getSharingScope());
            assertTrue(ConsentStatus.isUserConsented(testUser.getSession().getConsentStatuses()));
            
            // get consent and validate that it's the same consent
            ConsentSignature sigFromServer = userClient.getConsentSignature(testUser.getDefaultSubpopulation());
            
            assertEquals("name matches", name, sigFromServer.getName());
            assertEquals("birthdate matches", birthdate, sigFromServer.getBirthdate());
            assertEquals("imageData matches", imageData, sigFromServer.getImageData());
            assertEquals("imageMimeType matches", imageMimeType, sigFromServer.getImageMimeType());
            
            // giving consent again will throw
            try {
                userClient.consentToResearch(testUser.getDefaultSubpopulation(), sig,
                        SharingScope.ALL_QUALIFIED_RESEARCHERS);
                fail("EntityAlreadyExistsException not thrown");
            } catch (EntityAlreadyExistsException ex) {
                // expected
            }
            
            // The remote session should also reflect the sharing scope
            testUser.getSession().signOut();
            Session session = ClientProvider.signIn(new SignInCredentials("api", testUser.getEmail(), testUser.getPassword()));
            assertEquals(SharingScope.ALL_QUALIFIED_RESEARCHERS, session.getStudyParticipant().getSharingScope());
            assertTrue(ConsentStatus.isUserConsented(session.getConsentStatuses()));

            // withdraw consent
            userClient = session.getUserClient();
            userClient.withdrawConsentToResearch(testUser.getDefaultSubpopulation(), "Withdrawing test user from study.");
            // This method should now (immediately) throw a ConsentRequiredException
            try {
                userClient.getSchedules();
                fail("Should have thrown exception");
            } catch(ConsentRequiredException e) {
                // what we want
            }
        } finally {
            testUser.signOutAndDeleteUser();
        }
    }
    
    @Test
    public void canEmailConsentAgreement() {
        TestUser testUser = TestUserHelper.createAndSignInUser(ConsentTest.class, true);
        try {
            UserClient userClient = testUser.getSession().getUserClient();
            userClient.emailConsentSignature(testUser.getDefaultSubpopulation()); // just verify it throws no errors
        } finally {
            testUser.signOutAndDeleteUser();
        }
    }
    
    @Test
    public void canWithdrawFromAllConsentsInStudy() {
        TestUser user = TestUserHelper.createAndSignInUser(ConsentTest.class, true);
        try {
            Session session = user.getSession();
            
            // Can get activities without an error... user is indeed consented.
            session.getUserClient().getScheduledActivities(1, DateTimeZone.UTC);
            for (ConsentStatus status : session.getConsentStatuses().values()) {
                assertTrue(status.isConsented());
            }
            
            session.getUserClient().withdrawAllConsentsToResearch("I'm just a test user.");
            
            // session has been updated appropriately.
            assertEquals(SharingScope.NO_SHARING, session.getStudyParticipant().getSharingScope());
            assertFalse(ConsentStatus.isUserConsented(session.getConsentStatuses()));

            user.signOut();
            
            try {
                user.signInAgain();
                fail("Should have thrown consent exception");
            } catch(ConsentRequiredException e) {
                for (ConsentStatus status : e.getSession().getConsentStatuses().values()) {
                    assertFalse(status.isConsented());
                }
            }
        } finally {
            user.signOutAndDeleteUser();
        }
    }
}
