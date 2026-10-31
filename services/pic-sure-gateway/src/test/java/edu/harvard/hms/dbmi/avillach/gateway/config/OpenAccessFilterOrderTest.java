package edu.harvard.hms.dbmi.avillach.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;


import edu.harvard.hms.dbmi.avillach.commons.audit.AuditContext;
import edu.harvard.hms.dbmi.avillach.commons.net.ClientIpResolver;
import edu.harvard.hms.dbmi.avillach.gateway.auth.OpenAccessValidation;
import edu.harvard.hms.dbmi.avillach.gateway.auth.PsamaClient;
import edu.harvard.hms.dbmi.avillach.gateway.auth.PublicEndpointPolicy;
import edu.harvard.hms.dbmi.avillach.gateway.filter.OpenAccessFilter;
import edu.harvard.hms.dbmi.avillach.gateway.ratelimit.RateLimitProperties;
import edu.harvard.hms.dbmi.avillach.gateway.request.InboundIdentityHeaderSanitizingFilter;
import edu.harvard.hms.dbmi.avillach.gateway.request.InternalEndpointGuardFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;

class OpenAccessFilterOrderTest {

    @Test
    void assembledFiltersRejectInternalRequestsBeforeOpenAccessCallsPsama() throws Exception {
        PsamaClient psama = mock(PsamaClient.class);
        List<FilterRegistrationBean<? extends Filter>> registrations = assembledRegistrations(psama);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/operations/internal/queries/abc/dispatch");
        MockHttpServletResponse response = new MockHttpServletResponse();

        invokeInRegisteredOrder(registrations, request, response, (req, resp) -> {
        });

        assertThat(response.getStatus()).isEqualTo(404);
        verifyNoInteractions(psama);
    }

    @Test
    void assembledFiltersThrottleAfterBufferingAndBeforeTheInternalGuardAndOpenAccess() {
        GatewaySecurityProperties props = props();
        int throttle = AuditFilterOrderTest.throttleRegistration(props, subject -> null).getOrder();

        assertThat(throttle).isGreaterThan(new SecurityConfig().bufferingFilter(props, new SimpleMeterRegistry()).getOrder())
            .isLessThan(new ObservabilityConfig().internalEndpointGuardFilter().getOrder()).isLessThan(
                new SecurityConfig()
                    .openAccessFilter(mock(PsamaClient.class), new AuditContext(), props, new PublicEndpointPolicy(List.of())).getOrder()
            );
    }

    @Test
    void perKeyLimiterRunsAfterOpenAccessGrantsAndBeforeTheIdentityHeaderSanitizer() {
        GatewaySecurityProperties props = props();
        RateLimitProperties rateLimit = new RateLimitProperties(
            true, new RateLimitProperties.KeyLimits(60, 3000), new RateLimitProperties.KeyLimits(60, 3000),
            new RateLimitProperties.IpLimits(60, 3000, 300), new RateLimitProperties.Store(1000), List.of("192.0.2.10/32"), List.of()
        );
        int perKey = new SecurityConfig()
            .perKeyRateLimitFilter(rateLimit, new ClientIpResolver(List.of()), subject -> null, new AuditContext()).getOrder();

        assertThat(perKey).isGreaterThan(
            new SecurityConfig().openAccessFilter(mock(PsamaClient.class), new AuditContext(), props, new PublicEndpointPolicy(List.of()))
                .getOrder()
        ).isLessThan(new ObservabilityConfig().inboundIdentityHeaderSanitizingFilter().getOrder());
    }

    @Test
    @SuppressWarnings("unchecked")
    void assembledFiltersConsumeBearerApiKeyBeforeSanitizingRemovesItDownstream() throws Exception {
        PsamaClient psama = mock(PsamaClient.class);
        when(psama.validateOpenAccess(any())).thenReturn(OpenAccessValidation.fromBoolean(true));
        List<FilterRegistrationBean<? extends Filter>> registrations = assembledRegistrations(psama);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/query/sync");
        request.addHeader("authorization", "Bearer picsure_u_testKeyValue123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<HttpServletRequest> downstreamRequest = new AtomicReference<>();

        invokeInRegisteredOrder(registrations, request, response, (req, resp) -> downstreamRequest.set((HttpServletRequest) req));

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(psama).validateOpenAccess(payload.capture());
        assertThat(payload.getValue()).containsEntry("apiKey", "picsure_u_testKeyValue123");
        assertThat(downstreamRequest.get().getHeader("Authorization")).isNull();
        assertThat(downstreamRequest.get().getHeaders("Authorization").hasMoreElements()).isFalse();
        assertThat(Collections.list(downstreamRequest.get().getHeaderNames())).doesNotContain("Authorization", "authorization");
    }

    // the filter reads the first Authorization value; the sanitizer drops the whole header once any value carries a key
    @Test
    @SuppressWarnings("unchecked")
    void keyBeforeLoginTokenTakesTheOpenAccessPathAndNeitherReachesDownstream() throws Exception {
        PsamaClient psama = mock(PsamaClient.class);
        when(psama.validateOpenAccess(any())).thenReturn(OpenAccessValidation.fromBoolean(true));
        List<FilterRegistrationBean<? extends Filter>> registrations = assembledRegistrations(psama);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/query/sync");
        request.addHeader("Authorization", "Bearer picsure_u_testKeyValue123");
        request.addHeader("Authorization", "Bearer eyJlogin.token");
        AtomicReference<HttpServletRequest> downstreamRequest = new AtomicReference<>();

        invokeInRegisteredOrder(
            registrations, request, new MockHttpServletResponse(), (req, resp) -> downstreamRequest.set((HttpServletRequest) req)
        );

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(psama).validateOpenAccess(payload.capture());
        assertThat(payload.getValue()).containsEntry("apiKey", "picsure_u_testKeyValue123");
        assertThat(downstreamRequest.get().getHeaders("Authorization").hasMoreElements()).isFalse();
    }

    @Test
    void loginTokenBeforeKeyIsLeftForIntrospectionWithoutTheHeader() throws Exception {
        PsamaClient psama = mock(PsamaClient.class);
        List<FilterRegistrationBean<? extends Filter>> registrations = assembledRegistrations(psama);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/query/sync");
        request.addHeader("Authorization", "Bearer eyJlogin.token");
        request.addHeader("Authorization", "Bearer picsure_u_testKeyValue123");
        AtomicReference<HttpServletRequest> downstreamRequest = new AtomicReference<>();

        invokeInRegisteredOrder(
            registrations, request, new MockHttpServletResponse(), (req, resp) -> downstreamRequest.set((HttpServletRequest) req)
        );

        verifyNoInteractions(psama);
        assertThat(downstreamRequest.get().getAttribute(OpenAccessFilter.ATTR_OPEN_ACCESS_GRANTED)).isNull();
        assertThat(downstreamRequest.get().getHeaders("Authorization").hasMoreElements()).isFalse();
    }

    private static GatewaySecurityProperties props() {
        return new GatewaySecurityProperties(
            List.of(), true, 1024, "http://psama.local/introspect", "http://psama.local/open-access", "svc-token"
        );
    }

    private static List<FilterRegistrationBean<? extends Filter>> assembledRegistrations(PsamaClient psama) {
        GatewaySecurityProperties props = props();
        SecurityConfig securityConfig = new SecurityConfig();
        ObservabilityConfig observabilityConfig = new ObservabilityConfig();
        return List.of(
            observabilityConfig.internalEndpointGuardFilter(),
            securityConfig.openAccessFilter(psama, new AuditContext(), props, new PublicEndpointPolicy(List.of())),
            observabilityConfig.inboundIdentityHeaderSanitizingFilter()
        );
    }

    private static void invokeInRegisteredOrder(
        List<FilterRegistrationBean<? extends Filter>> registrations, ServletRequest request, ServletResponse response, FilterChain terminal
    ) throws Exception {
        List<Filter> filters = registrations.stream().sorted(Comparator.comparingInt(FilterRegistrationBean::getOrder))
            .<Filter>map(FilterRegistrationBean::getFilter).toList();
        new OrderedFilterChain(filters, terminal).doFilter(request, response);
    }

    private static final class OrderedFilterChain implements FilterChain {

        private final List<Filter> filters;
        private final FilterChain terminal;
        private int index;

        private OrderedFilterChain(List<Filter> filters, FilterChain terminal) {
            this.filters = filters;
            this.terminal = terminal;
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response) throws java.io.IOException, ServletException {
            if (index < filters.size()) {
                filters.get(index++).doFilter(request, response, this);
            } else {
                terminal.doFilter(request, response);
            }
        }
    }
}
