package edu.harvard.hms.dbmi.avillach.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import edu.harvard.hms.dbmi.avillach.gateway.auth.PsamaClient;

/**
 * Pins the auth-boundary PSAMA client timeouts. The bounds are configurable, so this pins that the client reads them from configuration and
 * that the default stays bounded.
 */
class SecurityConfigTest {

    @Test
    void authRequestFactorySettingsHaveBoundedConnectAndReadTimeoutsByDefault() {
        var settings = SecurityConfig.authRequestFactorySettings(props(null, null));

        assertThat(settings.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(settings.readTimeout()).isEqualTo(Duration.ofSeconds(10));
        // Both must be non-zero/non-null. A zero or absent timeout means "wait forever" for the underlying HTTP client.
        assertThat(settings.connectTimeout()).isPositive();
        assertThat(settings.readTimeout()).isPositive();
    }

    @Test
    void authRequestFactorySettingsTakeTheirBoundsFromConfiguration() {
        var settings = SecurityConfig.authRequestFactorySettings(props(Duration.ofSeconds(5), Duration.ofSeconds(45)));

        assertThat(settings.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(settings.readTimeout()).isEqualTo(Duration.ofSeconds(45));
    }

    @Test
    void psamaClientBuildsSuccessfullyWithTimeoutBoundedClient() {
        SecurityConfig config = new SecurityConfig();

        assertThat(config.psamaClient(props(null, null))).isNotNull();
    }

    private static GatewaySecurityProperties props(Duration connect, Duration read) {
        return new GatewaySecurityProperties(
            List.of(), List.of("/hpds/open"), false, 1024, "http://psama.local/introspect", "http://psama.local/open-access", "svc-token",
            connect, read
        );
    }
}
