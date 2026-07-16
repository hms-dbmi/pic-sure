package edu.harvard.dbmi.avillach.logging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        RequestInfo request = RequestInfo.builder().method("POST").url("/query/sync").srcIp("10.0.0.1").status(200).duration(150L)
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
        assertEquals("POST", event.getRequest().getMethod());
        assertEquals("/query", event.getRequest().getUrl());
        assertEquals("127.0.0.1", event.getRequest().getSrcIp());
        assertEquals(200, event.getRequest().getStatus());
    }

    @Test
    void roundTripSerialization() throws Exception {
        RequestInfo request = RequestInfo.builder().method("GET").url("/info").status(200).duration(42L).build();

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("key", "value");

        LoggingEvent original = LoggingEvent.builder("ACCESS").action("read").clientType("ui").request(request).metadata(metadata).build();

        String json = mapper.writeValueAsString(original);
        LoggingEvent deserialized = mapper.readValue(json, LoggingEvent.class);

        assertEquals(original.getEventType(), deserialized.getEventType());
        assertEquals(original.getAction(), deserialized.getAction());
        assertEquals(original.getClientType(), deserialized.getClientType());
        assertEquals(original.getRequest().getMethod(), deserialized.getRequest().getMethod());
        assertEquals(original.getRequest().getUrl(), deserialized.getRequest().getUrl());
        assertEquals(original.getRequest().getStatus(), deserialized.getRequest().getStatus());
        assertEquals(original.getRequest().getDuration(), deserialized.getRequest().getDuration());
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

    @Test
    void requestInfoAllFields() {
        RequestInfo request = RequestInfo.builder().requestId("req-123").method("POST").url("/query/sync").queryString("limit=100")
            .srcIp("10.0.0.1").destIp("10.0.0.2").destPort(8080).httpUserAgent("PIC-SURE/2.0").httpContentType("application/json")
            .status(200).bytes(4096L).duration(250L).referrer("https://picsure.example.com").build();

        JsonNode json = mapper.valueToTree(request);

        assertEquals("req-123", json.get("request_id").asText());
        assertEquals("POST", json.get("method").asText());
        assertEquals("/query/sync", json.get("url").asText());
        assertEquals("limit=100", json.get("query_string").asText());
        assertEquals("10.0.0.1", json.get("src_ip").asText());
        assertEquals("10.0.0.2", json.get("dest_ip").asText());
        assertEquals(8080, json.get("dest_port").asInt());
        assertEquals("PIC-SURE/2.0", json.get("http_user_agent").asText());
        assertEquals("application/json", json.get("http_content_type").asText());
        assertEquals(200, json.get("status").asInt());
        assertEquals(4096, json.get("bytes").asLong());
        assertEquals(250, json.get("duration").asLong());
        assertEquals("https://picsure.example.com", json.get("referrer").asText());
    }
}
