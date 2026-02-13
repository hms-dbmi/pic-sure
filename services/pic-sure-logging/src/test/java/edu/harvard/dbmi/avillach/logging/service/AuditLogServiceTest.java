package edu.harvard.dbmi.avillach.logging.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import edu.harvard.dbmi.avillach.logging.TestJwtBuilder;
import edu.harvard.dbmi.avillach.logging.config.AppConfig;
import edu.harvard.dbmi.avillach.logging.model.AuditEvent;
import edu.harvard.dbmi.avillach.logging.model.RequestInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AuditLogServiceTest {

    private AuditLogService service;
    private ListAppender<ILoggingEvent> listAppender;
    private Logger auditLogger;

    @BeforeEach
    void setUp() {
        AppConfig config = new AppConfig(
            "test-key", "myapp", "myplatform", "staging", "myhost",
            8080, "*",
            Map.of("sub", "subject", "email", "user_email", "roles", "roles", "logged_in", "logged_in")
        );
        JwtDecodeService jwtService = new JwtDecodeService(config.jwtClaimMapping());
        service = new AuditLogService(config, jwtService);

        auditLogger = (Logger) LoggerFactory.getLogger("AUDIT");
        listAppender = new ListAppender<>();
        listAppender.start();
        auditLogger.addAppender(listAppender);
    }

    @AfterEach
    void tearDown() {
        auditLogger.detachAppender(listAppender);
    }

    @Test
    void fullEventWithAllFields() {
        RequestInfo request = new RequestInfo(
            "req-123", "POST", "/api/query", "limit=10",
            "192.168.1.1", "10.0.0.5", 8443,
            "Mozilla/5.0", "application/json", 200, 1024L, 150L,
            "https://example.com"
        );
        AuditEvent event = new AuditEvent(
            "QUERY", "execute", "web",
            request,
            Map.of("query_id", "q1"),
            Map.of("code", "500", "message", "Internal error")
        );

        String token = TestJwtBuilder.buildToken(Map.of("sub", "user123", "email", "user@example.com"));

        service.logEvent(event, "Bearer " + token, null);

        assertEquals(1, listAppender.list.size());
        String message = listAppender.list.get(0).getFormattedMessage();
        assertTrue(message.contains("event_type=QUERY"));
        assertTrue(message.contains("action=execute"));
        assertTrue(message.contains("request_id=req-123"));
        assertTrue(message.contains("app=myapp"));
    }

    @Test
    void nullRequestHandled() {
        AuditEvent event = new AuditEvent("LOGIN", null, null, null, null, null);

        service.logEvent(event, null, null);

        assertEquals(1, listAppender.list.size());
        String message = listAppender.list.get(0).getFormattedMessage();
        assertTrue(message.contains("event_type=LOGIN"));
        assertTrue(message.contains("logged_in=false"));
    }

    @Test
    void emptyMetadataAndErrorOmitted() {
        AuditEvent event = new AuditEvent("LOGIN", null, null, null, Map.of(), Map.of());

        service.logEvent(event, null, null);

        assertEquals(1, listAppender.list.size());
        String message = listAppender.list.get(0).getFormattedMessage();
        assertFalse(message.contains("metadata"));
        assertFalse(message.contains("error"));
    }

    @Test
    void noJwtSetsLoggedInFalse() {
        AuditEvent event = new AuditEvent("PAGE_VIEW", null, null, null, null, null);

        service.logEvent(event, null, null);

        assertEquals(1, listAppender.list.size());
        String message = listAppender.list.get(0).getFormattedMessage();
        assertTrue(message.contains("logged_in=false"));
    }

    @Test
    void requestIdFromBody() {
        RequestInfo request = new RequestInfo("body-id", null, null, null, null, null, null, null, null, null, null, null, null);
        AuditEvent event = new AuditEvent("TEST", null, null, request, null, null);

        service.logEvent(event, null, "header-id");

        assertEquals(1, listAppender.list.size());
        String message = listAppender.list.get(0).getFormattedMessage();
        assertTrue(message.contains("request_id=body-id"));
    }

    @Test
    void requestIdFromHeaderFallback() {
        AuditEvent event = new AuditEvent("TEST", null, null, null, null, null);

        service.logEvent(event, null, "header-id");

        assertEquals(1, listAppender.list.size());
        String message = listAppender.list.get(0).getFormattedMessage();
        assertTrue(message.contains("request_id=header-id"));
    }

    @Test
    void requestIdFromHeaderFallbackWhenBodyRequestIdBlank() {
        RequestInfo request = new RequestInfo("  ", null, null, null, null, null, null, null, null, null, null, null, null);
        AuditEvent event = new AuditEvent("TEST", null, null, request, null, null);

        service.logEvent(event, null, "header-id");

        assertEquals(1, listAppender.list.size());
        String message = listAppender.list.get(0).getFormattedMessage();
        assertTrue(message.contains("request_id=header-id"));
    }

    @Test
    void timestampFormatIsISO8601() {
        AuditEvent event = new AuditEvent("TEST", null, null, null, null, null);

        service.logEvent(event, null, null);

        assertEquals(1, listAppender.list.size());
        String message = listAppender.list.get(0).getFormattedMessage();
        // ISO-8601 format contains 'T' between date and time
        assertTrue(message.contains("_time="));
    }

    @Test
    void platformFieldsPresent() {
        AuditEvent event = new AuditEvent("TEST", null, null, null, null, null);

        service.logEvent(event, null, null);

        assertEquals(1, listAppender.list.size());
        String message = listAppender.list.get(0).getFormattedMessage();
        assertTrue(message.contains("app=myapp"));
        assertTrue(message.contains("platform=myplatform"));
        assertTrue(message.contains("environment=staging"));
        assertTrue(message.contains("hostname=myhost"));
    }

    @Test
    void exceptionSafety() {
        // Service should never throw, even with a null event
        assertDoesNotThrow(() -> service.logEvent(null, null, null));
    }

    // --- String truncation tests (Change 3) ---

    @Test
    void longUrlTruncatedTo2000() {
        String longUrl = "x".repeat(3000);
        RequestInfo request = new RequestInfo(null, "GET", longUrl, null, null, null, null, null, null, null, null, null, null);
        AuditEvent event = new AuditEvent("TEST", null, null, request, null, null);

        service.logEvent(event, null, null);

        assertEquals(1, listAppender.list.size());
        String message = listAppender.list.get(0).getFormattedMessage();
        // The truncated URL should be exactly 2000 chars of 'x'
        String expected = "x".repeat(2000);
        assertTrue(message.contains("url=" + expected));
        // Should NOT contain the full 3000-char value
        assertFalse(message.contains("x".repeat(2001)));
    }

    @Test
    void exact2000CharStringNotTruncated() {
        String exactUrl = "y".repeat(2000);
        RequestInfo request = new RequestInfo(null, "GET", exactUrl, null, null, null, null, null, null, null, null, null, null);
        AuditEvent event = new AuditEvent("TEST", null, null, request, null, null);

        service.logEvent(event, null, null);

        assertEquals(1, listAppender.list.size());
        String message = listAppender.list.get(0).getFormattedMessage();
        assertTrue(message.contains("url=" + exactUrl));
    }

    @Test
    void longRequestIdFromHeaderTruncated() {
        String longRequestId = "r".repeat(3000);
        AuditEvent event = new AuditEvent("TEST", null, null, null, null, null);

        service.logEvent(event, null, longRequestId);

        assertEquals(1, listAppender.list.size());
        String message = listAppender.list.get(0).getFormattedMessage();
        String expected = "r".repeat(2000);
        assertTrue(message.contains("request_id=" + expected));
        assertFalse(message.contains("r".repeat(2001)));
    }
}
