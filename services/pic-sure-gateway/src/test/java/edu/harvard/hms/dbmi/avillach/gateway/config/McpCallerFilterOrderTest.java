package edu.harvard.hms.dbmi.avillach.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingEvent;
import edu.harvard.hms.dbmi.avillach.commons.audit.AuditContext;
import edu.harvard.hms.dbmi.avillach.commons.audit.VerifiedCaller;
import edu.harvard.hms.dbmi.avillach.gateway.auth.IntrospectionResponse;
import edu.harvard.hms.dbmi.avillach.gateway.auth.PsamaClient;
import edu.harvard.hms.dbmi.avillach.gateway.auth.PublicEndpointPolicy;
import edu.harvard.hms.dbmi.avillach.gateway.filter.AuditFilterConfig;
import edu.harvard.hms.dbmi.avillach.gateway.filter.McpCallerFilter;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Assembles the real {@link FilterRegistrationBean}s sorted by their configured order, so these tests fail if {@link McpCallerFilter} is
 * registered after a filter that calls PSAMA or after the sanitizer.
 */
class McpCallerFilterOrderTest {

    private static final String SECRET = "configured-mcp-secret";

    private final AuditContext audit = new AuditContext();
    private final SecurityConfig security = new SecurityConfig();
    private final ObservabilityConfig observability = new ObservabilityConfig();

    private static GatewaySecurityProperties props(boolean openAccessEnabled) {
        return new GatewaySecurityProperties(
            List.of(), openAccessEnabled, 1024, "http://psama.local/introspect", "http://psama.local/open-access", "svc-token"
        );
    }

    private FilterRegistrationBean<McpCallerFilter> mcpRegistration() {
        return security.mcpCallerFilter(audit, new McpProperties(SECRET, null));
    }

    private List<FilterRegistrationBean<?>> gatewayChain(PsamaClient psama, boolean openAccessEnabled) {
        PublicEndpointPolicy noPublicRoutes = new PublicEndpointPolicy(List.of());
        return List.of(
            observability.inboundIdentityHeaderSanitizingFilter(), security.introspectionFilter(psama, audit, noPublicRoutes),
            security.openAccessFilter(psama, audit, props(openAccessEnabled), noPublicRoutes), mcpRegistration(),
            observability.internalEndpointGuardFilter()
        );
    }

    private static MockFilterChain containerOrderedChain(List<FilterRegistrationBean<?>> registrations, HttpServlet terminal) {
        Filter[] filters = registrations.stream().sorted(Comparator.comparingInt(FilterRegistrationBean::getOrder))
            .map(FilterRegistrationBean::getFilter).toArray(Filter[]::new);
        return new MockFilterChain(terminal, filters);
    }

    private static HttpServlet capturing(AtomicReference<HttpServletRequest> downstream) {
        return new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) {
                downstream.set(req);
            }
        };
    }

    @Test
    void mcpCallerFilterRunsAfterTheInternalGuardAndBeforeOpenAccessAndTheSanitizer() {
        int order = mcpRegistration().getOrder();

        assertThat(order).isGreaterThan(observability.internalEndpointGuardFilter().getOrder())
            .isLessThan(
                security.openAccessFilter(mock(PsamaClient.class), audit, props(true), new PublicEndpointPolicy(List.of())).getOrder()
            ).isLessThan(observability.inboundIdentityHeaderSanitizingFilter().getOrder())
            .isLessThan(security.introspectionFilter(mock(PsamaClient.class), audit, new PublicEndpointPolicy(List.of())).getOrder());
    }

    @Test
    void invalidTokenWithABearerIsRejectedBeforeIntrospectionCallsPsama() throws Exception {
        PsamaClient psama = mock(PsamaClient.class);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/hpds/auth/query/sync");
        request.addHeader("Authorization", "Bearer some.user.jwt");
        request.addHeader(McpCallerFilter.HEADER, "wrong-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<HttpServletRequest> downstream = new AtomicReference<>();

        containerOrderedChain(gatewayChain(psama, false), capturing(downstream)).doFilter(request, response);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("invalid_service_credential");
        assertThat(downstream.get()).isNull();
        verifyNoInteractions(psama);
    }

    @Test
    void invalidTokenWithoutABearerIsRejectedBeforeOpenAccessCallsPsama() throws Exception {
        PsamaClient psama = mock(PsamaClient.class);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/hpds/open/query/sync");
        request.addHeader(McpCallerFilter.HEADER, "wrong-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        containerOrderedChain(gatewayChain(psama, true), capturing(new AtomicReference<>())).doFilter(request, response);

        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(psama);
    }

    @Test
    void validTokenIsReadBeforeTheSanitizerRunsAndTheRequestStillIntrospects() throws Exception {
        PsamaClient psama = mock(PsamaClient.class);
        when(psama.introspect(eq("some.user.jwt"), any()))
            .thenReturn(new IntrospectionResponse(true, "u-1", "s-1", "user@example.org", "USER", List.of(), false, null, null));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/hpds/auth/query/sync");
        request.addHeader("Authorization", "Bearer some.user.jwt");
        request.addHeader(McpCallerFilter.HEADER, SECRET);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<HttpServletRequest> downstream = new AtomicReference<>();

        containerOrderedChain(gatewayChain(psama, false), capturing(downstream)).doFilter(request, response);

        assertThat(downstream.get()).isNotNull();
        assertThat(VerifiedCaller.get(downstream.get())).contains("mcp");
        verify(psama).introspect(eq("some.user.jwt"), any());
    }

    @Test
    void internalEndpointGuardStillAnswers404BeforeTheCredentialCheck() throws Exception {
        PsamaClient psama = mock(PsamaClient.class);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/operations/internal/queries/abc/dispatch");
        request.addHeader(McpCallerFilter.HEADER, "wrong-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        containerOrderedChain(gatewayChain(psama, false), capturing(new AtomicReference<>())).doFilter(request, response);

        assertThat(response.getStatus()).isEqualTo(404);
        verifyNoInteractions(psama);
    }

    @Test
    void auditedCallerComesFromTheVerifiedAttributeNotTheClaimedHeader() throws Exception {
        LoggingClient logging = mock(LoggingClient.class);
        when(logging.isEnabled()).thenReturn(true);
        AuditFilterConfig auditConfig = new AuditFilterConfig();
        FilterRegistrationBean<?> auditRegistration = auditConfig.auditLoggingFilter(logging, auditConfig.auditRouteTable(), audit);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("X-Client-Type", "PYTHON_ADAPTER");

        containerOrderedChain(List.of(auditRegistration, mcpRegistration()), capturing(new AtomicReference<>()))
            .doFilter(request, new MockHttpServletResponse());

        ArgumentCaptor<LoggingEvent> emitted = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(logging).send(emitted.capture());
        assertThat(emitted.getValue().getCaller()).isEqualTo("mcp");
        assertThat(emitted.getValue().getMetadata()).containsEntry("client_type_claimed", "PYTHON_ADAPTER");
    }

    @Test
    void rejectedCredentialIsAuditedAsAFailureWithoutTheTokenValue() throws Exception {
        LoggingClient logging = mock(LoggingClient.class);
        when(logging.isEnabled()).thenReturn(true);
        AuditFilterConfig auditConfig = new AuditFilterConfig();
        FilterRegistrationBean<?> auditRegistration = auditConfig.auditLoggingFilter(logging, auditConfig.auditRouteTable(), audit);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/hpds/auth/query/sync");
        request.addHeader(McpCallerFilter.HEADER, "leaked-looking-value");

        containerOrderedChain(List.of(auditRegistration, mcpRegistration()), capturing(new AtomicReference<>()))
            .doFilter(request, new MockHttpServletResponse());

        ArgumentCaptor<LoggingEvent> emitted = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(logging).send(emitted.capture());
        assertThat(emitted.getValue().getCaller()).isNull();
        assertThat(emitted.getValue().getMetadata()).containsEntry("auth_result", "failure")
            .containsEntry("auth_failure_reason", "invalid_service_credential");
        assertThat(emitted.getValue().toString()).doesNotContain("leaked-looking-value");
    }
}
