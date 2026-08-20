package edu.harvard.hms.dbmi.avillach.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Pins the compact-constructor defaults for the auth-boundary timeouts. An unset or non-positive value must resolve to the documented
 * default. A null or zero timeout means "wait forever" to the underlying HTTP client.
 */
class GatewaySecurityPropertiesTest {

    private static GatewaySecurityProperties props(Duration connect, Duration read) {
        return new GatewaySecurityProperties(
            List.of(), List.of("/hpds/open"), false, 1024, "http://psama.local/introspect", "http://psama.local/open-access", "svc-token",
            "http://operations.local", "internal-token", connect, read
        );
    }

    @Test
    void unsetAuthTimeoutsResolveToTheDocumentedDefaults() {
        GatewaySecurityProperties props = props(null, null);

        assertThat(props.authConnectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(props.authReadTimeout()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void configuredAuthTimeoutsAreKeptVerbatim() {
        GatewaySecurityProperties props = props(Duration.ofSeconds(5), Duration.ofSeconds(45));

        assertThat(props.authConnectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(props.authReadTimeout()).isEqualTo(Duration.ofSeconds(45));
    }

    @Test
    void nonPositiveAuthTimeoutsFallBackToTheDefaultsRatherThanMeaningWaitForever() {
        GatewaySecurityProperties props = props(Duration.ZERO, Duration.ofSeconds(-1));

        assertThat(props.authConnectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(props.authReadTimeout()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void openPathPrefixesDefaultToEmptyWhenUnset() {
        GatewaySecurityProperties props =
            new GatewaySecurityProperties(null, null, false, 1024, "", "", "", "http://operations.local", "", null, null);

        assertThat(props.openPathPrefixes()).isEmpty();
        assertThat(props.allowListPrefixes()).isEmpty();
    }
}
