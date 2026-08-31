package edu.harvard.hms.dbmi.avillach.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Pins the AIO profile override: under the {@code aio} profile, PSAMA URLs resolve to the AIO Docker-network DNS names rather than the
 * empty base defaults.
 */
@SpringBootTest
@ActiveProfiles("aio")
class GatewaySecurityPropertiesAioBindingTest {

    @Autowired
    private GatewaySecurityProperties props;

    @Test
    void introspectionUrlResolvesToAioPsamaDns() {
        assertThat(props.introspectionUrl()).isEqualTo("http://psama:8090/auth/token/inspect");
    }

    @Test
    void openAccessValidateUrlResolvesToAioPsamaDns() {
        assertThat(props.openAccessValidateUrl()).isEqualTo("http://psama:8090/auth/open/validate");
    }

}
