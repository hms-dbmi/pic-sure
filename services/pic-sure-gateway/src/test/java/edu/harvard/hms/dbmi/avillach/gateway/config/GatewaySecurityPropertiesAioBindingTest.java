package edu.harvard.hms.dbmi.avillach.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

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

    /**
     * Regression: this must NOT fall back to the empty string when {@code OPERATIONS_SERVICE_URL} is unset.
     * {@code QueryAuthFetcher} builds {@code {operationsServiceUrl}/operations/internal/queries/{id}/dispatch} to authorize the bodyless
     * {@code /query/{id}/result} and {@code /signed-url} reads. An empty base leaves a RELATIVE uri, and the
     * {@code IllegalArgumentException("URI with undefined scheme")} that follows is not a {@code RestClientException} -- so it escapes
     * {@code QueryAuthFetcher}'s fail-closed catches AND the gateway's {@code PicsureException}-only advice, surfacing as a bare Boot 500
     * on downloads while every other endpoint keeps working.
     */
    @Test
    void operationsServiceUrlDefaultsToAnAbsoluteUrlWhenTheEnvVarIsUnset() {
        assertThat(props.operationsServiceUrl()).isNotBlank();
        assertThat(URI.create(props.operationsServiceUrl()).isAbsolute()).isTrue();
        assertThat(props.operationsServiceUrl()).isEqualTo("http://pic-sure-operations-service:8080");
    }
}
