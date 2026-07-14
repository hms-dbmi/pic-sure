package edu.harvard.hms.dbmi.avillach.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import edu.harvard.hms.dbmi.avillach.commons.audit.AuditRoute;

/**
 * Reconciles the pic-sure-hpds-query-service aggregate open path against the gateway's audit route table: the legacy WAR's v1
 * {@code AggregateDataSharingResourceRS} emitted a bespoke inline {@code aggregate.query_sync} audit event, while its v3 sibling emitted
 * NONE at all (the missing-audit gap). Neither is ported -- the query-service performs no audit emission of its own; instead, both
 * {@code /hpds/open/query/sync} (v1) and {@code /hpds/open/v3/query/sync} (v3) are audited identically, at this single gateway chokepoint,
 * as {@code QUERY}/{@code query.sync}. This closes the v3 gap and unifies the action name (accepted, documented deviation from the WAR's
 * {@code aggregate.query_sync}), at the cost of the WAR's per-event {@code resource_id} metadata field (the gateway audit schema is uniform
 * across all query traffic, see {@link AuditFilterConfig}).
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
