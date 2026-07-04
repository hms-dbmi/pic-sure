package edu.harvard.hms.dbmi.avillach.gateway.shadow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Task 1: token hashing is a stable, reversal-safe SHA-256 hex digest; {@link ShadowRecord} serializes to the plan's Global-Constraints
 * schema verbatim (field names AND order -- the reconciler parses this shape); {@link ShadowSupport#emit} writes exactly one minified JSON
 * line to the {@code picsure.shadow} logger at INFO.
 */
class ShadowSupportTest {

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger shadowLogger = (Logger) LoggerFactory.getLogger("picsure.shadow");

    @BeforeEach
    void attachAppender() {
        appender.start();
        shadowLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        shadowLogger.detachAppender(appender);
    }

    @Test
    void tokenHashIsLowercaseSha256HexOfCredential() {
        // SHA-256("abc") known vector.
        assertThat(ShadowSupport.tokenHash("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void tokenHashOfNullOrBlankIsNull() {
        assertNull(ShadowSupport.tokenHash(null));
        assertNull(ShadowSupport.tokenHash(""));
    }

    @Test
    void tokenHashIsStableAndDeterministicAcrossCalls() {
        assertThat(ShadowSupport.tokenHash("same-token")).isEqualTo(ShadowSupport.tokenHash("same-token"));
    }

    @Test
    void tokenHashDiffersForDifferentCredentials() {
        assertThat(ShadowSupport.tokenHash("token-a")).isNotEqualTo(ShadowSupport.tokenHash("token-b"));
    }

    @Test
    void introspectionRecordSerializesToSchemaShapeInFieldOrder() throws Exception {
        ShadowRecord r = ShadowRecord.gwIntrospection("cid-1", "deadbeef", "/picsure/query/sync", Map.of("k", "v"));

        String json = new ObjectMapper().writeValueAsString(r);

        assertThat(json).isEqualTo(
            "{\"side\":\"GW\",\"correlationId\":\"cid-1\",\"channel\":\"introspection\",\"tokenHash\":\"deadbeef\","
                + "\"targetService\":\"/picsure/query/sync\",\"query\":{\"k\":\"v\"},\"formattedQueryPresent\":false,"
                + "\"ipAddress\":null,\"decision\":null}"
        );
    }

    @Test
    void openAccessRecordCarriesIpAddressAndAlwaysNullDecision() throws Exception {
        ShadowRecord r = ShadowRecord.gwOpenAccess("cid-2", null, "/picsure/query/sync", null, "OPEN_ACCESS:localhost");

        String json = new ObjectMapper().writeValueAsString(r);

        assertThat(json).contains("\"side\":\"GW\"").contains("\"channel\":\"open-access\"")
            .contains("\"ipAddress\":\"OPEN_ACCESS:localhost\"").contains("\"decision\":null").contains("\"tokenHash\":null")
            .contains("\"query\":null").contains("\"formattedQueryPresent\":false");
    }

    @Test
    void emitWritesExactlyOneMinifiedJsonLineToPicsureShadowLoggerAtInfo() {
        ShadowRecord r = ShadowRecord.gwIntrospection("cid-3", "deadbeef", "/picsure/query/sync", Map.of("k", "v"));

        ShadowSupport.emit(r);

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel().toString()).isEqualTo("INFO");
        assertThat(event.getLoggerName()).isEqualTo("picsure.shadow");
        String line = event.getFormattedMessage();
        assertThat(line).doesNotContain("\n").isEqualTo(
            "{\"side\":\"GW\",\"correlationId\":\"cid-3\",\"channel\":\"introspection\",\"tokenHash\":\"deadbeef\","
                + "\"targetService\":\"/picsure/query/sync\",\"query\":{\"k\":\"v\"},\"formattedQueryPresent\":false,"
                + "\"ipAddress\":null,\"decision\":null}"
        );
    }

    @Test
    void emitSuppressesGatewaySelfServedActuatorPaths() {
        // I8: an OBSERVE catch-all request to a gateway-self-served /actuator probe would build a record WildFly never pairs.
        ShadowSupport.emit(ShadowRecord.gwOpenAccess("cid-a", null, "/actuator/health/liveness", null, "OPEN_ACCESS:host"));
        ShadowSupport.emit(ShadowRecord.gwOpenAccess("cid-m", null, "/actuator/prometheus", null, "OPEN_ACCESS:host"));

        assertThat(appender.list).isEmpty();
    }

    @Test
    void emitSuppressesGatewaySelfServedDocPaths() {
        ShadowSupport.emit(ShadowRecord.gwOpenAccess("cid-o", null, "/openapi.json", null, "OPEN_ACCESS:host"));
        ShadowSupport.emit(ShadowRecord.gwIntrospection("cid-s", "h", "/swagger-ui/index.html", Map.of()));

        assertThat(appender.list).isEmpty();
    }

    @Test
    void emitStillEmitsForCatchAllPathsThatLookSimilar() {
        // Segment-safe: a different route whose name merely starts with the guarded token still emits.
        ShadowSupport.emit(ShadowRecord.gwIntrospection("cid-x", "h", "/actuatorial/query", Map.of("k", "v")));

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("\"correlationId\":\"cid-x\"");
    }

    @Test
    void isGatewaySelfServedSegmentSafety() {
        assertThat(ShadowSupport.isGatewaySelfServed("/actuator")).isTrue();
        assertThat(ShadowSupport.isGatewaySelfServed("/actuator/health/liveness")).isTrue();
        assertThat(ShadowSupport.isGatewaySelfServed("/swagger-ui/index.html")).isTrue();
        assertThat(ShadowSupport.isGatewaySelfServed("/openapi.json")).isTrue();
        assertThat(ShadowSupport.isGatewaySelfServed("/v3/openapi.json")).isTrue();
        // Not self-served: real catch-all traffic and look-alike routes.
        assertThat(ShadowSupport.isGatewaySelfServed("/picsure/query/sync")).isFalse();
        assertThat(ShadowSupport.isGatewaySelfServed("/actuatorial/query")).isFalse();
        assertThat(ShadowSupport.isGatewaySelfServed("/swagger-ui-custom")).isFalse();
        assertThat(ShadowSupport.isGatewaySelfServed(null)).isFalse();
    }
}
