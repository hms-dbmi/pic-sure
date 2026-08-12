package edu.harvard.hms.dbmi.avillach.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PublicEndpointPolicyTest {

    private final PublicEndpointPolicy policy = policy();

    @ParameterizedTest
    @CsvSource(
        {"GET, /system/status", "GET, /openapi.json", "POST, /gateway/openapi.json", "GET, /logging", "POST, /logging/audit",
            "GET, /operations/configuration", "GET, /operations/configuration/", "GET, /operations/configuration/abc-123",
            "GET, /operations/configuration/abc-123/"}
    )
    void existingPublicRoutesRemainPublic(String method, String path) {
        assertThat(policy.evaluate(method, path).publicEndpoint()).isTrue();
    }

    @ParameterizedTest
    @CsvSource(
        {"POST, /system/status", "GET, /v3/system/status", "GET, /foo/system/status", "GET, /loggingAdmin/x",
            "GET, /operations/configuration/admin", "GET, /operations/configuration/admin/x", "POST, /operations/configuration",
            "POST, /operations/configuration/abc-123", "GET, /operations/dataset/named/abc-123"}
    )
    void adjacentRoutesRemainProtected(String method, String path) {
        assertThat(policy.evaluate(method, path).publicEndpoint()).isFalse();
    }

    @Test
    void systemStatusCarriesTheSystemMonitorAuditIdentity() {
        PublicEndpointPolicy.Decision decision = policy.evaluate("GET", "/system/status");

        assertThat(decision.auditUsername()).contains("SYSTEM_MONITOR");
    }

    @Test
    void otherPublicRoutesDoNotInventAnAuditIdentity() {
        PublicEndpointPolicy.Decision decision = policy.evaluate("GET", "/logging/audit");

        assertThat(decision.auditUsername()).isEmpty();
    }

    @Test
    void nullMethodOrPathIsProtected() {
        assertThat(policy.evaluate(null, "/system/status").publicEndpoint()).isFalse();
        assertThat(policy.evaluate("GET", null).publicEndpoint()).isFalse();
    }

    @Test
    void configuredPrefixesAreDefensivelyCopied() {
        List<String> prefixes = new ArrayList<>(List.of("/logging"));
        PublicEndpointPolicy copiedPolicy = new PublicEndpointPolicy(prefixes);
        prefixes.clear();

        assertThat(copiedPolicy.evaluate("GET", "/logging/audit").publicEndpoint()).isTrue();
    }

    private static PublicEndpointPolicy policy() {
        return new PublicEndpointPolicy(List.of("/actuator", "/openapi", "/swagger-ui", "/logging"));
    }
}
