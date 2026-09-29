package edu.harvard.hms.dbmi.avillach.commons.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingEvent;
import jakarta.servlet.FilterChain;

class AuditLoggingFilterTest {

    private final AuditRouteTable routes =
        new AuditRouteTable(List.of(new AuditRoute(Pattern.compile("^/query/sync/?$"), "POST", "QUERY", "query.sync")));

    @Test
    void shouldNotFilterIsTrueWhenClientIsDisabled() {
        LoggingClient client = mock(LoggingClient.class);
        when(client.isEnabled()).thenReturn(false);
        AuditLoggingFilter filter = new AuditLoggingFilter(client, routes, new AuditContext(), List.of());

        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("POST", "/query/sync"))).isTrue();
    }

    @Test
    void shouldNotFilterIsTrueForOptionsRequests() {
        LoggingClient client = mock(LoggingClient.class);
        when(client.isEnabled()).thenReturn(true);
        AuditLoggingFilter filter = new AuditLoggingFilter(client, routes, new AuditContext(), List.of());

        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("OPTIONS", "/query/sync"))).isTrue();
    }

    @Test
    void shouldNotFilterIsTrueForSystemStatusAndOpenapiPaths() {
        LoggingClient client = mock(LoggingClient.class);
        when(client.isEnabled()).thenReturn(true);
        AuditLoggingFilter filter = new AuditLoggingFilter(client, routes, new AuditContext(), List.of());

        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/system/status"))).isTrue();
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/openapi.json"))).isTrue();
    }

    @Test
    void shouldNotFilterIsTrueWhenPathContainsASkipContainsEntry() {
        LoggingClient client = mock(LoggingClient.class);
        when(client.isEnabled()).thenReturn(true);
        AuditLoggingFilter filter = new AuditLoggingFilter(client, routes, new AuditContext(), List.of("/proxy/pic-sure-logging/"));

        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/proxy/pic-sure-logging/audit"))).isTrue();
    }

    @Test
    void shouldNotFilterIsFalseForAnOrdinaryRequest() {
        LoggingClient client = mock(LoggingClient.class);
        when(client.isEnabled()).thenReturn(true);
        AuditLoggingFilter filter = new AuditLoggingFilter(client, routes, new AuditContext(), List.of());

        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("POST", "/query/sync"))).isFalse();
    }

    @Test
    void shouldNotFilterIsOverridableBySubclasses() {
        LoggingClient client = mock(LoggingClient.class);
        when(client.isEnabled()).thenReturn(true);
        AuditLoggingFilter filter = new AuditLoggingFilter(client, routes, new AuditContext(), List.of()) {
            @Override
            protected boolean shouldNotFilter(jakarta.servlet.http.HttpServletRequest request) {
                return true;
            }
        };

        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("POST", "/query/sync"))).isTrue();
    }

    @Test
    void emitsAnAuditEventForAMatchedRouteOnANonSkippedRequest() throws Exception {
        LoggingClient client = mock(LoggingClient.class);
        when(client.isEnabled()).thenReturn(true);
        AuditLoggingFilter filter = new AuditLoggingFilter(client, routes, new AuditContext(), List.of());

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/query/sync");
        request.addHeader("X-Session-Id", "session-123");
        request.addHeader("Referer", "https://picsure.example.org/explore");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        filter.doFilter(request, response, new MockFilterChain());

        ArgumentCaptor<LoggingEvent> eventCaptor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(client, times(1)).send(eventCaptor.capture());

        LoggingEvent event = eventCaptor.getValue();
        assertThat(event.getEventType()).isEqualTo("QUERY");
        assertThat(event.getAction()).isEqualTo("query.sync");
        assertThat(event.getSessionId()).isEqualTo("session-123");
        assertThat(event.getRequest().getReferrer()).isEqualTo("https://picsure.example.org/explore");
    }

    @Test
    void xClientTypeHeaderLandsAsCallerOnTheEmittedEvent() throws Exception {
        LoggingClient client = mock(LoggingClient.class);
        when(client.isEnabled()).thenReturn(true);
        AuditLoggingFilter filter = new AuditLoggingFilter(client, routes, new AuditContext(), List.of());

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/query/sync");
        request.addHeader("X-Client-Type", "PYTHON_ADAPTER");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        filter.doFilter(request, response, new MockFilterChain());

        ArgumentCaptor<LoggingEvent> eventCaptor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(client, times(1)).send(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getCaller()).isEqualTo("PYTHON_ADAPTER");
    }

    @Test
    void callerIsUnsetWhenTheXClientTypeHeaderIsAbsent() throws Exception {
        LoggingClient client = mock(LoggingClient.class);
        when(client.isEnabled()).thenReturn(true);
        AuditLoggingFilter filter = new AuditLoggingFilter(client, routes, new AuditContext(), List.of());

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/query/sync");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        filter.doFilter(request, response, new MockFilterChain());

        ArgumentCaptor<LoggingEvent> eventCaptor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(client, times(1)).send(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getCaller()).isNull();
    }

    @Test
    void callerIsUnsetWhenTheXClientTypeHeaderIsEmpty() throws Exception {
        LoggingClient client = mock(LoggingClient.class);
        when(client.isEnabled()).thenReturn(true);
        AuditLoggingFilter filter = new AuditLoggingFilter(client, routes, new AuditContext(), List.of());

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/query/sync");
        request.addHeader("X-Client-Type", "");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        filter.doFilter(request, response, new MockFilterChain());

        ArgumentCaptor<LoggingEvent> eventCaptor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(client, times(1)).send(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getCaller()).isNull();
    }

    @Test
    void withVerifiedCallerOnlyOffTheRawXClientTypeHeaderIsStillTheCaller() throws Exception {
        LoggingClient client = mock(LoggingClient.class);
        when(client.isEnabled()).thenReturn(true);
        AuditLoggingFilter filter = new AuditLoggingFilter(client, routes, new AuditContext(), List.of(), false);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/query/sync");
        request.addHeader("X-Client-Type", "MCP_SERVER");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        filter.doFilter(request, response, new MockFilterChain());

        ArgumentCaptor<LoggingEvent> eventCaptor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(client, times(1)).send(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getCaller()).isEqualTo("MCP_SERVER");
        assertThat(eventCaptor.getValue().getMetadata()).isNull();
    }

    @Test
    void withVerifiedCallerOnlyOnAndNoVerifiedCallerTheHeaderIsOnlyRecordedAsAClaim() throws Exception {
        LoggingClient client = mock(LoggingClient.class);
        when(client.isEnabled()).thenReturn(true);
        AuditLoggingFilter filter = new AuditLoggingFilter(client, routes, new AuditContext(), List.of(), true);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/query/sync");
        request.addHeader("X-Client-Type", "MCP_SERVER");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        filter.doFilter(request, response, new MockFilterChain());

        ArgumentCaptor<LoggingEvent> eventCaptor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(client, times(1)).send(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getCaller()).isNull();
        assertThat(eventCaptor.getValue().getMetadata()).containsEntry("client_type_claimed", "MCP_SERVER");
    }

    @Test
    void withVerifiedCallerOnlyOnACallerSetByALaterFilterIsRecorded() throws Exception {
        LoggingClient client = mock(LoggingClient.class);
        when(client.isEnabled()).thenReturn(true);
        AuditLoggingFilter filter = new AuditLoggingFilter(client, routes, new AuditContext(), List.of(), true);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/query/sync");
        request.addHeader("X-Client-Type", "PYTHON_ADAPTER");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);
        FilterChain chainThatVerifiesTheCaller = (req, res) -> VerifiedCaller.set(req, "MCP_SERVER");

        filter.doFilter(request, response, chainThatVerifiesTheCaller);

        ArgumentCaptor<LoggingEvent> eventCaptor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(client, times(1)).send(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getCaller()).isEqualTo("MCP_SERVER");
        assertThat(eventCaptor.getValue().getMetadata()).containsEntry("client_type_claimed", "PYTHON_ADAPTER");
    }

    void srcIpIsTheRightmostXffEntrySoAClientSuppliedLeadingEntryIsNeverUsed() throws Exception {
        LoggingClient client = mock(LoggingClient.class);
        when(client.isEnabled()).thenReturn(true);
        AuditLoggingFilter filter = new AuditLoggingFilter(client, routes, new AuditContext(), List.of());

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/query/sync");
        // A client can send its own X-Forwarded-For; the trusted front proxy APPENDS the address it
        // actually saw, so only the rightmost entry is trustworthy.
        request.addHeader("X-Forwarded-For", "6.6.6.6, 203.0.113.9");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        filter.doFilter(request, response, new MockFilterChain());

        ArgumentCaptor<LoggingEvent> eventCaptor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(client, times(1)).send(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getRequest().getSrcIp()).isEqualTo("203.0.113.9");
    }

    @Test
    void srcIpFallsBackToRemoteAddrWithoutAnXffHeader() throws Exception {
        LoggingClient client = mock(LoggingClient.class);
        when(client.isEnabled()).thenReturn(true);
        AuditLoggingFilter filter = new AuditLoggingFilter(client, routes, new AuditContext(), List.of());

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/query/sync");
        request.setRemoteAddr("192.0.2.44");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        filter.doFilter(request, response, new MockFilterChain());

        ArgumentCaptor<LoggingEvent> eventCaptor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(client, times(1)).send(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getRequest().getSrcIp()).isEqualTo("192.0.2.44");
    }

    @Test
    void doesNotEmitWhenTheRequestIsSkipped() throws Exception {
        LoggingClient client = mock(LoggingClient.class);
        when(client.isEnabled()).thenReturn(true);
        AuditLoggingFilter filter = new AuditLoggingFilter(client, routes, new AuditContext(), List.of());

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/system/status");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        verify(client, never()).send(any(LoggingEvent.class));
        verify(client, never()).send(any(LoggingEvent.class), any(), any());
    }

    private final AuditRouteTable routedPaths = new AuditRouteTable(
        List.of(
            new AuditRoute(Pattern.compile("^(?:/[a-z0-9-]+)+?/query/?$"), "POST", "QUERY", "query.submitted"),
            new AuditRoute(Pattern.compile("^/mcp$"), "POST", "OTHER", "mcp.request")
        )
    );

    private LoggingEvent emittedFor(String rawPath) throws Exception {
        LoggingClient client = mock(LoggingClient.class);
        when(client.isEnabled()).thenReturn(true);
        AuditLoggingFilter filter = new AuditLoggingFilter(client, routedPaths, new AuditContext(), List.of(), true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        filter.doFilter(new MockHttpServletRequest("POST", rawPath), response, new MockFilterChain());

        ArgumentCaptor<LoggingEvent> eventCaptor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(client, times(1)).send(eventCaptor.capture());
        return eventCaptor.getValue();
    }

    @Test
    void anEncodedMcpSpellingMatchesTheRouteItsDecodedPathMatches() throws Exception {
        LoggingEvent event = emittedFor("/%6Dcp");

        assertThat(event.getAction()).isEqualTo("mcp.request");
        assertThat(event.getRequest().getUrl()).isEqualTo("/%6Dcp");
    }

    @Test
    void anEncodedHpdsSpellingMatchesTheQueryRouteItsDecodedPathMatches() throws Exception {
        LoggingEvent event = emittedFor("/%68pds/auth/%71uery");

        assertThat(event.getEventType()).isEqualTo("QUERY");
        assertThat(event.getAction()).isEqualTo("query.submitted");
        assertThat(event.getRequest().getUrl()).isEqualTo("/%68pds/auth/%71uery");
    }

    @Test
    void anEncodedPercentIsDecodedOnceAndNeverAgain() throws Exception {
        LoggingEvent event = emittedFor("/%256Dcp");

        assertThat(event.getEventType()).isEqualTo("OTHER");
        assertThat(event.getAction()).isEqualTo("POST");
    }
}
