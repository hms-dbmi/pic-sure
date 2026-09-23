package edu.harvard.hms.dbmi.avillach.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import edu.harvard.hms.dbmi.avillach.gateway.auth.PublicEndpointPolicy;
import edu.harvard.hms.dbmi.avillach.gateway.auth.ShippedPublicRoutes;

/**
 * Binds the shipped {@code application.yml} with no overrides and checks the wired {@link PublicEndpointPolicy} makes the same decisions
 * {@code PublicEndpointPolicyTest} pins against {@link ShippedPublicRoutes}. Deleting or loosening a {@code public-routes} entry fails
 * here; a malformed entry fails the context before any test runs.
 */
@SpringBootTest
class PublicRoutesBindingTest {

    @Autowired
    private GatewaySecurityProperties props;

    @Autowired
    private PublicEndpointPolicy policy;

    @Test
    void shippedPublicRoutesBindToTheSameEntriesThePolicyTestPins() {
        assertThat(props.publicRoutes()).containsExactlyElementsOf(ShippedPublicRoutes.routes());
    }

    @ParameterizedTest
    @CsvSource(
        {"GET, /system/status", "GET, /openapi.json", "POST, /gateway/openapi.json", "GET, /logging", "POST, /logging/audit",
            "GET, /operations/configuration", "GET, /operations/configuration/", "GET, /operations/configuration/abc-123",
            "GET, /operations/configuration/abc-123/"}
    )
    void boundRoutesKeepEachPublicRoutePublic(String method, String path) {
        assertThat(policy.evaluate(method, path).publicEndpoint()).isTrue();
    }

    @ParameterizedTest
    @CsvSource(
        {"POST, /system/status", "GET, /v3/system/status", "GET, /foo/system/status", "GET, /loggingAdmin/x",
            "GET, /operations/configuration/admin", "GET, /operations/configuration/admin/x", "POST, /operations/configuration",
            "POST, /operations/configuration/abc-123", "GET, /operations/dataset/named/abc-123"}
    )
    void boundRoutesKeepEachAdjacentRouteProtected(String method, String path) {
        assertThat(policy.evaluate(method, path).publicEndpoint()).isFalse();
    }

    @Test
    void boundSystemStatusRouteCarriesTheSystemMonitorAuditIdentity() {
        assertThat(policy.evaluate("GET", "/system/status").auditUsername()).contains("SYSTEM_MONITOR");
    }
}
