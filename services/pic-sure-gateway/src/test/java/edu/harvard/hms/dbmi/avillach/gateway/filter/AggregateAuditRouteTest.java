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
        Optional<AuditRoute> r = config.auditRouteTable().match("/mcp", "POST");

        assertThat(r).isPresent();
        assertThat(r.get().getEventType()).isEqualTo("OTHER");
        assertThat(r.get().getAction()).isEqualTo("mcp.request");
    }

    @Test
    void mcpIsAuditedOnlyForPostOnTheExactPath() {
        assertThat(config.auditRouteTable().match("/mcp", "GET")).isEmpty();
        assertThat(config.auditRouteTable().match("/mcp/", "POST")).isEmpty();
    }
}
