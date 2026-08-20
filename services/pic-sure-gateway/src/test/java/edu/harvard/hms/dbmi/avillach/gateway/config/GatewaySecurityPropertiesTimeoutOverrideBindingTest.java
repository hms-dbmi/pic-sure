package edu.harvard.hms.dbmi.avillach.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * A {@code GATEWAY_AUTH_READ_TIMEOUT} set in {@code gateway.env} must reach the properties record. Unbound, the env var does nothing and
 * the deploy runs at the default.
 */
@SpringBootTest(properties = {"GATEWAY_AUTH_CONNECT_TIMEOUT=5s", "GATEWAY_AUTH_READ_TIMEOUT=45s"})
class GatewaySecurityPropertiesTimeoutOverrideBindingTest {

    @Autowired
    private GatewaySecurityProperties props;

    @Test
    void environmentOverridesReplaceTheDefaultAuthTimeouts() {
        assertThat(props.authConnectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(props.authReadTimeout()).isEqualTo(Duration.ofSeconds(45));
    }
}
