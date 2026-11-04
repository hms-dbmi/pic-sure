package edu.harvard.hms.dbmi.avillach.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import edu.harvard.hms.dbmi.avillach.gateway.auth.PsamaClient;

/**
 * Pins the auth-boundary PSAMA client timeouts.
 */
class SecurityConfigTest {

    @Test
    void authRequestFactorySettingsHaveBoundedConnectAndReadTimeouts() {
        assertThat(SecurityConfig.AUTH_REQUEST_FACTORY_SETTINGS.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(SecurityConfig.AUTH_REQUEST_FACTORY_SETTINGS.readTimeout()).isEqualTo(Duration.ofSeconds(10));
        // Both must be non-zero/non-null -- a zero or absent timeout means "wait forever" for the underlying HTTP client.
        assertThat(SecurityConfig.AUTH_CONNECT_TIMEOUT).isPositive();
        assertThat(SecurityConfig.AUTH_READ_TIMEOUT).isPositive();
    }

    @Test
    void psamaClientBuildsSuccessfullyWithTimeoutBoundedClient() {
        SecurityConfig config = new SecurityConfig();
        GatewaySecurityProperties props = new GatewaySecurityProperties(
            List.of(), false, 1024, "http://psama.local/introspect", "http://psama.local/open-access", "svc-token"
        );

        assertThat(config.psamaClient(props)).isNotNull();
    }
}
