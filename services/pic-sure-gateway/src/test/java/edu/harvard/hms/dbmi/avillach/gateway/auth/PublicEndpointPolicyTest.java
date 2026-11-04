package edu.harvard.hms.dbmi.avillach.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import edu.harvard.hms.dbmi.avillach.gateway.auth.PublicRoute.MatchKind;

/**
 * The method/path pairs below are the contract the shipped {@code public-routes} block has to keep. The policy under test is built from
 * {@link ShippedPublicRoutes}; {@code PublicRoutesBindingTest} asserts the same pairs against the bound yaml.
 */
class PublicEndpointPolicyTest {

    private final PublicEndpointPolicy policy = policy();

    @ParameterizedTest
    @CsvSource(
        {"GET, /system/status", "GET, /openapi.json", "POST, /gateway/openapi.json", "GET, /logging", "POST, /logging/audit",
            "GET, /operations/configuration", "GET, /operations/configuration/", "GET, /operations/configuration/abc-123",
            "GET, /operations/configuration/abc-123/", "GET, /operations/banners/active"}
    )
    void existingPublicRoutesRemainPublic(String method, String path) {
        assertThat(policy.evaluate(method, path).publicEndpoint()).isTrue();
    }

    @ParameterizedTest
    @CsvSource(
        {"POST, /system/status", "GET, /v3/system/status", "GET, /foo/system/status", "GET, /loggingAdmin/x",
            "GET, /operations/configuration/admin", "GET, /operations/configuration/admin/x", "POST, /operations/configuration",
            "POST, /operations/configuration/abc-123", "GET, /operations/dataset/named/abc-123", "POST, /operations/banners/active",
            "GET, /operations/banners", "GET, /operations/banners/active/", "GET, /operations/banners/active/extra"}
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
    void configuredRoutesAreDefensivelyCopied() {
        List<PublicRoute> routes = new ArrayList<>(List.of(ShippedPublicRoutes.prefix("/logging")));
        PublicEndpointPolicy copiedPolicy = new PublicEndpointPolicy(routes);
        routes.clear();

        assertThat(copiedPolicy.evaluate("GET", "/logging/audit").publicEndpoint()).isTrue();
    }

    @Test
    void firstMatchingRouteInListOrderDecides() {
        PublicEndpointPolicy ordered = new PublicEndpointPolicy(
            List.of(
                new PublicRoute("/status", MatchKind.EXACT, Set.of("GET"), null, "FIRST"),
                new PublicRoute("/status", MatchKind.EXACT, null, null, "SECOND")
            )
        );

        assertThat(ordered.evaluate("GET", "/status").auditUsername()).contains("FIRST");
        assertThat(ordered.evaluate("POST", "/status").auditUsername()).contains("SECOND");
    }

    @Test
    void noConfiguredRoutesProtectsEverything() {
        PublicEndpointPolicy empty = new PublicEndpointPolicy(List.of());

        assertThat(empty.evaluate("GET", "/system/status").publicEndpoint()).isFalse();
        assertThat(empty.evaluate("GET", "/openapi.json").publicEndpoint()).isFalse();
    }

    private static PublicEndpointPolicy policy() {
        return new PublicEndpointPolicy(ShippedPublicRoutes.routes());
    }
}
