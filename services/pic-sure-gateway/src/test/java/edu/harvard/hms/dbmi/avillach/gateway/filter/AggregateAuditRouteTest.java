package edu.harvard.hms.dbmi.avillach.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import edu.harvard.hms.dbmi.avillach.commons.audit.AuditRoute;

/**
 * Verifies that the gateway audit route table classifies the aggregate open sync path. {@code /hpds/open/query/sync} is audited at the
 * gateway as {@code QUERY}/{@code query.sync}; the query service does not emit its own audit event. The gateway's uniform query audit
 * schema does not include per-event resource-id metadata. See {@link AuditFilterConfig}.
 */
class AggregateAuditRouteTest {

    private final AuditFilterConfig config = new AuditFilterConfig();

    @Test
    void openQuerySyncMapsToQuerySync() {
        Optional<AuditRoute> r = config.auditRouteTable().match("/hpds/open/query/sync", "POST");

        assertThat(r).isPresent();
        assertThat(r.get().getEventType()).isEqualTo("QUERY");
        assertThat(r.get().getAction()).isEqualTo("query.sync");
    }

    @Test
    void mcpPostMapsToMcpRequest() {
        Optional<AuditRoute> r = config.auditRouteTable().match(McpCallerFilter.MCP_PATH, "POST");

        assertThat(r).isPresent();
        assertThat(r.get().getEventType()).isEqualTo("OTHER");
        assertThat(r.get().getAction()).isEqualTo("mcp.request");
    }

    /**
     * {@code GET /hpds/auth/search/values} is the path the query service serves genomic annotation values on. The table matches the path
     * alone, so the handler's {@code genomicConceptPath}, {@code query}, {@code page} and {@code size} query parameters play no part.
     */
    @Test
    void authorizedSearchValuesMapsToSearchValues() {
        Optional<AuditRoute> r = config.auditRouteTable().match("/hpds/auth/search/values", "GET");

        assertThat(r).isPresent();
        assertThat(r.get().getEventType()).isEqualTo("SEARCH");
        assertThat(r.get().getAction()).isEqualTo("search.values");
    }

    @Test
    void openSearchValuesMapsToSearchValues() {
        Optional<AuditRoute> r = config.auditRouteTable().match("/hpds/open/search/values", "GET");

        assertThat(r).isPresent();
        assertThat(r.get().getEventType()).isEqualTo("SEARCH");
        assertThat(r.get().getAction()).isEqualTo("search.values");
    }

    /**
     * No handler behind the gateway serves {@code POST} on a path ending in {@code /search/{segment}}, and concept search lives at
     * {@code /internal/search/{backend}}, which the gateway refuses to route. Such a request gets the generic audit event.
     */
    @Test
    void postBelowSearchHasNoRouteEntry() {
        assertThat(config.auditRouteTable().match("/hpds/auth/search/abc", "POST")).isEmpty();
        assertThat(config.auditRouteTable().match("/hpds/open/search/abc", "POST")).isEmpty();
        assertThat(config.auditRouteTable().match("/internal/search/auth", "POST")).isEmpty();
    }

    /**
     * The {@code mcp.request} entry matches only POST on the exact path. Other methods and spellings still get the generic audit event;
     * this pins only the route table entry.
     */
    @Test
    void mcpRouteEntryMatchesOnlyPostOnTheExactPath() {
        assertThat(config.auditRouteTable().match("/mcp", "GET")).isEmpty();
        assertThat(config.auditRouteTable().match("/mcp/", "POST")).isEmpty();
    }
}
