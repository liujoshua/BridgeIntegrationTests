package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sagebionetworks.bridge.rest.RestUtils;
import org.sagebionetworks.bridge.rest.api.AppConfigsApi;
import org.sagebionetworks.bridge.rest.exceptions.ConcurrentModificationException;
import org.sagebionetworks.bridge.rest.exceptions.EntityAlreadyExistsException;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.model.AppConfigElement;
import org.sagebionetworks.bridge.rest.model.AppConfigElementList;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.rest.model.VersionHolder;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;

@SuppressWarnings("ConstantConditions")
public class AppConfigElementTest {
    private static final String TEST_STRING = "This is no longer a participant";
    private TestUser developer;
    private TestUser admin;
    private String id;
    private String id2;
    private AppConfigsApi adminConfigsApi;

    @Before
    public void before() throws IOException {
        developer = TestUserHelper.createAndSignInUser(AppConfigElementTest.class, false, Role.DEVELOPER);
        admin = TestUserHelper.getSignedInAdmin();
        id = Tests.randomIdentifier(AppConfigElementTest.class);
        id2 = Tests.randomIdentifier(AppConfigElementTest.class);
        adminConfigsApi = admin.getClient(AppConfigsApi.class);
    }

    @After
    public void after1() throws Exception {
        adminConfigsApi.deleteAllAppConfigElementRevisions(id, true).execute();
    }
    
    @After
    public void after2() throws Exception {
        adminConfigsApi.deleteAllAppConfigElementRevisions(id2, true).execute();
    }
    
    @After
    public void after3() throws Exception {
        if (developer != null) {
            developer.signOutAndDeleteUser();    
        }
    }

    @Test
    public void crudAppConfigElement() throws Exception {
        AppConfigsApi configsApi = developer.getClient(AppConfigsApi.class);

        StudyParticipant participantV1 = new StudyParticipant();
        participantV1.setFirstName("first name test");

        AppConfigElement elementV1 = new AppConfigElement();
        elementV1.setId(id);
        elementV1.setData(participantV1);

        VersionHolder v1 = configsApi.createAppConfigElement(elementV1).execute().body();
        elementV1.setVersion(v1.getVersion());
        elementV1.setRevision(1L); // this was automatically set because it was zero, below we'll set it to a specific
                                   // revision.

        AppConfigElementList listV1 = configsApi.getAllAppConfigElementRevisions(id, true).execute().body();

        AppConfigElement retrievedInListV1 = listV1.getItems().get(0);
        assertEquals(id, retrievedInListV1.getId());
        assertEquals(new Long(1), retrievedInListV1.getRevision());

        StudyParticipant retrievedParticipantV1 = RestUtils.toType(retrievedInListV1.getData(), StudyParticipant.class);
        assertEquals("first name test", retrievedParticipantV1.getFirstName());

        AppConfigElement retrievedDirectlyV1 = configsApi.getAppConfigElement(id, retrievedInListV1.getRevision())
                .execute().body();
        assertEquals(id, retrievedDirectlyV1.getId());
        assertEquals(new Long(1), retrievedDirectlyV1.getRevision());

        StudyParticipant participantV2 = new StudyParticipant();
        participantV2.setFirstName("second name test");

        AppConfigElement elementV2 = new AppConfigElement();
        elementV2.setId(id);
        elementV2.setData(participantV2);
        try {
            // This fails: it just gets set to the same revision (v1) which exists
            configsApi.createAppConfigElement(elementV2).execute().body();
            fail("Should have thrown exception");
        } catch (EntityAlreadyExistsException e) {
        }
        elementV2.setRevision(2L); // this is new, and will work

        VersionHolder version = configsApi.createAppConfigElement(elementV2).execute().body();
        elementV2.setVersion(version.getVersion());

        AppConfigElementList listV2 = configsApi.getAllAppConfigElementRevisions(id, true).execute().body();
        // These are most recent first.
        assertEquals(2, listV2.getItems().size());
        assertEquals(new Long(2), listV2.getItems().get(0).getRevision());
        assertEquals(new Long(1), listV2.getItems().get(1).getRevision());

        // This does not work. It again tries to create revision 1
        try {
            configsApi.createAppConfigElement(elementV2).execute().body();
            fail("Should have thrown exception");
        } catch (EntityAlreadyExistsException e) {
        }

        // does not exist, throws correct exception
        try {
            configsApi.getAppConfigElement(id, 3L).execute();
            fail("Should have thrown exception");
        } catch (EntityNotFoundException e) {
        }

        // Can update unpublished versions...
        elementV1.setData(TEST_STRING);
        VersionHolder holder = configsApi.updateAppConfigElement(id, elementV1.getRevision(), elementV1).execute()
                .body();
        elementV1.setVersion(holder.getVersion());
        
        // Cannot submit an update without a version...
        long savedVersion = elementV1.getVersion();
        try {
            elementV1.setVersion(null);
            configsApi.updateAppConfigElement(id, elementV1.getRevision(), elementV1).execute().body();
            fail("Should have thrown exception");
        } catch (ConcurrentModificationException e) {
        }
        elementV1.setVersion(savedVersion);

        AppConfigElement retrievedUpdated = configsApi.getAppConfigElement(elementV1.getId(), elementV1.getRevision())
                .execute().body();
        assertEquals(TEST_STRING, (String) retrievedUpdated.getData());

        // logically delete v2
        configsApi.deleteAppConfigElement(id, elementV2.getRevision(), false).execute().body();

        // You can still get it... despite being logically deleted
        AppConfigElement element = configsApi.getAppConfigElement(id, elementV2.getRevision()).execute().body();
        assertTrue(element.isDeleted());

        // Most recent should now be v1
        AppConfigElement mostRecentElement = configsApi.getMostRecentAppConfigElement(id).execute().body();
        assertEquals(elementV1.getRevision(), mostRecentElement.getRevision());

        // Test includeDeleted on the call to get all revisions of one element
        AppConfigElementList responseFalse = configsApi.getAllAppConfigElementRevisions(id, false).execute().body();
        assertEquals(1, responseFalse.getItems().size());
        assertFalse(responseFalse.getRequestParams().isIncludeDeleted());

        AppConfigElementList responseTrue = configsApi.getAllAppConfigElementRevisions(id, true).execute().body();
        assertEquals(2, responseTrue.getItems().size());
        assertTrue(responseTrue.getRequestParams().isIncludeDeleted());

        // Logically delete and verify all versions are marked deleted
        configsApi.deleteAllAppConfigElementRevisions(id, false).execute();
        
        AppConfigElementList list = configsApi.getAllAppConfigElementRevisions(id, true).execute().body();
        assertFalse(list.getItems().isEmpty());
        
        list = configsApi.getAllAppConfigElementRevisions(id, false).execute().body();
        assertTrue(list.getItems().isEmpty());
        
        // Physically delete and verify a version is gone
        // admin has to do this...
        adminConfigsApi.deleteAppConfigElement(id, elementV1.getRevision(), true).execute();
        try {
            configsApi.getAppConfigElement(id, elementV1.getRevision()).execute();
            fail("Should have thrown exception");
        } catch (EntityNotFoundException e) {
        }
        // Use the delete all revisions version and verify a version is gone
        adminConfigsApi.deleteAllAppConfigElementRevisions(id2, true).execute();
        try {
            configsApi.getAppConfigElement(id2, elementV2.getRevision()).execute();
            fail("Should have thrown exception");
        } catch (EntityNotFoundException e) {
        }
    }

    @Test
    public void testMostRecentVersions() throws Exception {
        AppConfigsApi configsApi = developer.getClient(AppConfigsApi.class);

        AppConfigElement elementID1V1 = new AppConfigElement();
        elementID1V1.setId(id);
        elementID1V1.setRevision(1L);
        elementID1V1.setData("test");

        AppConfigElement elementID1V2 = new AppConfigElement();
        elementID1V2.setId(id);
        elementID1V2.setRevision(2L);
        elementID1V2.setData("test");

        AppConfigElement elementID2V1 = new AppConfigElement();
        elementID2V1.setId(id2);
        elementID2V1.setRevision(1L);
        elementID2V1.setData("test");

        AppConfigElement elementID2V2 = new AppConfigElement();
        elementID2V2.setId(id2);
        elementID2V2.setRevision(2L);
        elementID2V2.setData("test");

        configsApi.createAppConfigElement(elementID1V1).execute();
        configsApi.createAppConfigElement(elementID1V2).execute();
        configsApi.createAppConfigElement(elementID2V1).execute();
        configsApi.createAppConfigElement(elementID2V2).execute();

        AppConfigElement mostRecentElement = configsApi.getMostRecentAppConfigElement(id).execute().body();
        assertTrue(isElementVersion(mostRecentElement, id, 2L));

        mostRecentElement = configsApi.getMostRecentAppConfigElement(id2).execute().body();
        assertTrue(isElementVersion(mostRecentElement, id2, 2L));

        // DELETE
        configsApi.deleteAppConfigElement(id, 1L, false).execute();
        configsApi.deleteAppConfigElement(id2, 2L, false).execute();

        // With deleted included, these should be v1 r2, and v2 r2 (both the most recent)
        Tests.retryHelper(() -> configsApi.getMostRecentAppConfigElements(true).execute().body().getItems(),
                list -> list.stream().anyMatch(config -> isElementVersion(config, id, 2L))
                        && list.stream().anyMatch(config -> isElementVersion(config, id2, 2L)));

        // With deleted excluded, this picture looks different:
        // Now we get a prior version in the list.
        Tests.retryHelper(() -> configsApi.getMostRecentAppConfigElements(false).execute().body().getItems(),
                list -> list.stream().anyMatch(config -> isElementVersion(config, id, 2L))
                        && list.stream().anyMatch(config -> isElementVersion(config, id2, 1L)));
    }

    private boolean isElementVersion(AppConfigElement config, String id, long revision) {
        return (config.getId().equals(id) && config.getRevision() == revision);
    }
}
