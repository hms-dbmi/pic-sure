package edu.harvard.dbmi.avillach.logging.handler;

import edu.harvard.dbmi.avillach.logging.model.AuditEvent;
import edu.harvard.dbmi.avillach.logging.service.AuditLogService;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuditHandlerTest {

    private AuditLogService auditLogService;
    private AuditHandler handler;

    @BeforeEach
    void setUp() {
        auditLogService = mock(AuditLogService.class);
        handler = new AuditHandler(auditLogService);
    }

    @Test
    void validRequestReturns202() {
        Context ctx = mock(Context.class);
        when(ctx.body()).thenReturn("{\"event_type\":\"QUERY\",\"action\":\"execute\"}");
        when(ctx.header("Authorization")).thenReturn("Bearer token");
        when(ctx.header("X-Request-Id")).thenReturn("req-123");
        when(ctx.status(202)).thenReturn(ctx);

        handler.handle(ctx);

        verify(ctx).status(202);
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditLogService).logEvent(captor.capture(), eq("Bearer token"), eq("req-123"));
        assertEquals("QUERY", captor.getValue().eventType());
        assertEquals("execute", captor.getValue().action());
    }

    @Test
    void invalidJsonThrows400() {
        Context ctx = mock(Context.class);
        when(ctx.body()).thenReturn("not-json");

        assertThrows(BadRequestResponse.class, () -> handler.handle(ctx));
    }

    @Test
    void missingEventTypeThrows400() {
        Context ctx = mock(Context.class);
        when(ctx.body()).thenReturn("{\"action\":\"execute\"}");

        assertThrows(BadRequestResponse.class, () -> handler.handle(ctx));
    }

    @Test
    void blankEventTypeThrows400() {
        Context ctx = mock(Context.class);
        when(ctx.body()).thenReturn("{\"event_type\":\"   \"}");

        assertThrows(BadRequestResponse.class, () -> handler.handle(ctx));
    }

    @Test
    void unknownFieldsIgnored() {
        Context ctx = mock(Context.class);
        when(ctx.body()).thenReturn("{\"event_type\":\"TEST\",\"unknown_field\":\"value\"}");
        when(ctx.status(202)).thenReturn(ctx);

        assertDoesNotThrow(() -> handler.handle(ctx));
        verify(auditLogService).logEvent(any(), any(), any());
    }

    // --- ObjectMapper hardening tests (Change 1) ---

    @Test
    void deeplyNestedJsonRejected() {
        // Build JSON nested beyond depth 10
        StringBuilder json = new StringBuilder();
        json.append("{\"event_type\":\"TEST\",\"metadata\":");
        for (int i = 0; i < 15; i++) {
            json.append("{\"k\":");
        }
        json.append("\"v\"");
        for (int i = 0; i < 15; i++) {
            json.append("}");
        }
        json.append("}");

        Context ctx = mock(Context.class);
        when(ctx.body()).thenReturn(json.toString());

        assertThrows(BadRequestResponse.class, () -> handler.handle(ctx));
    }

    @Test
    void oversizedStringRejected() {
        // Build a string longer than 10KB
        String bigValue = "x".repeat(11_000);
        String json = "{\"event_type\":\"TEST\",\"action\":\"" + bigValue + "\"}";

        Context ctx = mock(Context.class);
        when(ctx.body()).thenReturn(json);

        assertThrows(BadRequestResponse.class, () -> handler.handle(ctx));
    }

    // --- Metadata/error key limit tests (Change 2) ---

    @Test
    void metadataExceeding50KeysRejected() {
        Map<String, String> metadata = IntStream.rangeClosed(1, 51)
            .boxed()
            .collect(Collectors.toMap(i -> "key" + i, i -> "val" + i));
        String metadataJson = mapToJson(metadata);
        String json = "{\"event_type\":\"TEST\",\"metadata\":" + metadataJson + "}";

        Context ctx = mock(Context.class);
        when(ctx.body()).thenReturn(json);

        BadRequestResponse ex = assertThrows(BadRequestResponse.class, () -> handler.handle(ctx));
        assertTrue(ex.getMessage().contains("metadata"));
    }

    @Test
    void errorExceeding20KeysRejected() {
        Map<String, String> error = IntStream.rangeClosed(1, 21)
            .boxed()
            .collect(Collectors.toMap(i -> "key" + i, i -> "val" + i));
        String errorJson = mapToJson(error);
        String json = "{\"event_type\":\"TEST\",\"error\":" + errorJson + "}";

        Context ctx = mock(Context.class);
        when(ctx.body()).thenReturn(json);

        BadRequestResponse ex = assertThrows(BadRequestResponse.class, () -> handler.handle(ctx));
        assertTrue(ex.getMessage().contains("error"));
    }

    @Test
    void metadataAtExactly50KeysAccepted() {
        Map<String, String> metadata = IntStream.rangeClosed(1, 50)
            .boxed()
            .collect(Collectors.toMap(i -> "key" + i, i -> "val" + i));
        String metadataJson = mapToJson(metadata);
        String json = "{\"event_type\":\"TEST\",\"metadata\":" + metadataJson + "}";

        Context ctx = mock(Context.class);
        when(ctx.body()).thenReturn(json);
        when(ctx.status(202)).thenReturn(ctx);

        assertDoesNotThrow(() -> handler.handle(ctx));
        verify(auditLogService).logEvent(any(), any(), any());
    }

    @Test
    void errorAtExactly20KeysAccepted() {
        Map<String, String> error = IntStream.rangeClosed(1, 20)
            .boxed()
            .collect(Collectors.toMap(i -> "key" + i, i -> "val" + i));
        String errorJson = mapToJson(error);
        String json = "{\"event_type\":\"TEST\",\"error\":" + errorJson + "}";

        Context ctx = mock(Context.class);
        when(ctx.body()).thenReturn(json);
        when(ctx.status(202)).thenReturn(ctx);

        assertDoesNotThrow(() -> handler.handle(ctx));
        verify(auditLogService).logEvent(any(), any(), any());
    }

    @Test
    void nestingAtDepth10Accepted() {
        // Build JSON nested exactly to depth 10 (object with metadata containing nested objects)
        // event_type field + metadata with 8 levels of nesting (root object = 1, metadata object = 2, so 8 more = 10)
        StringBuilder json = new StringBuilder();
        json.append("{\"event_type\":\"TEST\",\"metadata\":");
        for (int i = 0; i < 8; i++) {
            json.append("{\"k\":");
        }
        json.append("\"v\"");
        for (int i = 0; i < 8; i++) {
            json.append("}");
        }
        json.append("}");

        Context ctx = mock(Context.class);
        when(ctx.body()).thenReturn(json.toString());
        when(ctx.status(202)).thenReturn(ctx);

        assertDoesNotThrow(() -> handler.handle(ctx));
    }

    private String mapToJson(Map<String, String> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : map.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(entry.getKey()).append("\":\"").append(entry.getValue()).append("\"");
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }
}
