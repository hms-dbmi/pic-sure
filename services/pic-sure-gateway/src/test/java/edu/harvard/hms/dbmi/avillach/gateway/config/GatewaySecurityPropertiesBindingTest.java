package edu.harvard.hms.dbmi.avillach.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Pins what {@code application.yml} binds with no environment overrides set. */
@SpringBootTest
class GatewaySecurityPropertiesBindingTest {

    @Autowired
    private GatewaySecurityProperties props;

    /** The open path is keyed on this list, so a missing entry sends tokened open-endpoint traffic to introspection. */
    @Test
    void openPathPrefixesBindToTheOpenHpdsIngress() {
        assertThat(props.openPathPrefixes()).containsExactly("/hpds/open");
    }

    @Test
    void authTimeoutsBindToTheirDefaultsWhenTheEnvVarsAreUnset() {
        assertThat(props.authConnectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(props.authReadTimeout()).isEqualTo(Duration.ofSeconds(10));
    }
}
