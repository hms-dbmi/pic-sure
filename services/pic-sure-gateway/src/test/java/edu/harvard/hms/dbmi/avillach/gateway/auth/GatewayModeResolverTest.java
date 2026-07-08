package edu.harvard.hms.dbmi.avillach.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * The resolver is the single source of truth for the effective mode and the per-request enforce decision. Covers the full
 * {@code (auth-enabled, mode)} resolution matrix, including the unusual tuple and the WARN it emits.
 */
class GatewayModeResolverTest {

    // ---- resolution matrix: explicit mode always wins; when unset, auth-enabled drives it ----

    @Test
    void explicitModeAlwaysWinsRegardlessOfAuthEnabled() {
        assertThat(GatewayModeResolver.resolve(GatewayAuthMode.ENFORCE, true)).isEqualTo(GatewayAuthMode.ENFORCE);
        assertThat(GatewayModeResolver.resolve(GatewayAuthMode.ENFORCE, false)).isEqualTo(GatewayAuthMode.ENFORCE);
        assertThat(GatewayModeResolver.resolve(GatewayAuthMode.TRANSPARENT, true)).isEqualTo(GatewayAuthMode.TRANSPARENT);
        assertThat(GatewayModeResolver.resolve(GatewayAuthMode.TRANSPARENT, false)).isEqualTo(GatewayAuthMode.TRANSPARENT);
    }

    @Test
    void unsetModeDerivesFromAuthEnabled() {
        // today's production topology (auth-enabled=true, mode unset) MUST resolve to ENFORCE
        assertThat(GatewayModeResolver.resolve(null, true)).isEqualTo(GatewayAuthMode.ENFORCE);
        assertThat(GatewayModeResolver.resolve(null, false)).isEqualTo(GatewayAuthMode.TRANSPARENT);
    }

    // ---- unusual tuples ----

    @Test
    void unusualTuplesAreExactlyEnforceWithoutAuth() {
        assertThat(GatewayModeResolver.isUnusualTuple(GatewayAuthMode.ENFORCE, false)).isTrue();

        // everything else is a normal tuple
        assertThat(GatewayModeResolver.isUnusualTuple(GatewayAuthMode.ENFORCE, true)).isFalse();
        assertThat(GatewayModeResolver.isUnusualTuple(GatewayAuthMode.TRANSPARENT, true)).isFalse();
        assertThat(GatewayModeResolver.isUnusualTuple(null, true)).isFalse();
        assertThat(GatewayModeResolver.isUnusualTuple(null, false)).isFalse();
    }

    @Test
    void logsWarnForEnforceWithAuthDisabled() {
        List<ILoggingEvent> events =
            captureLogsDuring(() -> GatewayModeResolver.logResolution(GatewayAuthMode.ENFORCE, false, GatewayAuthMode.ENFORCE));
        assertThat(events).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains("ENFORCE").contains("UNUSUAL").contains("auth-enabled=false")
                .contains("mode=enforce");
        });
    }

    @Test
    void logsInfoNotWarnForNormalProductionTuple() {
        List<ILoggingEvent> events = captureLogsDuring(() -> GatewayModeResolver.logResolution(null, true, GatewayAuthMode.ENFORCE));
        assertThat(events).noneMatch(e -> e.getLevel() == Level.WARN);
        assertThat(events).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.INFO);
            assertThat(e.getFormattedMessage()).contains("ENFORCE").contains("auth-enabled=true").contains("mode=<unset>");
        });
    }

    // ---- per-request decision ----

    @Test
    void enforceModeEnforcesEveryRoute() {
        GatewayModeResolver r = new GatewayModeResolver(GatewayAuthMode.ENFORCE);
        assertThat(r.enforcesFor("/hpds/auth/v3/query/sync")).isTrue();
        assertThat(r.enforcesFor("/picsure/query/sync")).isTrue(); // any path is enforced in ENFORCE mode
    }

    @Test
    void transparentModeNeverEnforces() {
        GatewayModeResolver r = new GatewayModeResolver(GatewayAuthMode.TRANSPARENT);
        assertThat(r.enforcesFor("/picsure/query/sync")).isFalse();
        assertThat(r.enforcesFor("/hpds/x")).isFalse();
    }

    private static List<ILoggingEvent> captureLogsDuring(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(GatewayModeResolver.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list;
    }
}
