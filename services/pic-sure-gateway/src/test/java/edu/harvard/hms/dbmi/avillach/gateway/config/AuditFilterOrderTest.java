package edu.harvard.hms.dbmi.avillach.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingEvent;
import edu.harvard.hms.dbmi.avillach.commons.audit.AuditContext;
import edu.harvard.hms.dbmi.avillach.gateway.auth.PsamaClient;
import edu.harvard.hms.dbmi.avillach.gateway.auth.PublicEndpointPolicy;
import edu.harvard.hms.dbmi.avillach.gateway.auth.QueryAuthFetcher;
import edu.harvard.hms.dbmi.avillach.gateway.filter.AuditFilterConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServlet;

/**
 * Chains are assembled by sorting the real {@link FilterRegistrationBean}s on configured order, never by hand-listing filters:
 * {@code AuditLoggingFilter} emits from a {@code finally}, so a hand-built audit-outermost chain passes whatever the registered order is.
 */
class AuditFilterOrderTest {

    private static GatewaySecurityProperties props(boolean openAccessEnabled) {
        // No open-path prefixes and null timeouts keep this suite on the behavior it was written against: the open
        // fast path triggers on token absence only, and the auth clients fall back to the default 2s/10s bounds.
        return new GatewaySecurityProperties(
            List.of(), List.of(), openAccessEnabled, 1024, "http://psama.local/introspect", "http://psama.local/open-access", "svc-token",
            "http://operations.local", "internal-token", null, null
        );
    }

    /** Assembles the filters in the order the servlet container would apply them -- ascending registration order. */
    private static MockFilterChain containerOrderedChain(List<FilterRegistrationBean<?>> registrations) {
        List<FilterRegistrationBean<?>> sorted = new ArrayList<>(registrations);
        sorted.sort(Comparator.comparingInt(FilterRegistrationBean::getOrder));
        Filter[] filters = sorted.stream().map(FilterRegistrationBean::getFilter).toArray(Filter[]::new);
        return new MockFilterChain(new HttpServlet() {}, filters);
    }

    private static LoggingClient enabledLoggingClient() {
        LoggingClient client = mock(LoggingClient.class);
        when(client.isEnabled()).thenReturn(true);
        return client;
    }

    private static FilterRegistrationBean<?> auditRegistration(LoggingClient client, AuditContext audit) {
        AuditFilterConfig config = new AuditFilterConfig();
        return config.auditLoggingFilter(client, config.auditRouteTable(), audit);
    }

    @Test
    void openAccessDenialShortCircuitStillEmitsAnAuditEvent() throws Exception {
        LoggingClient logging = enabledLoggingClient();
        PsamaClient psama = mock(PsamaClient.class);
        when(psama.validateOpenAccess(any())).thenReturn(false); // PSAMA denies the no-bearer request
        AuditContext audit = new AuditContext();

        FilterRegistrationBean<?> openAccess =
            new SecurityConfig().openAccessFilter(psama, audit, new ObjectMapper(), props(true), new PublicEndpointPolicy(List.of()));

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
    void introspectionDenialShortCircuitStillEmitsAnAuditEvent() throws Exception {
        LoggingClient logging = enabledLoggingClient();
        AuditContext audit = new AuditContext();

        // Open access disabled, so the no-bearer request reaches introspection and denies as missing_token.
        SecurityConfig security = new SecurityConfig();
        FilterRegistrationBean<?> openAccess = security
            .openAccessFilter(mock(PsamaClient.class), audit, new ObjectMapper(), props(false), new PublicEndpointPolicy(List.of()));
        FilterRegistrationBean<?> introspection = security.introspectionFilter(
            mock(PsamaClient.class), audit, new ObjectMapper(), mock(QueryAuthFetcher.class), new PublicEndpointPolicy(List.of())
        );

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
            .isLessThan(
                security
                    .openAccessFilter(mock(PsamaClient.class), audit, new ObjectMapper(), props(true), new PublicEndpointPolicy(List.of()))
                    .getOrder()
            )
            .isLessThan(
                security.introspectionFilter(
                    mock(PsamaClient.class), audit, new ObjectMapper(), mock(QueryAuthFetcher.class), new PublicEndpointPolicy(List.of())
                ).getOrder()
            );

        // Spring Security's chain can reject before any gateway filter runs (401/403), so audit must wrap it too.
        assertThat(auditOrder).isLessThan(SecurityProperties.DEFAULT_FILTER_ORDER);
    }

    @Test
    void auditFilterStaysInsideRequestContextFilterSoTheRequestScopedContextResolvesOnTheWayOut() {
        int auditOrder = auditRegistration(enabledLoggingClient(), new AuditContext()).getOrder();

        assertThat(auditOrder).isGreaterThan(new OrderedRequestContextFilter().getOrder());
    }
}
