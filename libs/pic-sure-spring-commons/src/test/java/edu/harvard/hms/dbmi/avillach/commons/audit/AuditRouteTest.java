package edu.harvard.hms.dbmi.avillach.commons.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class AuditRouteTest {

    @Test
    void matchesOnlyGivenMethodWhenMethodIsSpecified() {
        AuditRoute route = new AuditRoute(Pattern.compile("^/query/sync/?$"), "POST", "QUERY", "query.sync");

        assertThat(route.matches("/query/sync", "POST")).isTrue();
        assertThat(route.matches("/query/sync", "GET")).isFalse();
    }

    @Test
    void nullMethodMeansAnyMethod() {
        AuditRoute route = new AuditRoute(Pattern.compile("^/query/[^/]+/status/?$"), null, "QUERY", "query.status");

        assertThat(route.matches("/query/abc/status", "GET")).isTrue();
        assertThat(route.matches("/query/abc/status", "POST")).isTrue();
    }

    @Test
    void defaultConstructorUsesMatchesNotFind() {
        AuditRoute route = new AuditRoute(Pattern.compile("^/search/[^/]+/?$"), "POST", "SEARCH", "search.execute");

        // "matches" requires the whole string to match; a trailing segment should not match.
        assertThat(route.matches("/search/abc/values", "POST")).isFalse();
    }

    @Test
    void useFindTrueMatchesAnywhereInThePath() {
        AuditRoute route = new AuditRoute(Pattern.compile("^/search/[^/]+/values/"), null, "SEARCH", "search.values", true);

        assertThat(route.matches("/search/abc/values/1", "GET")).isTrue();
    }

    @Test
    void useFindFalseRequiresFullMatch() {
        AuditRoute route = new AuditRoute(Pattern.compile("^/search/[^/]+/values/"), null, "SEARCH", "search.values", false);

        // The pattern has no trailing anchor, so a full-string match against extra characters fails.
        assertThat(route.matches("/search/abc/values/1", "GET")).isFalse();
    }
}
