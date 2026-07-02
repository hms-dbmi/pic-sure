package edu.harvard.hms.dbmi.avillach.gateway.auth;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayAuthScopeTest {

    private static final List<String> QUERY_READ = List.of(".*/query/[^/]+/(?:result|signed-url)/?$");

    @Test
    void phase2_queryReadAuthOff_resultAndSignedUrlOwnedByWildFly() {
        GatewayAuthScope scope = new GatewayAuthScope(false, QUERY_READ); // GATEWAY_OWNS_QUERY_READ_AUTH=false
        assertThat(scope.interimOwnedByWildFly("/query/abc/result")).isTrue();
        assertThat(scope.interimOwnedByWildFly("/v3/query/abc/signed-url")).isTrue();
        assertThat(scope.interimOwnedByWildFly("/query/abc/signed-url/")).isTrue();
        assertThat(scope.gatewayOwnsAuth("/query/abc/result")).isFalse();
    }

    @Test
    void everythingElseIsGatewayOwned() {
        GatewayAuthScope scope = new GatewayAuthScope(false, QUERY_READ);
        assertThat(scope.gatewayOwnsAuth("/query")).isTrue();
        assertThat(scope.gatewayOwnsAuth("/query/abc/status")).isTrue();
        assertThat(scope.gatewayOwnsAuth("/search/xyz")).isTrue();
        assertThat(scope.interimOwnedByWildFly("/query")).isFalse();
    }

    @Test
    void phase4_queryReadAuthOn_gatewayOwnsQueryRead() {
        GatewayAuthScope scope = new GatewayAuthScope(true, QUERY_READ); // GATEWAY_OWNS_QUERY_READ_AUTH=true
        assertThat(scope.gatewayOwnsAuth("/query/abc/result")).isTrue();
        assertThat(scope.interimOwnedByWildFly("/v3/query/abc/signed-url")).isFalse();
    }
}
