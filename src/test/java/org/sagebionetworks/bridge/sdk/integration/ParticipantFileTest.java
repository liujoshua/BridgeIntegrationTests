package org.sagebionetworks.bridge.sdk.integration;

import okhttp3.ResponseBody;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sagebionetworks.bridge.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.rest.exceptions.InvalidEntityException;
import org.sagebionetworks.bridge.rest.model.Message;
import org.sagebionetworks.bridge.rest.model.ParticipantFile;
import org.sagebionetworks.bridge.rest.model.ParticipantFileList;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;

import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Scanner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

public class ParticipantFileTest {

    static final String TEST_UPLOAD_STRING = "This text uploaded as an object via presigned URL.";

    TestUser participant;

    ForConsentedUsersApi userApi;

    ParticipantFile file;

    @Before
    public void before() throws Exception {
        participant = TestUserHelper.createAndSignInUser(ParticipantFileTest.class, true);
        userApi = participant.getClient(ForConsentedUsersApi.class);
    }

    @After
    public void after() throws Exception {
        List<ParticipantFile> remainder = userApi.getParticipantFiles(null, 10).execute().body().getItems();
        while (!remainder.isEmpty()) {
            for (ParticipantFile file : remainder) {
                userApi.deleteParticipantFile(file.getFileId()).execute();
            }
            remainder = userApi.getParticipantFiles(null, 10).execute().body().getItems();
        }
        participant.signOutAndDeleteUser();
    }

    @Test
    public void invalidParticipantFileRejected() throws Exception {
        file = new ParticipantFile();

        try {
            userApi.createParticipantFile("file_id", file).execute().body();
            fail("Should have thrown an exception");
        } catch(InvalidEntityException e) {
            assertEquals("mimeType is required", e.getErrors().get("mimeType").get(0));
        }
    }
    
    @Test
    public void crudParticipantFile() throws Exception {
        file = new ParticipantFile();
        file.setMimeType("text/plain");

        ForConsentedUsersApi userApi = participant.getClient(ForConsentedUsersApi.class);

        final ParticipantFile keys = userApi.createParticipantFile("file_id", file).execute().body();

        assertNotNull(keys);
        assertEquals(file.getMimeType(), keys.getMimeType());
        String uploadUrl = keys.getUploadUrl();

        URL url = new URL(uploadUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setDoOutput(true);
        connection.setRequestMethod("PUT");
        connection.setRequestProperty("Content-Type", "text/plain");
        OutputStreamWriter out = new OutputStreamWriter(connection.getOutputStream());
        out.write(TEST_UPLOAD_STRING);
        out.close();
        assertEquals(200, connection.getResponseCode());;
        connection.disconnect();
        
        ParticipantFileList results = userApi.getParticipantFiles(null, 5).execute().body();
        assertNotNull(results);
        List<ParticipantFile> resultList = results.getItems();
        assertEquals(1, resultList.size());
        ParticipantFile onlyFile = resultList.get(0);
        assertEquals("text/plain", onlyFile.getMimeType());
        assertEquals("file_id", onlyFile.getFileId());

        for (int i = 0; i < 5; i++) {
            file = new ParticipantFile();
            file.setMimeType("text/plain");
            userApi.createParticipantFile(i + "file", file).execute();
        }
        
        // Note: After starting a local Bridge server, this test fails with a 403 Forbidden. 
        // Thereafter is seems to consistently succeed. It is not a DDB eventual consistency
        // issue.
        ResponseBody body = userApi.getParticipantFile("file_id").execute().body();
        assertNotNull(body);
        assertNotNull(body.contentType());
        assertEquals("text/plain", body.contentType().toString());
        assertEquals(TEST_UPLOAD_STRING.length(), body.contentLength());
        try (InputStream content = body.byteStream(); Scanner sc = new Scanner(content)) {
            assertEquals("This text uploaded as an object via presigned URL.", sc.nextLine());
        }

        results = userApi.getParticipantFiles(null, 5).execute().body();
        assertNotNull(results);
        resultList = results.getItems();
        assertEquals(5, resultList.size());
        String nextKey = results.getNextPageOffsetKey();
        results = userApi.getParticipantFiles(nextKey, 5).execute().body();
        assertNotNull(results);
        resultList = results.getItems();
        assertEquals(1, resultList.size());
        assertNull(results.getNextPageOffsetKey());

        Message deleteMessage = userApi.deleteParticipantFile("file_id").execute().body();
        assertNotNull(deleteMessage);
        assertEquals("Participant file deleted.", deleteMessage.getMessage());

        for (int i = 0; i < 5; i++) {
            userApi.deleteParticipantFile(i + "file").execute();
        }

        results = userApi.getParticipantFiles(null, 5).execute().body();
        assertNotNull(results);
        resultList = results.getItems();
        assertEquals(0, resultList.size());
    }
}