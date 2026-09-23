package edu.harvard.hms.dbmi.avillach.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import edu.harvard.hms.dbmi.avillach.commons.audit.AuditRoute;

/**
 * Verifies that the gateway audit route table treats the v1 and v3 aggregate open paths identically. Both {@code /hpds/open/query/sync} and
 * {@code /hpds/open/v3/query/sync} are audited at the gateway as {@code QUERY}/{@code query.sync}; the query service does not emit its own
 * audit event. The gateway's uniform query audit schema does not include per-event resource-id metadata. See {@link AuditFilterConfig}.
 */
class AggregateAuditRouteTest {

    private final AuditFilterConfig config = new AuditFilterConfig();

    @Test
    void openV1QuerySyncMapsToQuerySync() {
        Optional<AuditRoute> r = config.auditRouteTable().match("/hpds/open/query/sync", "POST");

        assertThat(r).isPresent();
        assertThat(r.get().getEventType()).isEqualTo("QUERY");
        assertThat(r.get().getAction()).isEqualTo("query.sync");
    }

    @Test
    void openV3QuerySyncMapsToQuerySync() {
        Optional<AuditRoute> r = config.auditRouteTable().match("/hpds/open/v3/query/sync", "POST");

        assertThat(r).isPresent();
        assertThat(r.get().getEventType()).isEqualTo("QUERY");
        assertThat(r.get().getAction()).isEqualTo("query.sync");
    }
}
