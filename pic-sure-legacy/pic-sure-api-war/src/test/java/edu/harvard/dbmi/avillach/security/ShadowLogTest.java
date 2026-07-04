package edu.harvard.dbmi.avillach.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Task 7/8: {@link ShadowLog} is the WildFly ({@code side=WF}) half of the gateway-parity shadow-logging pipeline. It must:
 * <ul>
 * <li>hash bearer tokens byte-identically to the gateway's {@code ShadowSupport.tokenHash} (same SHA-256("abc") vector);</li>
 * <li>render {@code SHADOW_WF} lines matching the plan's Global-Constraints schema verbatim (field names AND order);</li>
 * <li>emit exactly one line per call to the {@code picsure.shadow} logger when {@code PICSURE_SHADOW_LOGGING} is enabled, and zero lines
 * (zero behavior change) when disabled.</li>
 * </ul>
 * The SLF4J binding on this module (Java 11, {@code slf4j-jdk14}) routes straight through to {@code java.util.logging}, so tests attach a
 * {@link Handler} to the JUL logger named {@code picsure.shadow} to observe emitted records instead of a logback ListAppender.
 */
public class ShadowLogTest {

    private final List<LogRecord> captured = new ArrayList<>();
    private java.util.logging.Logger shadowJulLogger;
    private Handler captureHandler;

    @Before
    public void attachCaptureHandler() {
        shadowJulLogger = java.util.logging.Logger.getLogger("picsure.shadow");
        shadowJulLogger.setLevel(java.util.logging.Level.ALL);
        captureHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                captured.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        shadowJulLogger.addHandler(captureHandler);
    }

    @After
    public void detachCaptureHandlerAndResetFlag() {
        shadowJulLogger.removeHandler(captureHandler);
        ShadowLog.setEnabledForTest(null);
    }

    // ---- tokenHash: must be byte-identical to gateway ShadowSupport.tokenHash ----

    @Test
    public void tokenHashMatchesGatewayVector() {
        // identical SHA-256("abc") vector as gateway ShadowSupportTest
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ShadowLog.tokenHash("abc"));
    }

    @Test
    public void tokenHashOfNullOrEmptyIsNull() {
        assertNull(ShadowLog.tokenHash(null));
        assertNull(ShadowLog.tokenHash(""));
    }

    // ---- renderIntrospection / renderOpenAccess: pure schema-shape checks ----

    @Test
    public void introspectionLineHasWfShapeAndDecision() {
        String line = ShadowLog.renderIntrospection("cid-7", "deadbeef", "/v3/query/sync", Map.of("k", "v"), true);
        assertTrue(line.contains("\"side\":\"WF\""));
        assertTrue(line.contains("\"channel\":\"introspection\""));
        assertTrue(line.contains("\"targetService\":\"/v3/query/sync\""));
        assertTrue(line.contains("\"decision\":\"active\""));
    }

    @Test
    public void introspectionLineFieldOrderMatchesSchemaVerbatim() {
        String line = ShadowLog.renderIntrospection("cid-1", "deadbeef", "/picsure/query/sync", Map.of("k", "v"), false);

        assertEquals(
            "{\"side\":\"WF\",\"correlationId\":\"cid-1\",\"channel\":\"introspection\",\"tokenHash\":\"deadbeef\","
                + "\"targetService\":\"/picsure/query/sync\",\"query\":{\"k\":\"v\"},\"formattedQueryPresent\":false,"
                + "\"ipAddress\":null,\"decision\":\"inactive\"}",
            line
        );
    }

    @Test
    public void openAccessLineHasShapeAndDecision() {
        String line = ShadowLog.renderOpenAccess("cid-8", "/picsure/query/sync", Map.of(), "OPEN_ACCESS:host", false);
        assertTrue(line.contains("\"channel\":\"open-access\""));
        assertTrue(line.contains("\"decision\":\"deny\""));
        assertTrue(line.contains("\"ipAddress\":\"OPEN_ACCESS:host\""));
    }

    @Test
    public void openAccessLineTokenHashIsAlwaysNull() {
        String line = ShadowLog.renderOpenAccess("cid-9", "/picsure/query/sync", null, "OPEN_ACCESS:host", true);
        assertTrue(line.contains("\"tokenHash\":null"));
        assertTrue(line.contains("\"decision\":\"allow\""));
    }

    // ---- Flag gating: OFF must be a complete no-op; ON must emit exactly one line ----

    @Test
    public void emitIntrospectionWritesNothingWhenFlagDisabled() {
        ShadowLog.setEnabledForTest(false);

        ShadowLog.emitIntrospection("cid-off", ShadowLog.tokenHash("some-token"), "/query/sync", Map.of("q", 1), true);

        assertTrue(captured.isEmpty());
    }

    @Test
    public void emitIntrospectionWritesExactlyOneLineWithCorrelationIdAndMatchingTokenHashWhenFlagEnabled() {
        ShadowLog.setEnabledForTest(true);

        ShadowLog.emitIntrospection("cid-on-1", ShadowLog.tokenHash("USER_TOKEN"), "/query/sync", Map.of("q", 1), true);

        assertEquals(1, captured.size());
        String line = captured.get(0).getMessage();
        assertTrue(line.contains("\"side\":\"WF\""));
        assertTrue(line.contains("\"correlationId\":\"cid-on-1\""));
        assertTrue(line.contains("\"channel\":\"introspection\""));
        // Same algorithm as the gateway -- the two sides' hashes for the same bearer token must be comparable.
        assertTrue(line.contains("\"tokenHash\":\"" + ShadowLog.tokenHash("USER_TOKEN") + "\""));
        assertTrue(line.contains("\"decision\":\"active\""));
    }

    @Test
    public void emitOpenAccessWritesNothingWhenFlagDisabled() {
        ShadowLog.setEnabledForTest(false);

        ShadowLog.emitOpenAccess("cid-off-oa", "/query/sync", Map.of(), "OPEN_ACCESS:host", true);

        assertTrue(captured.isEmpty());
    }

    @Test
    public void emitOpenAccessWritesExactlyOneLineWhenFlagEnabled() {
        ShadowLog.setEnabledForTest(true);

        ShadowLog.emitOpenAccess("cid-on-2", "/query/sync", Map.of(), "OPEN_ACCESS:host", false);

        assertEquals(1, captured.size());
        String line = captured.get(0).getMessage();
        assertTrue(line.contains("\"correlationId\":\"cid-on-2\""));
        assertTrue(line.contains("\"channel\":\"open-access\""));
        assertTrue(line.contains("\"decision\":\"deny\""));
        assertTrue(line.contains("\"tokenHash\":null"));
    }

    @Test
    public void enabledReflectsTestOverride() {
        ShadowLog.setEnabledForTest(true);
        assertTrue(ShadowLog.enabled());
        ShadowLog.setEnabledForTest(false);
        assertFalse(ShadowLog.enabled());
    }
}
