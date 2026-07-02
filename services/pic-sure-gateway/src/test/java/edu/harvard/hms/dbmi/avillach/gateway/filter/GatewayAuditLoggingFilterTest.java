package edu.harvard.hms.dbmi.avillach.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import edu.harvard.hms.dbmi.avillach.commons.audit.AuditContext;
import edu.harvard.hms.dbmi.avillach.commons.audit.AuditRoute;
import edu.harvard.hms.dbmi.avillach.commons.audit.AuditRouteTable;
import edu.harvard.hms.dbmi.avillach.gateway.auth.GatewayAuthScope;
import edu.harvard.dbmi.avillach.logging.LoggingClient;
import jakarta.servlet.http.HttpServletRequest;

class GatewayAuditLoggingFilterTest {

    private GatewayAuditLoggingFilter filter() {
        // isEnabled() must be stubbed true so the base class's other skip conditions (OPTIONS, /system/status,
        // skipContains) are actually exercised rather than short-circuited by the default-disabled mock.
        LoggingClient client = mock(LoggingClient.class);
        when(client.isEnabled()).thenReturn(true);
        return new GatewayAuditLoggingFilter(
            client, mock(AuditRouteTable.class), new AuditContext(), List.of("/logging"),
            new GatewayAuthScope(false, List.of(".*/query/[^/]+/(?:result|signed-url)/?$"))
        );
    }

    @Test
    void skipsInterimResultSoWildFlyAuditsIt() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn("/query/abc/result");
        assertThat(filter().shouldNotFilter(req)).isTrue();
    }

    @Test
    void doesNotSkipGatewayOwnedPaths() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn("/v3/query");
        assertThat(filter().shouldNotFilter(req)).isFalse();
    }

    @Test
    void honorsBaseSkipListForNonInterimPaths() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn("/v3/logging/something");
        assertThat(filter().shouldNotFilter(req)).isTrue();
    }

    @Test
    void skipsWhenLoggingClientDisabled() {
        LoggingClient client = mock(LoggingClient.class);
        when(client.isEnabled()).thenReturn(false);
        GatewayAuditLoggingFilter filter = new GatewayAuditLoggingFilter(
            client, mock(AuditRouteTable.class), new AuditContext(), List.of("/logging"),
            new GatewayAuthScope(false, List.of(".*/query/[^/]+/(?:result|signed-url)/?$"))
        );

        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn("/v3/query");
        assertThat(filter.shouldNotFilter(req)).isTrue();
    }

    @Test
    void routeTableMatchesQuerySyncBeforeGenericQuery() {
        AuditRouteTable routes = new AuditFilterConfig().auditRouteTable();

        AuditRoute syncRoute = routes.match("/v3/query/sync", "POST").orElseThrow();
        assertThat(syncRoute.getAction()).isEqualTo("query.sync");

        AuditRoute submittedRoute = routes.match("/v3/query", "POST").orElseThrow();
        assertThat(submittedRoute.getAction()).isEqualTo("query.submitted");

        AuditRoute resultRoute = routes.match("/v3/query/abc-123/result", "GET").orElseThrow();
        assertThat(resultRoute.getAction()).isEqualTo("query.result");
    }
}
