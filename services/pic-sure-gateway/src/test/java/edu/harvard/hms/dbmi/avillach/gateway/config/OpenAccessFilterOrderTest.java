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

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.hms.dbmi.avillach.commons.audit.AuditContext;
import edu.harvard.hms.dbmi.avillach.gateway.auth.PsamaClient;
import edu.harvard.hms.dbmi.avillach.gateway.filter.OpenAccessFilter;
import edu.harvard.hms.dbmi.avillach.gateway.request.InboundIdentityHeaderSanitizingFilter;
import edu.harvard.hms.dbmi.avillach.gateway.request.InternalEndpointGuardFilter;
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
    @SuppressWarnings("unchecked")
    void assembledFiltersConsumeApiKeyBeforeCaseInsensitiveSanitizingRemovesItDownstream() throws Exception {
        PsamaClient psama = mock(PsamaClient.class);
        when(psama.validateOpenAccess(any())).thenReturn(true);
        List<FilterRegistrationBean<? extends Filter>> registrations = assembledRegistrations(psama);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/query/sync");
        request.addHeader("x-PiCsUrE-aPi-kEy", "picsure_testKeyValue123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<HttpServletRequest> downstreamRequest = new AtomicReference<>();

        invokeInRegisteredOrder(registrations, request, response, (req, resp) -> downstreamRequest.set((HttpServletRequest) req));

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(psama).validateOpenAccess(payload.capture());
        assertThat(payload.getValue()).containsEntry("apiKey", "picsure_testKeyValue123");
        assertThat(downstreamRequest.get().getHeader("X-PICSURE-API-Key")).isNull();
        assertThat(downstreamRequest.get().getHeader("x-picsure-api-key")).isNull();
        assertThat(downstreamRequest.get().getHeaders("X-PICSURE-API-Key").hasMoreElements()).isFalse();
        assertThat(Collections.list(downstreamRequest.get().getHeaderNames())).doesNotContain("X-PICSURE-API-Key", "x-PiCsUrE-aPi-kEy");
    }

    private static List<FilterRegistrationBean<? extends Filter>> assembledRegistrations(PsamaClient psama) {
        GatewaySecurityProperties props = new GatewaySecurityProperties(
            List.of(), true, 1024, "http://psama.local/introspect", "http://psama.local/open-access", "svc-token",
            "http://operations.local", "internal-token"
        );
        SecurityConfig securityConfig = new SecurityConfig();
        ObservabilityConfig observabilityConfig = new ObservabilityConfig();
        return List.of(
            observabilityConfig.internalEndpointGuardFilter(),
            securityConfig.openAccessFilter(psama, new AuditContext(), new ObjectMapper(), props),
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
