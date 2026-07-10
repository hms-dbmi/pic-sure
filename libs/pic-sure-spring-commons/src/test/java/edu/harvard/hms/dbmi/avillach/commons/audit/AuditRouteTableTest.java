package edu.harvard.hms.dbmi.avillach.commons.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class AuditRouteTableTest {

    @Test
    void firstMatchWinsForOverlappingPatterns() {
        AuditRoute syncRoute = new AuditRoute(Pattern.compile("^/query/sync/?$"), "POST", "QUERY", "query.sync");
        AuditRoute queryRoute = new AuditRoute(Pattern.compile("^/query/?$"), "POST", "QUERY", "query.submitted");
        AuditRouteTable table = new AuditRouteTable(List.of(syncRoute, queryRoute));

        Optional<AuditRoute> match = table.match("/query/sync", "POST");

        assertThat(match).contains(syncRoute);
    }

    @Test
    void returnsEmptyWhenNoRouteMatches() {
        AuditRouteTable table =
            new AuditRouteTable(List.of(new AuditRoute(Pattern.compile("^/query/?$"), "POST", "QUERY", "query.submitted")));

        assertThat(table.match("/unrelated", "GET")).isEmpty();
    }

    @Test
    void matchRespectsMethodFiltering() {
        AuditRouteTable table =
            new AuditRouteTable(List.of(new AuditRoute(Pattern.compile("^/query/?$"), "POST", "QUERY", "query.submitted")));

        assertThat(table.match("/query", "GET")).isEmpty();
        assertThat(table.match("/query", "POST")).isPresent();
    }
}
