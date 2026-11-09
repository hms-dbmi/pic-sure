package edu.harvard.hms.dbmi.avillach.ai.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RequestFingerprintTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ChatRequest request(String query) throws Exception {
        return new ChatRequest("hello", "conv-1", "req-1", JSON.readTree(query), null, null);
    }

    @Test
    void theOrderOfAQueryObjectsFieldsDoesNotChangeTheFingerprint() throws Exception {
        assertEquals(
            RequestFingerprint.of(request("{\"select\":[\"a\"],\"phenotypicClause\":{\"x\":1,\"y\":2}}")),
            RequestFingerprint.of(request("{\"phenotypicClause\":{\"y\":2,\"x\":1},\"select\":[\"a\"]}"))
        );
    }

    @Test
    void theOrderOfAListStillChangesTheFingerprint() throws Exception {
        assertNotEquals(RequestFingerprint.of(request("{\"select\":[\"a\",\"b\"]}")), RequestFingerprint.of(request("{\"select\":[\"b\",\"a\"]}")));
    }
}
