package edu.harvard.hms.dbmi.avillach.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.filter.OrderedRequestContextFilter;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingEvent;
import edu.harvard.hms.dbmi.avillach.commons.audit.AuditContext;
import edu.harvard.hms.dbmi.avillach.commons.net.ClientIpResolver;
import edu.harvard.hms.dbmi.avillach.gateway.auth.OpenAccessValidation;
import edu.harvard.hms.dbmi.avillach.gateway.auth.PsamaClient;
import edu.harvard.hms.dbmi.avillach.gateway.auth.PublicEndpointPolicy;
import edu.harvard.hms.dbmi.avillach.gateway.filter.AuditFilterConfig;
import edu.harvard.hms.dbmi.avillach.gateway.ratelimit.RateLimitDecision;
import edu.harvard.hms.dbmi.avillach.gateway.ratelimit.RateLimitProperties;
import edu.harvard.hms.dbmi.avillach.gateway.ratelimit.RateLimitStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServlet;

/**
 * Chains are assembled by sorting the real {@link FilterRegistrationBean}s on configured order, never by hand-listing filters:
 * {@code AuditLoggingFilter} emits from a {@code finally}, so a hand-built audit-outermost chain passes whatever the registered order is.
 */
class AuditFilterOrderTest {

    private static GatewaySecurityProperties props(boolean openAccessEnabled) {
        return new GatewaySecurityProperties(
            List.of(), openAccessEnabled, 1024, "http://psama.local/introspect", "http://psama.local/open-access", "svc-token"
        );
    }

    /** Assembles the filters in the order the servlet container would apply them -- ascending registration order. */
    private static MockFilterChain containerOrderedChain(List<FilterRegistrationBean<?>> registrations) {
        List<FilterRegistrationBean<?>> sorted = new ArrayList<>(registrations);
        sorted.sort(Comparator.comparingInt(FilterRegistrationBean::getOrder));
        Filter[] filters = sorted.stream().map(FilterRegistrationBean::getFilter).toArray(Filter[]::new);
        return new MockFilterChain(new HttpServlet() {}, filters);
    }

    private static final RateLimitStore ALWAYS_BREACHED = subject -> new RateLimitDecision(false, RateLimitDecision.Window.BURST, 30);

    static FilterRegistrationBean<?> throttleRegistration(GatewaySecurityProperties props, RateLimitStore store) {
        return throttleRegistration(props, store, new AuditContext());
    }

    static FilterRegistrationBean<?> throttleRegistration(GatewaySecurityProperties props, RateLimitStore store, AuditContext audit) {
        return new SecurityConfig().prevalidationThrottleFilter(
            props, new PublicEndpointPolicy(List.of()), rateLimit(), new ClientIpResolver(List.of()), store, audit
        );
    }

    private static RateLimitProperties rateLimit() {
        return new RateLimitProperties(
            true, new RateLimitProperties.KeyLimits(60, 3000), new RateLimitProperties.KeyLimits(60, 3000),
            new RateLimitProperties.IpLimits(60, 3000, 300), new RateLimitProperties.Store(1000), List.of("192.0.2.10/32"), List.of()
        );
    }

    private static LoggingClient enabledLoggingClient() {
        LoggingClient client = mock(LoggingClient.class);
        when(client.isEnabled()).thenReturn(true);
        return client;
    }

    private static FilterRegistrationBean<?> auditRegistration(LoggingClient client, AuditContext audit) {
        AuditFilterConfig config = new AuditFilterConfig();
        return config.auditLoggingFilter(client, config.auditRouteTable(), audit, rateLimit(), new ClientIpResolver(List.of()));
    }

    @Test
    void openAccessDenialShortCircuitStillEmitsAnAuditEvent() throws Exception {
        LoggingClient logging = enabledLoggingClient();
        PsamaClient psama = mock(PsamaClient.class);
        when(psama.validateOpenAccess(any())).thenReturn(OpenAccessValidation.fromBoolean(false)); // PSAMA denies the no-bearer request
        AuditContext audit = new AuditContext();

        FilterRegistrationBean<?> openAccess =
            new SecurityConfig().openAccessFilter(psama, audit, props(true), new PublicEndpointPolicy(List.of()));

        MockHttpServletResponse response = new MockHttpServletResponse();
        containerOrderedChain(List.of(auditRegistration(logging, audit), openAccess))
            .doFilter(new MockHttpServletRequest("POST", "/query/sync"), response);

        assertThat(response.getStatus()).isEqualTo(401);

        ArgumentCaptor<LoggingEvent> emitted = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(logging).send(emitted.capture());
        assertThat(emitted.getValue().getMetadata()).containsEntry("auth_result", "failure")
            .containsEntry("auth_action", "open_access.denied");
        assertThat(emitted.getValue().getRequest().getStatus()).isEqualTo(401);
        assertThat(emitted.getValue().getError()).containsEntry("status", 401).containsEntry("error_type", "client_error");
    }

    @Test
    void throttleRejectionStillEmitsAnAuditEvent() throws Exception {
        LoggingClient logging = enabledLoggingClient();
        PsamaClient psama = mock(PsamaClient.class);
        AuditContext audit = new AuditContext();
        FilterRegistrationBean<?> openAccess =
            new SecurityConfig().openAccessFilter(psama, audit, props(true), new PublicEndpointPolicy(List.of()));

        MockHttpServletResponse response = new MockHttpServletResponse();
        containerOrderedChain(
            List.of(openAccess, throttleRegistration(props(true), ALWAYS_BREACHED, audit), auditRegistration(logging, audit))
        ).doFilter(new MockHttpServletRequest("POST", "/query/sync"), response);

        assertThat(response.getStatus()).isEqualTo(429);
        verifyNoInteractions(psama);
        ArgumentCaptor<LoggingEvent> emitted = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(logging).send(emitted.capture());
        assertThat(emitted.getValue().getRequest().getStatus()).isEqualTo(429);
    }

    @Test
    void introspectionDenialShortCircuitStillEmitsAnAuditEvent() throws Exception {
        LoggingClient logging = enabledLoggingClient();
        AuditContext audit = new AuditContext();

        // Open access disabled, so the no-bearer request reaches introspection and denies as missing_token.
        SecurityConfig security = new SecurityConfig();
        FilterRegistrationBean<?> openAccess =
            security.openAccessFilter(mock(PsamaClient.class), audit, props(false), new PublicEndpointPolicy(List.of()));
        FilterRegistrationBean<?> introspection =
            security.introspectionFilter(mock(PsamaClient.class), audit, new PublicEndpointPolicy(List.of()));

        MockHttpServletResponse response = new MockHttpServletResponse();
        containerOrderedChain(List.of(auditRegistration(logging, audit), openAccess, introspection))
            .doFilter(new MockHttpServletRequest("POST", "/query/sync"), response);

        assertThat(response.getStatus()).isEqualTo(401);

        ArgumentCaptor<LoggingEvent> emitted = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(logging).send(emitted.capture());
        assertThat(emitted.getValue().getMetadata()).containsEntry("auth_result", "failure")
            .containsEntry("auth_failure_reason", "missing_token");
    }

    @Test
    void auditFilterIsOrderedAheadOfEveryFilterThatCanShortCircuitTheChain() {
        AuditContext audit = new AuditContext();
        SecurityConfig security = new SecurityConfig();
        int auditOrder = auditRegistration(enabledLoggingClient(), audit).getOrder();

        assertThat(auditOrder).isLessThan(security.bufferingFilter(props(true), new SimpleMeterRegistry()).getOrder())
            .isLessThan(throttleRegistration(props(true), ALWAYS_BREACHED).getOrder())
            .isLessThan(
                security.openAccessFilter(mock(PsamaClient.class), audit, props(true), new PublicEndpointPolicy(List.of())).getOrder()
            ).isLessThan(security.introspectionFilter(mock(PsamaClient.class), audit, new PublicEndpointPolicy(List.of())).getOrder())
            .isLessThan(new ObservabilityConfig().internalEndpointGuardFilter().getOrder())
            .isLessThan(security.perKeyRateLimitFilter(rateLimit(), new ClientIpResolver(List.of()), ALWAYS_BREACHED, audit).getOrder());

        // Spring Security's chain can reject before any gateway filter runs (401/403), so audit must wrap it too.
        assertThat(auditOrder).isLessThan(SecurityProperties.DEFAULT_FILTER_ORDER);
    }

    @Test
    void auditFilterStaysInsideRequestContextFilterSoTheRequestScopedContextResolvesOnTheWayOut() {
        int auditOrder = auditRegistration(enabledLoggingClient(), new AuditContext()).getOrder();

        assertThat(auditOrder).isGreaterThan(new OrderedRequestContextFilter().getOrder());
    }
}
