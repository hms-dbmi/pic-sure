package edu.harvard.hms.dbmi.avillach.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import edu.harvard.hms.dbmi.avillach.commons.audit.AuditRoute;

/**
 * Pins the gateway audit route table against the surviving HTTP surface. The rows are the single chokepoint where query and search traffic
 * becomes an audit event, so a route that matches nothing is silent data loss rather than a test failure -- which is exactly what happened
 * to search: the patterns were written for the legacy WAR's {@code /search/{resourceId}} and {@code /search/{resourceId}/values/} shapes,
 * and neither matched once the {@code {resourceId}} segment was dropped from the ingress.
 *
 * <p>Every case here asserts a REAL path from the surviving surface, so a future ingress rename fails here instead of quietly unaudting
 * itself.
 */
class AuditFilterConfigRouteTest {

    private final AuditFilterConfig config = new AuditFilterConfig();

    private Optional<AuditRoute> match(String path, String method) {
        return config.auditRouteTable().match(path, method);
    }

    private void assertRoute(String path, String method, String eventType, String action) {
        Optional<AuditRoute> r = match(path, method);
        assertThat(r).as("no audit route matched %s %s", method, path).isPresent();
        assertThat(r.get().getEventType()).isEqualTo(eventType);
        assertThat(r.get().getAction()).isEqualTo(action);
    }

    // --- search: the surviving v3 surface ---

    @Test
    void v3SearchMapsToSearchExecute() {
        assertRoute("/hpds/auth/v3/search", "POST", "SEARCH", "search.execute");
    }

    @Test
    void v3SearchOnTheOpenBackendMapsToSearchExecute() {
        assertRoute("/hpds/open/v3/search", "POST", "SEARCH", "search.execute");
    }

    /** The values endpoint is a GET now; the row pins no method, so the real verb is audited. */
    @Test
    void v3SearchValuesMapsToSearchValues() {
        assertRoute("/hpds/auth/v3/search/values", "GET", "SEARCH", "search.values");
    }

    @Test
    void v3SearchValuesMatchesWithATrailingSlash() {
        assertRoute("/hpds/auth/v3/search/values/", "GET", "SEARCH", "search.values");
    }

    /** First-match-wins: the more specific values row must win over the plain search row, never the other way round. */
    @Test
    void searchValuesIsNotSwallowedByTheSearchExecuteRow() {
        assertThat(match("/hpds/auth/v3/search/values", "GET").map(AuditRoute::getAction)).contains("search.values");
        assertThat(match("/hpds/auth/v3/search", "POST").map(AuditRoute::getAction)).contains("search.execute");
    }

    // --- query rows are unaffected by the search fix ---

    @Test
    void queryRoutesStillMap() {
        assertRoute("/hpds/auth/v3/query/sync", "POST", "QUERY", "query.sync");
        assertRoute("/hpds/auth/v3/query", "POST", "QUERY", "query.submitted");
        assertRoute("/hpds/auth/v3/query/abc-123/status", "GET", "QUERY", "query.status");
        assertRoute("/hpds/auth/v3/query/abc-123/result", "POST", "DATA_ACCESS", "query.result");
        assertRoute("/hpds/auth/v3/query/abc-123/signed-url", "POST", "DATA_ACCESS", "query.signed_url");
        assertRoute("/hpds/auth/v3/query/abc-123/metadata", "GET", "QUERY", "query.metadata");
    }

    /** A search row must not claim query traffic (the two prefixes are disjoint, and stay that way). */
    @Test
    void queryPathsDoNotMatchASearchRoute() {
        assertThat(match("/hpds/auth/v3/query", "POST").map(AuditRoute::getEventType)).contains("QUERY");
        assertThat(match("/hpds/auth/v3/query/sync", "POST").map(AuditRoute::getEventType)).contains("QUERY");
    }

    @Test
    void anUnmappedPathMatchesNothing() {
        assertThat(match("/hpds/auth/v3/info", "POST")).isEmpty();
    }
}
