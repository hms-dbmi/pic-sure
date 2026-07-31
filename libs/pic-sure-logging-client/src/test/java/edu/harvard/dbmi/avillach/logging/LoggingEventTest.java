package edu.harvard.dbmi.avillach.logging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
// Shadows this package's @AuditEvent annotation, which this test does not use.
import edu.harvard.dbmi.avillach.contracts.audit.AuditEvent;
import edu.harvard.dbmi.avillach.contracts.audit.RequestInfo;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LoggingEventTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void serializesWithSnakeCaseFieldNames() {
        LoggingEvent event = LoggingEvent.builder("QUERY").action("execute").clientType("api").build();

        JsonNode json = mapper.valueToTree(event);
        assertEquals("QUERY", json.get("event_type").asText());
        assertEquals("execute", json.get("action").asText());
        assertEquals("api", json.get("client_type").asText());
    }

    @Test
    void omitsNullFields() {
        LoggingEvent event = LoggingEvent.builder("LOGIN").action("attempt").build();

        JsonNode json = mapper.valueToTree(event);
        assertTrue(json.has("event_type"));
        assertTrue(json.has("action"));
        assertFalse(json.has("client_type"));
        assertFalse(json.has("request"));
        assertFalse(json.has("metadata"));
        assertFalse(json.has("error"));
    }

    @Test
    void serializesRequestInfo() {
        RequestInfo request = new RequestInfoBuilder().method("POST").url("/query/sync").srcIp("10.0.0.1").status(200).duration(150L)
            .httpContentType("application/json").build();

        LoggingEvent event = LoggingEvent.builder("QUERY").action("execute").request(request).build();

        JsonNode json = mapper.valueToTree(event);
        JsonNode reqJson = json.get("request");

        assertNotNull(reqJson);
        assertEquals("POST", reqJson.get("method").asText());
        assertEquals("/query/sync", reqJson.get("url").asText());
        assertEquals("10.0.0.1", reqJson.get("src_ip").asText());
        assertEquals(200, reqJson.get("status").asInt());
        assertEquals(150, reqJson.get("duration").asLong());
        assertEquals("application/json", reqJson.get("http_content_type").asText());

        // null fields should be absent
        assertFalse(reqJson.has("query_string"));
        assertFalse(reqJson.has("dest_ip"));
        assertFalse(reqJson.has("referrer"));
    }

    @Test
    void serializesMetadata() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("resourceId", "abc-123");
        metadata.put("queryCount", 5);

        LoggingEvent event = LoggingEvent.builder("QUERY").metadata(metadata).build();

        JsonNode json = mapper.valueToTree(event);
        JsonNode metaJson = json.get("metadata");

        assertNotNull(metaJson);
        assertEquals("abc-123", metaJson.get("resourceId").asText());
        assertEquals(5, metaJson.get("queryCount").asInt());
    }

    @Test
    void serializesError() {
        Map<String, Object> error = new HashMap<>();
        error.put("message", "Not found");
        error.put("code", 404);

        LoggingEvent event = LoggingEvent.builder("ERROR").error(error).build();

        JsonNode json = mapper.valueToTree(event);
        JsonNode errorJson = json.get("error");

        assertNotNull(errorJson);
        assertEquals("Not found", errorJson.get("message").asText());
        assertEquals(404, errorJson.get("code").asInt());
    }

    @Test
    void requiresEventType() {
        assertThrows(IllegalArgumentException.class, () -> LoggingEvent.builder(null));
        assertThrows(IllegalArgumentException.class, () -> LoggingEvent.builder(""));
        assertThrows(IllegalArgumentException.class, () -> LoggingEvent.builder(" "));
    }

    @Test
    void rejectsMetadataExceeding50Keys() {
        Map<String, Object> metadata = new HashMap<>();
        for (int i = 0; i < 51; i++) {
            metadata.put("key" + i, "value");
        }

        assertThrows(IllegalArgumentException.class, () -> LoggingEvent.builder("TEST").metadata(metadata).build());
    }

    @Test
    void rejectsErrorExceeding20Keys() {
        Map<String, Object> error = new HashMap<>();
        for (int i = 0; i < 21; i++) {
            error.put("key" + i, "value");
        }

        assertThrows(IllegalArgumentException.class, () -> LoggingEvent.builder("TEST").error(error).build());
    }

    @Test
    void metadataIsImmutableAfterBuild() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("key", "value");

        LoggingEvent event = LoggingEvent.builder("TEST").metadata(metadata).build();

        assertThrows(UnsupportedOperationException.class, () -> event.getMetadata().put("newKey", "newValue"));
    }

    @Test
    void deserializesFromServerFormat() throws Exception {
        String json = "{\"event_type\":\"QUERY\",\"action\":\"execute\",\"client_type\":\"api\","
            + "\"request\":{\"method\":\"POST\",\"url\":\"/query\",\"src_ip\":\"127.0.0.1\",\"status\":200}}";

        LoggingEvent event = mapper.readValue(json, LoggingEvent.class);

        assertEquals("QUERY", event.getEventType());
        assertEquals("execute", event.getAction());
        assertEquals("api", event.getClientType());
        assertNotNull(event.getRequest());
        assertEquals("POST", event.getRequest().method());
        assertEquals("/query", event.getRequest().url());
        assertEquals("127.0.0.1", event.getRequest().srcIp());
        assertEquals(200, event.getRequest().status());
    }

    @Test
    void roundTripSerialization() throws Exception {
        RequestInfo request = new RequestInfoBuilder().method("GET").url("/info").status(200).duration(42L).build();

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("key", "value");

        LoggingEvent original = LoggingEvent.builder("ACCESS").action("read").clientType("ui").request(request).metadata(metadata).build();

        String json = mapper.writeValueAsString(original);
        LoggingEvent deserialized = mapper.readValue(json, LoggingEvent.class);

        assertEquals(original.getEventType(), deserialized.getEventType());
        assertEquals(original.getAction(), deserialized.getAction());
        assertEquals(original.getClientType(), deserialized.getClientType());
        assertEquals(original.getRequest().method(), deserialized.getRequest().method());
        assertEquals(original.getRequest().url(), deserialized.getRequest().url());
        assertEquals(original.getRequest().status(), deserialized.getRequest().status());
        assertEquals(original.getRequest().duration(), deserialized.getRequest().duration());
        assertEquals("value", deserialized.getMetadata().get("key"));
    }

    @Test
    void serializesSessionId() {
        LoggingEvent event = LoggingEvent.builder("QUERY").action("execute").sessionId("sess-abc").build();

        JsonNode json = mapper.valueToTree(event);
        assertEquals("sess-abc", json.get("session_id").asText());
    }

    @Test
    void omitsNullSessionId() {
        LoggingEvent event = LoggingEvent.builder("QUERY").action("execute").build();

        JsonNode json = mapper.valueToTree(event);
        assertFalse(json.has("session_id"));
    }

    @Test
    void roundTripPreservesSessionId() throws Exception {
        LoggingEvent original = LoggingEvent.builder("ACCESS").action("read").sessionId("sess-xyz").build();

        String json = mapper.writeValueAsString(original);
        LoggingEvent deserialized = mapper.readValue(json, LoggingEvent.class);

        assertEquals("sess-xyz", deserialized.getSessionId());
    }

    @Test
    void withClientTypePreservesSessionId() {
        LoggingEvent original = LoggingEvent.builder("QUERY").action("execute").sessionId("sess-123").build();
        LoggingEvent copy = original.withClientType("api");

        assertEquals("sess-123", copy.getSessionId());
        assertEquals("api", copy.getClientType());
    }

    /**
     * Applying the config's default clientType must never throw: LoggingClient does it outside the try/catch in send(), so a throw here
     * would escape a call documented never to throw and fail a user's request over an audit record. Validation belongs at construction, so
     * an event that never went through the builder -- one read back off the wire, over the caps -- still copies cleanly.
     */
    @Test
    void withClientTypeDoesNotRevalidateAnEventItDidNotBuild() throws Exception {
        Map<String, Object> overCap = new HashMap<>();
        for (int i = 0; i < 60; i++) {
            overCap.put("key" + i, "value");
        }
        LoggingEvent offTheWire = mapper.readValue(
            mapper.writeValueAsString(new AuditEvent("QUERY", "execute", null, null, null, null, overCap, null)), LoggingEvent.class
        );

        LoggingEvent copy = assertDoesNotThrow(() -> offTheWire.withClientType("api"));

        assertEquals("api", copy.getClientType());
        assertEquals(60, copy.getMetadata().size());
    }

    /**
     * The point of the consolidation: this class is a builder with validation, not a second copy of the wire model. What goes on the socket
     * is the shared {@link AuditEvent} record the logging service reads back, byte for byte -- so a field can never be added to one side
     * without the other seeing it.
     */
    @Test
    void serializesAsTheSharedAuditEventContract() throws Exception {
        RequestInfo request = new RequestInfoBuilder().requestId("req-123").method("POST").url("/query/sync").queryString("limit=100")
            .srcIp("10.0.0.1").destIp("10.0.0.2").destPort(8080).httpUserAgent("PIC-SURE/3.0").httpContentType("application/json")
            .status(200).bytes(4096L).duration(250L).referrer("https://picsure.example.com").build();
        Map<String, Object> metadata = Map.of("resourceId", "abc-123");
        Map<String, Object> error = Map.of("message", "Not found");

        LoggingEvent event = LoggingEvent.builder("QUERY").action("execute").clientType("api").sessionId("sess-1").request(request)
            .metadata(metadata).error(error).build();

        AuditEvent expected = new AuditEvent("QUERY", "execute", "api", null, "sess-1", request, metadata, error);
        assertEquals(mapper.writeValueAsString(expected), mapper.writeValueAsString(event));
        assertEquals(expected, event.toAuditEvent());
    }

    /**
     * The emitters never populate "caller" -- the logging service derives the identity from the Authorization header it is handed. Sharing
     * the record must not start writing an empty caller key onto every audit POST.
     */
    @Test
    void omitsTheServerDerivedCaller() {
        LoggingEvent event = LoggingEvent.builder("QUERY").action("execute").build();

        assertFalse(mapper.valueToTree(event).has("caller"));
    }
}
