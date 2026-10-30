package edu.harvard.hms.dbmi.avillach.commons.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
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
    void optionsRequestsSkipTheEvent() {
        LoggingClient client = mock(LoggingClient.class);
        when(client.isEnabled()).thenReturn(true);
        AuditLoggingFilter filter = new AuditLoggingFilter(client, routes, new AuditContext(), List.of());

        assertThat(filter.skipsEvent(new MockHttpServletRequest("OPTIONS", "/query/sync"))).isTrue();
    }

    @Test
    void systemStatusAndOpenapiPathsSkipTheEvent() {
        LoggingClient client = mock(LoggingClient.class);
        when(client.isEnabled()).thenReturn(true);
        AuditLoggingFilter filter = new AuditLoggingFilter(client, routes, new AuditContext(), List.of());

        assertThat(filter.skipsEvent(new MockHttpServletRequest("GET", "/system/status"))).isTrue();
        assertThat(filter.skipsEvent(new MockHttpServletRequest("GET", "/openapi.json"))).isTrue();
    }

    @Test
    void aPathContainingASkipContainsEntrySkipsTheEvent() {
        LoggingClient client = mock(LoggingClient.class);
        when(client.isEnabled()).thenReturn(true);
        AuditLoggingFilter filter = new AuditLoggingFilter(client, routes, new AuditContext(), List.of("/proxy/pic-sure-logging/"));

        assertThat(filter.skipsEvent(new MockHttpServletRequest("GET", "/proxy/pic-sure-logging/audit"))).isTrue();
    }

    @Test
    void shouldNotFilterIsFalseForAnOrdinaryRequest() {
        LoggingClient client = mock(LoggingClient.class);
        when(client.isEnabled()).thenReturn(true);
        AuditLoggingFilter filter = new AuditLoggingFilter(client, routes, new AuditContext(), List.of());

        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("POST", "/query/sync"))).isFalse();
        assertThat(filter.skipsEvent(new MockHttpServletRequest("POST", "/query/sync"))).isFalse();
    }

    @Test
    void aSkippedPathEmitsNothingUnlessTheChainMarksTheRequestAuditRequired() throws Exception {
        LoggingClient client = mock(LoggingClient.class);
        when(client.isEnabled()).thenReturn(true);
        AuditLoggingFilter filter = new AuditLoggingFilter(client, routes, new AuditContext(), List.of("/bin/continuous"));

        filter.doFilter(new MockHttpServletRequest("POST", "/bin/continuous"), new MockHttpServletResponse(), new MockFilterChain());
        verify(client, never()).send(any(LoggingEvent.class));

        MockFilterChain marking = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
                request.setAttribute(AuditLoggingFilter.AUDIT_REQUIRED_ATTRIBUTE, Boolean.TRUE);
                ((jakarta.servlet.http.HttpServletResponse) response).setStatus(429);
            }
        };
        filter.doFilter(new MockHttpServletRequest("POST", "/bin/continuous"), new MockHttpServletResponse(), marking);

        ArgumentCaptor<LoggingEvent> event = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(client).send(event.capture());
        assertThat(event.getValue().getRequest().getStatus()).isEqualTo(429);
    }

    /**
     * Runs the same request through the unsubclassed filter first and checks it emits, so the later silence can only come from the subclass
     * override and not from the base skip rules.
     */
    @Test
    void subclassOverrideOfShouldNotFilterSuppressesTheAuditEvent() throws Exception {
        LoggingClient client = mock(LoggingClient.class);
        when(client.isEnabled()).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/query/sync");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        new AuditLoggingFilter(client, routes, new AuditContext(), List.of()).doFilter(request, response, new MockFilterChain());
        verify(client, times(1)).send(any(LoggingEvent.class));
        clearInvocations(client);

        AuditLoggingFilter subclass = new AuditLoggingFilter(client, routes, new AuditContext(), List.of()) {
            @Override
            protected boolean shouldNotFilter(jakarta.servlet.http.HttpServletRequest request) {
                return true;
            }
        };

        subclass.doFilter(request, response, new MockFilterChain());

        verify(client, never()).send(any(LoggingEvent.class));
        verify(client, never()).send(any(LoggingEvent.class), any(), any());
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

    @Test
    void forwardsTheAuthorizationHeaderByDefault() throws Exception {
        LoggingClient client = mock(LoggingClient.class);
        when(client.isEnabled()).thenReturn(true);
        AuditLoggingFilter filter = new AuditLoggingFilter(client, routes, new AuditContext(), List.of());

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/query/sync");
        request.addHeader("Authorization", "Bearer some.jwt.token");
        request.addHeader("X-Request-Id", "req-1");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        verify(client).send(any(LoggingEvent.class), eq("Bearer some.jwt.token"), eq("req-1"));
    }

    @Test
    void anAuthorizationTheForwardPredicateRejectsIsNotSentButTheRequestIdStillIs() throws Exception {
        LoggingClient client = mock(LoggingClient.class);
        when(client.isEnabled()).thenReturn(true);
        AuditLoggingFilter filter =
            new AuditLoggingFilter(client, routes, new AuditContext(), List.of(), authorization -> !authorization.contains("secret"));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/query/sync");
        request.addHeader("Authorization", "Bearer secret-key");
        request.addHeader("X-Request-Id", "req-1");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        verify(client).send(any(LoggingEvent.class), isNull(), eq("req-1"));
    }
}
