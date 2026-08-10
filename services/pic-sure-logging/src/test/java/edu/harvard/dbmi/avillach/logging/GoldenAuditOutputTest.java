package edu.harvard.dbmi.avillach.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.logging.config.JwtClaimMappingConverter;
import edu.harvard.dbmi.avillach.logging.config.LoggingProperties;
import edu.harvard.dbmi.avillach.logging.model.AuditEvent;
import edu.harvard.dbmi.avillach.logging.model.RequestInfo;
import edu.harvard.dbmi.avillach.logging.service.AuditLogService;
import edu.harvard.dbmi.avillach.logging.service.JwtDecodeService;
import net.logstash.logback.encoder.LogstashEncoder;
import net.logstash.logback.fieldnames.LogstashFieldNames;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the exact audit JSON emitted to stdout. Splunk field extractions depend on it. This test must survive the Javalin -> Spring Boot
 * rewrite unchanged apart from the config type it constructs.
 */
class GoldenAuditOutputTest {

    private static final String IGNORE = "[ignore]";

    private AuditLogService service;
    private ListAppender<ILoggingEvent> listAppender;
    private Logger auditLogger;
    private LogstashEncoder encoder;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        LoggingProperties config = new LoggingProperties(
            "test-key", "myapp", "myplatform", "staging", "myhost", "*",
            Map.of("sub", "subject", "email", "user_email", "roles", "roles", "logged_in", "logged_in")
        );
        service = new AuditLogService(config, new JwtDecodeService(config.jwtClaimMapping()));

        auditLogger = (Logger) LoggerFactory.getLogger("AUDIT");
        listAppender = new ListAppender<>();
        listAppender.start();
        auditLogger.addAppender(listAppender);

        // Mirror the AUDIT_JSON appender in logback config exactly.
        LogstashFieldNames names = new LogstashFieldNames();
        names.setVersion(IGNORE);
        names.setLevelValue(IGNORE);
        names.setThread(IGNORE);
        names.setLevel(IGNORE);
        names.setLogger(IGNORE);
        names.setMessage(IGNORE);
        names.setTimestamp(IGNORE);

        encoder = new LogstashEncoder();
        encoder.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        encoder.setFieldNames(names);
        encoder.start();
    }

    @AfterEach
    void tearDown() {
        encoder.stop();
        auditLogger.detachAppender(listAppender);
    }

    /** Encodes the single captured audit event exactly as the stdout appender would. */
    private JsonNode encodeSoleEvent() throws Exception {
        assertThat(listAppender.list).hasSize(1);
        byte[] encoded = encoder.encode(listAppender.list.get(0));
        return mapper.readTree(new String(encoded, StandardCharsets.UTF_8));
    }

    @Test
    void noJwtEmitsLoggedInFalseAndNoUserFields() throws Exception {
        service.logEvent(new AuditEvent("LOGIN", null, null, null, null, null, null, null), null, null);

        JsonNode json = encodeSoleEvent();

        assertThat(json.get("event_type").asText()).isEqualTo("LOGIN");
        assertThat(json.get("logged_in").isBoolean()).isTrue();
        assertThat(json.get("logged_in").asBoolean()).isFalse();
        assertThat(json.has("subject")).isFalse();
        assertThat(json.has("user_email")).isFalse();
        assertThat(json.has("request_id")).isFalse();
        assertThat(json.has("metadata")).isFalse();
        assertThat(json.has("error")).isFalse();
        // Platform fields always present.
        assertThat(json.get("app").asText()).isEqualTo("myapp");
        assertThat(json.get("platform").asText()).isEqualTo("myplatform");
        assertThat(json.get("environment").asText()).isEqualTo("staging");
        assertThat(json.get("hostname").asText()).isEqualTo("myhost");
        // Suppressed logback fields must never appear.
        assertThat(json.has("level")).isFalse();
        assertThat(json.has("logger_name")).isFalse();
        assertThat(json.has("message")).isFalse();
        assertThat(json.has("@timestamp")).isFalse();
        assertThat(json.has("@version")).isFalse();
    }

    @Test
    void rolesStaysJsonArrayAndLoggedInStaysBoolean() throws Exception {
        String token = TestJwtBuilder
            .buildToken(Map.of("sub", "user123", "email", "user@example.com", "roles", List.of("ADMIN", "USER"), "logged_in", true));

        service.logEvent(new AuditEvent("QUERY", null, null, null, null, null, null, null), "Bearer " + token, null);

        JsonNode json = encodeSoleEvent();

        assertThat(json.get("subject").asText()).isEqualTo("user123");
        assertThat(json.get("user_email").asText()).isEqualTo("user@example.com");
        assertThat(json.get("roles").isArray()).isTrue();
        assertThat(json.get("roles")).hasSize(2);
        assertThat(json.get("roles").get(0).asText()).isEqualTo("ADMIN");
        assertThat(json.get("logged_in").isBoolean()).isTrue();
        assertThat(json.get("logged_in").asBoolean()).isTrue();
    }

    @Test
    void customJwtClaimsFollowConfiguredOrderInEncodedAuditJson() throws Exception {
        Map<String, String> claimMapping = new JwtClaimMappingConverter()
            .convert("{\"claim_c\":\"custom_c\",\"claim_a\":\"custom_a\",\"claim_d\":\"custom_d\",\"claim_b\":\"custom_b\"}");
        LoggingProperties config = new LoggingProperties("test-key", "myapp", "myplatform", "staging", "myhost", "*", claimMapping);
        service = new AuditLogService(config, new JwtDecodeService(config.jwtClaimMapping()));
        String token = TestJwtBuilder.buildToken(Map.of("claim_a", "a", "claim_b", "b", "claim_c", "c", "claim_d", "d"));

        service.logEvent(new AuditEvent("QUERY", null, null, null, null, null, null, null), "Bearer " + token, null);

        JsonNode json = encodeSoleEvent();
        List<String> fieldNames = new ArrayList<>();
        json.fieldNames().forEachRemaining(fieldNames::add);
        assertThat(fieldNames).containsSubsequence("custom_c", "custom_a", "custom_d", "custom_b");
    }

    @Test
    void malformedJwtDegradesToLoggedInFalse() throws Exception {
        service.logEvent(new AuditEvent("QUERY", null, null, null, null, null, null, null), "Bearer not-a-jwt", null);

        JsonNode json = encodeSoleEvent();

        assertThat(json.get("logged_in").asBoolean()).isFalse();
        assertThat(json.has("subject")).isFalse();
    }

    @Test
    void requestFieldsAreFlattenedToTopLevelAndTypesPreserved() throws Exception {
        RequestInfo request = new RequestInfo(
            "req-123", "POST", "/api/query", "limit=10", "192.168.1.1", "10.0.0.5", 8443, "Mozilla/5.0", "application/json", 200, 1024L,
            150L, "https://example.com"
        );
        service.logEvent(new AuditEvent("QUERY", "execute", "web", null, null, request, null, null), null, null);

        JsonNode json = encodeSoleEvent();

        assertThat(json.has("request")).isFalse(); // flattened, never nested
        assertThat(json.get("request_id").asText()).isEqualTo("req-123");
        assertThat(json.get("method").asText()).isEqualTo("POST");
        assertThat(json.get("url").asText()).isEqualTo("/api/query");
        assertThat(json.get("query_string").asText()).isEqualTo("limit=10");
        assertThat(json.get("src_ip").asText()).isEqualTo("192.168.1.1");
        assertThat(json.get("dest_ip").asText()).isEqualTo("10.0.0.5");
        assertThat(json.get("dest_port").isNumber()).isTrue();
        assertThat(json.get("dest_port").asInt()).isEqualTo(8443);
        assertThat(json.get("http_user_agent").asText()).isEqualTo("Mozilla/5.0");
        assertThat(json.get("http_content_type").asText()).isEqualTo("application/json");
        assertThat(json.get("status").isNumber()).isTrue();
        assertThat(json.get("status").asInt()).isEqualTo(200);
        assertThat(json.get("bytes").asLong()).isEqualTo(1024L);
        assertThat(json.get("duration").asLong()).isEqualTo(150L);
        assertThat(json.get("referrer").asText()).isEqualTo("https://example.com");
        assertThat(json.get("client_type").asText()).isEqualTo("web");
    }

    @Test
    void requestIdFallsBackToHeaderThenIsOmitted() throws Exception {
        service.logEvent(new AuditEvent("TEST", null, null, null, null, null, null, null), null, "hdr-1");
        assertThat(encodeSoleEvent().get("request_id").asText()).isEqualTo("hdr-1");

        listAppender.list.clear();
        service.logEvent(new AuditEvent("TEST", null, null, null, null, null, null, null), null, null);
        assertThat(encodeSoleEvent().has("request_id")).isFalse();
    }

    @Test
    void sessionIdPromotedFromMetadataAndStrippedFromIt() throws Exception {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("session_id", "sid-1");
        metadata.put("dataset", "phs000001");
        service.logEvent(new AuditEvent("TEST", null, null, null, null, null, metadata, null), null, null);

        JsonNode json = encodeSoleEvent();

        assertThat(json.get("session_id").asText()).isEqualTo("sid-1");
        assertThat(json.get("metadata").has("session_id")).isFalse();
        assertThat(json.get("metadata").get("dataset").asText()).isEqualTo("phs000001");
    }

    @Test
    void metadataOmittedEntirelyWhenOnlySessionIdPresent() throws Exception {
        service.logEvent(new AuditEvent("TEST", null, null, null, null, null, Map.of("session_id", "sid-only"), null), null, null);

        JsonNode json = encodeSoleEvent();

        assertThat(json.get("session_id").asText()).isEqualTo("sid-only");
        assertThat(json.has("metadata")).isFalse();
    }

    @Test
    void stringsTruncatedAtExactly2000Chars() throws Exception {
        RequestInfo request = new RequestInfo(null, null, "x".repeat(3000), null, null, null, null, null, null, null, null, null, null);
        service.logEvent(new AuditEvent("TEST", null, null, null, null, request, null, null), null, null);

        assertThat(encodeSoleEvent().get("url").asText()).hasSize(2000);
    }

    @Test
    void timeIsIso8601AndFirstKeyInInsertionOrder() throws Exception {
        service.logEvent(new AuditEvent("TEST", null, null, null, null, null, null, null), null, null);

        JsonNode json = encodeSoleEvent();

        assertThat(json.get("_time").asText()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z");
        assertThat(json.fieldNames().next()).isEqualTo("_time");
    }

    @Test
    void callerAndErrorMapArePreserved() throws Exception {
        service.logEvent(
            new AuditEvent("TEST", null, null, "PYTHON_ADAPTER", null, null, null, Map.of("origin", "psama", "message", "Internal error")),
            null, null
        );

        JsonNode json = encodeSoleEvent();

        assertThat(json.get("caller").asText()).isEqualTo("PYTHON_ADAPTER");
        assertThat(json.get("error").get("origin").asText()).isEqualTo("psama");
        assertThat(json.get("error").get("message").asText()).isEqualTo("Internal error");
    }
}
