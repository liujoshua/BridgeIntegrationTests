package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;

import org.joda.time.LocalDate;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import retrofit2.Response;

import org.sagebionetworks.bridge.rest.api.UsersApi;
import org.sagebionetworks.bridge.rest.model.DateRange;
import org.sagebionetworks.bridge.rest.model.Message;
import org.sagebionetworks.bridge.user.TestUserHelper;

// We don't currently have a way to hook into an email address to see the result. For now, just make sure that the call
// to the API succeeds and doesn't throw.
public class UserDataDownloadTest {
    private static TestUserHelper.TestUser user;

    @BeforeClass
    public static void setup() throws Exception {
        user = TestUserHelper.createAndSignInUser(UserDataDownloadTest.class, true);
    }

    @AfterClass
    public static void deleteUser() throws Exception {
        if (user != null) {
            user.signOutAndDeleteUser();
        }
    }

    @Test
    public void withBody() throws Exception {
        LocalDate todaysDate = LocalDate.now();
        DateRange dateRange = new DateRange().startDate(todaysDate).endDate(todaysDate);
        Response<Message> response = user.getClient(UsersApi.class).sendDataToUser(dateRange).execute();
        assertEquals(202, response.code());
    }
}
