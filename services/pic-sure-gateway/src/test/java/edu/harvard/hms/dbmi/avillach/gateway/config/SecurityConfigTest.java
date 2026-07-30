package edu.harvard.hms.dbmi.avillach.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;

import edu.harvard.dbmi.avillach.contracts.auth.IntrospectionRequest;
import edu.harvard.dbmi.avillach.contracts.auth.TargetedRequest;
import edu.harvard.hms.dbmi.avillach.gateway.auth.PsamaClient;
import edu.harvard.hms.dbmi.avillach.gateway.auth.QueryAuthFetcher;

/**
 * Pins FIX 2: the auth-boundary RestClients ({@link PsamaClient}, {@link QueryAuthFetcher}) must never be built with unbounded connect/read
 * timeouts -- a hung PSAMA or query-service response must not stall the synchronous auth filter chain / a Tomcat worker indefinitely.
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

    /**
     * The gateway declares its own {@code ObjectMapper}, which suppresses Boot's auto-configured one and with it commons'
     * {@code StrictWebDeserializationConfig} customizer. That is deliberate (see the bean's comment) and only safe while the bare mapper is
     * strict on its own. Jackson's default is strict; this pins it, so a future {@code configure(...)} on that bean cannot quietly loosen
     * the gateway alone.
     */
    @Test
    void gatewayObjectMapperRejectsUnknownPropertiesWithoutBootsCustomizer() {
        ObjectMapper mapper = new SecurityConfig().objectMapper();

        assertThat(mapper.getDeserializationConfig().isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isTrue();
    }

    /**
     * SECURITY: deployed FISMA access rules are JsonPath strings evaluated against the introspection payload. The contracts module pins the
     * record's shape against a bare mapper; this pins it against the mapper the GATEWAY actually injects into
     * {@code PsamaIntrospectionFilter}, so a gateway-local Jackson change (naming strategy, feature flip) cannot silently move the rules'
     * anchor points. The wire-level counterpart lives in {@code PsamaClientTest}.
     */
    @Test
    void introspectionPayloadStillResolvesDeployedRulesUnderTheGatewaysOwnMapper() throws Exception {
        ObjectMapper mapper = new SecurityConfig().objectMapper();

        String json = mapper.writeValueAsString(
            new IntrospectionRequest(
                "tok", new TargetedRequest("/hpds/auth/v3/query", mapper.readTree("{\"expectedResultType\":\"COUNT\"}"))
            )
        );

        assertThat(JsonPath.<String>read(json, "$.request.['Target Service']")).isEqualTo("/hpds/auth/v3/query");
        assertThat(JsonPath.<String>read(json, "$.request.query.expectedResultType")).isEqualTo("COUNT");
        assertThat(json).contains("\"Target Service\"").doesNotContain("targetService");
    }

    @Test
    void psamaClientAndQueryAuthFetcherBeansBuildSuccessfullyWithTimeoutBoundedClients() {
        SecurityConfig config = new SecurityConfig();
        GatewaySecurityProperties props = new GatewaySecurityProperties(
            List.of(), false, "userId", 1024, "http://psama.local/introspect", "http://psama.local/open-access", "svc-token",
            "http://operations.local", "internal-token"
        );

        assertThat(config.psamaClient(props)).isNotNull();
        assertThat(config.queryAuthFetcher(props)).isNotNull();
    }
}
