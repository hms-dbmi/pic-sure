package edu.harvard.dbmi.avillach.security;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GatewayAuthDelegationTest {

    private static final String QUERY_RESULT = "/query/e830138f-2943-4661-90ae-da053bd94a18/result";
    private static final String QUERY_RESULT_TRAILING_SLASH = "/query/e830138f-2943-4661-90ae-da053bd94a18/result/";
    private static final String QUERY_SIGNED_URL_V3 = "/v3/query/e830138f-2943-4661-90ae-da053bd94a18/signed-url";
    private static final String QUERY_SIGNED_URL_NO_TRAILING_SLASH = "/query/e830138f-2943-4661-90ae-da053bd94a18/signed-url";
    private static final String OTHER_QUERY_PATH = "/query/sync";
    private static final String OTHER_QUERY_STATUS_PATH = "/query/e830138f-2943-4661-90ae-da053bd94a18/status";
    private static final String SEARCH_PATH = "/search/abc-123";

    // ---- GATEWAY_OWNS_AUTH=false, GATEWAY_OWNS_QUERY_READ_AUTH=false ----

    @Test
    public void bothFlagsOff_wildFlyOwnsEverything() {
        GatewayAuthDelegation delegation = new GatewayAuthDelegation(false, false);

        assertFalse(delegation.gatewayOwnsAuth(OTHER_QUERY_PATH));
        assertFalse(delegation.gatewayOwnsAuth(OTHER_QUERY_STATUS_PATH));
        assertFalse(delegation.gatewayOwnsAuth(SEARCH_PATH));
        assertFalse(delegation.gatewayOwnsAuth(QUERY_RESULT));
        assertFalse(delegation.gatewayOwnsAuth(QUERY_RESULT_TRAILING_SLASH));
        assertFalse(delegation.gatewayOwnsAuth(QUERY_SIGNED_URL_V3));
        assertFalse(delegation.gatewayOwnsAuth(QUERY_SIGNED_URL_NO_TRAILING_SLASH));
    }

    // ---- GATEWAY_OWNS_AUTH=false, GATEWAY_OWNS_QUERY_READ_AUTH=true (master switch wins) ----

    @Test
    public void authOffQueryReadOn_masterSwitchStillWins_wildFlyOwnsEverything() {
        GatewayAuthDelegation delegation = new GatewayAuthDelegation(false, true);

        assertFalse(delegation.gatewayOwnsAuth(OTHER_QUERY_PATH));
        assertFalse(delegation.gatewayOwnsAuth(SEARCH_PATH));
        assertFalse(delegation.gatewayOwnsAuth(QUERY_RESULT));
        assertFalse(delegation.gatewayOwnsAuth(QUERY_SIGNED_URL_V3));
    }

    // ---- GATEWAY_OWNS_AUTH=true, GATEWAY_OWNS_QUERY_READ_AUTH=false (Phase 2 cutover) ----

    @Test
    public void authOnQueryReadOff_gatewayOwnsEverythingExceptResultAndSignedUrl() {
        GatewayAuthDelegation delegation = new GatewayAuthDelegation(true, false);

        assertTrue(delegation.gatewayOwnsAuth(OTHER_QUERY_PATH));
        assertTrue(delegation.gatewayOwnsAuth(OTHER_QUERY_STATUS_PATH));
        assertTrue(delegation.gatewayOwnsAuth(SEARCH_PATH));
        assertTrue(delegation.gatewayOwnsAuth("/query"));

        assertFalse(delegation.gatewayOwnsAuth(QUERY_RESULT));
        assertFalse(delegation.gatewayOwnsAuth(QUERY_RESULT_TRAILING_SLASH));
        assertFalse(delegation.gatewayOwnsAuth(QUERY_SIGNED_URL_V3));
        assertFalse(delegation.gatewayOwnsAuth(QUERY_SIGNED_URL_NO_TRAILING_SLASH));
    }

    // ---- GATEWAY_OWNS_AUTH=true, GATEWAY_OWNS_QUERY_READ_AUTH=true (Phase 4) ----

    @Test
    public void bothFlagsOn_gatewayOwnsEverythingIncludingResultAndSignedUrl() {
        GatewayAuthDelegation delegation = new GatewayAuthDelegation(true, true);

        assertTrue(delegation.gatewayOwnsAuth(OTHER_QUERY_PATH));
        assertTrue(delegation.gatewayOwnsAuth(SEARCH_PATH));
        assertTrue(delegation.gatewayOwnsAuth(QUERY_RESULT));
        assertTrue(delegation.gatewayOwnsAuth(QUERY_RESULT_TRAILING_SLASH));
        assertTrue(delegation.gatewayOwnsAuth(QUERY_SIGNED_URL_V3));
        assertTrue(delegation.gatewayOwnsAuth(QUERY_SIGNED_URL_NO_TRAILING_SLASH));
    }

    // ---- Edge cases ----

    @Test
    public void nullOrBlankPath_failsClosed_wildFlyOwnsAuth() {
        // FIX 3: an unresolvable path must never default to gateway-owned -- that would silently skip WildFly's
        // JWTFilter/AuditLoggingFilter for a request it can't even identify, leaving a gap in the audit trail.
        assertFalse(new GatewayAuthDelegation(false, false).gatewayOwnsAuth(null));
        assertFalse(new GatewayAuthDelegation(true, false).gatewayOwnsAuth(null));
        assertFalse(new GatewayAuthDelegation(true, true).gatewayOwnsAuth(null));
        assertFalse(new GatewayAuthDelegation(true, false).gatewayOwnsAuth(""));
        assertFalse(new GatewayAuthDelegation(true, false).gatewayOwnsAuth("   "));
    }

    @Test
    public void resultPathWithExtraSegmentDoesNotMatchQueryReadPattern() {
        // /query/{id}/result/extra is not the result endpoint itself, so it's treated like any other
        // gateway-owned path once GATEWAY_OWNS_AUTH is true.
        GatewayAuthDelegation delegation = new GatewayAuthDelegation(true, false);
        assertTrue(delegation.gatewayOwnsAuth("/query/e830138f-2943-4661-90ae-da053bd94a18/result/extra"));
    }
}
