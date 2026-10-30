package edu.harvard.hms.dbmi.avillach.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingEvent;
import edu.harvard.hms.dbmi.avillach.commons.audit.AuditContext;
import edu.harvard.hms.dbmi.avillach.commons.net.CidrRange;
import edu.harvard.hms.dbmi.avillach.commons.net.ClientIpResolver;
import edu.harvard.hms.dbmi.avillach.gateway.auth.OpenAccessValidation;
import edu.harvard.hms.dbmi.avillach.gateway.auth.PsamaClient;
import edu.harvard.hms.dbmi.avillach.gateway.auth.PublicEndpointPolicy;
import edu.harvard.hms.dbmi.avillach.gateway.filter.AuditFilterConfig;
import edu.harvard.hms.dbmi.avillach.gateway.ratelimit.RateLimitDecision;
import edu.harvard.hms.dbmi.avillach.gateway.ratelimit.RateLimitDecision.Window;
import edu.harvard.hms.dbmi.avillach.gateway.ratelimit.RateLimitProperties;
import edu.harvard.hms.dbmi.avillach.gateway.ratelimit.RateLimitStore;
import edu.harvard.hms.dbmi.avillach.gateway.ratelimit.RateLimitSubject;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * A caller that pads its {@code X-Forwarded-For} past {@link ClientIpResolver#MAX_HOPS} moves the limiter onto the proxy, but every audit
 * event, through every registered gateway filter in configured order, still records the caller as {@code srcIp}: on BDC past the ALB the
 * caller's padding sits in front of, and on AIO as the hop httpd appended.
 */
class PaddedForwardedForAuditTest {

    private static final String HTTPD = "192.0.2.10";
    private static final String ALB = "10.0.1.5";
    private static final String CLIENT = "203.0.113.7";
    private static final String USER_KEY = "picsure_u_AbCdEfGh0123456789plaintextSecret";
    private static final String PADDING = String.join(", ", Collections.nCopies(ClientIpResolver.MAX_HOPS + 1, "6.6.6.6"));

    private static final List<String> BDC_PROXIES = List.of(HTTPD + "/32", "10.0.1.0/24");
    private static final String BDC_PADDED = PADDING + ", " + CLIENT + ", " + ALB;

    private static final HttpServlet UPSTREAM = new HttpServlet() {
        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp) {}
    };

    private final LoggingClient logging = mock(LoggingClient.class);
    private final PsamaClient psama = mock(PsamaClient.class);

    private MockHttpServletResponse send(List<String> trustedProxies, RateLimitStore store, String forwardedFor) throws Exception {
        when(logging.isEnabled()).thenReturn(true);
        AuditContext audit = new AuditContext();
        GatewaySecurityProperties props =
            new GatewaySecurityProperties(List.of(), true, 1024, "http://psama.local/introspect", "http://psama.local/open-access", "svc");
        RateLimitProperties rateLimit = new RateLimitProperties(
            true, new RateLimitProperties.KeyLimits(60, 3000), new RateLimitProperties.KeyLimits(60, 3000),
            new RateLimitProperties.IpLimits(60, 3000, 300), new RateLimitProperties.Store(1000), trustedProxies, List.of()
        );
        PublicEndpointPolicy publicEndpoints = new PublicEndpointPolicy(List.of());
        ClientIpResolver clientIps = new ClientIpResolver(trustedProxies.stream().map(CidrRange::parse).toList());
        SecurityConfig security = new SecurityConfig();
        ObservabilityConfig observability = new ObservabilityConfig();
        AuditFilterConfig auditConfig = new AuditFilterConfig();
        List<FilterRegistrationBean<?>> registrations = List.of(
            observability.requestIdFilter(), observability.accessLogFilter(),
            auditConfig.auditLoggingFilter(logging, auditConfig.auditRouteTable(), audit, rateLimit, clientIps),
            security.bufferingFilter(props, new SimpleMeterRegistry()),
            security.prevalidationThrottleFilter(props, publicEndpoints, rateLimit, clientIps, store, audit),
            observability.internalEndpointGuardFilter(), security.openAccessFilter(psama, audit, props, publicEndpoints),
            security.perKeyRateLimitFilter(rateLimit, clientIps, store, audit), observability.inboundIdentityHeaderSanitizingFilter()
        );
        Filter[] filters = registrations.stream().sorted(Comparator.comparingInt(FilterRegistrationBean::getOrder))
            .map(FilterRegistrationBean::getFilter).toArray(Filter[]::new);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/query/sync");
        request.setRemoteAddr(HTTPD);
        request.addHeader("X-Forwarded-For", forwardedFor);
        request.addHeader("Authorization", "Bearer " + USER_KEY);
        MockHttpServletResponse response = new MockHttpServletResponse();
        new MockFilterChain(UPSTREAM, filters).doFilter(request, response);
        return response;
    }

    private static RateLimitStore allowing() {
        return subject -> new RateLimitDecision(true, null, 0);
    }

    private static RateLimitStore breachingPrevalidation() {
        return subject -> subject.kind() == RateLimitSubject.Kind.PREVALIDATION_IP ? new RateLimitDecision(false, Window.BURST, 12)
            : new RateLimitDecision(true, null, 0);
    }

    private LoggingEvent emitted() {
        ArgumentCaptor<LoggingEvent> event = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(logging).send(event.capture(), any(), any());
        return event.getValue();
    }

    @Test
    void aGrantOnAPaddedBdcChainRecordsTheCallerAsSrcIp() throws Exception {
        when(psama.validateOpenAccess(anyMap())).thenReturn(new OpenAccessValidation(true, OpenAccessValidation.KEY_TYPE_USER, "key-id", "picsure_u_AbCd", null, null));

        MockHttpServletResponse response = send(BDC_PROXIES, allowing(), BDC_PADDED);

        assertThat(response.getStatus()).isEqualTo(200);
        LoggingEvent event = emitted();
        assertThat(event.getMetadata()).containsEntry("auth_action", "open_access.granted");
        assertThat(event.getRequest().getSrcIp()).isEqualTo(CLIENT);
    }

    @Test
    void aDenialOnAPaddedBdcChainRecordsTheCallerAsSrcIp() throws Exception {
        when(psama.validateOpenAccess(anyMap())).thenReturn(OpenAccessValidation.fromBoolean(false));

        MockHttpServletResponse response = send(BDC_PROXIES, allowing(), BDC_PADDED);

        assertThat(response.getStatus()).isEqualTo(401);
        LoggingEvent event = emitted();
        assertThat(event.getMetadata()).containsEntry("auth_action", "open_access.denied");
        assertThat(event.getRequest().getSrcIp()).isEqualTo(CLIENT);
    }

    @Test
    void aBreachOnAPaddedBdcChainBucketsOnHttpdButRecordsTheCallerAsSrcIp() throws Exception {
        MockHttpServletResponse response = send(BDC_PROXIES, breachingPrevalidation(), BDC_PADDED);

        assertThat(response.getStatus()).isEqualTo(429);
        LoggingEvent event = emitted();
        assertThat(event.getRequest().getSrcIp()).isEqualTo(CLIENT);
        assertThat(event.getMetadata()).containsEntry("rate_limit_subject", HTTPD).containsEntry("rate_limit_ip_resolution_failed", true);
    }

    @Test
    void aBreachOnAPaddedAioChainStillRecordsTheHopHttpdAppended() throws Exception {
        MockHttpServletResponse response = send(List.of(HTTPD + "/32"), breachingPrevalidation(), PADDING + ", " + CLIENT);

        assertThat(response.getStatus()).isEqualTo(429);
        LoggingEvent event = emitted();
        assertThat(event.getRequest().getSrcIp()).isEqualTo(CLIENT);
        assertThat(event.getMetadata()).containsEntry("rate_limit_subject", HTTPD).containsEntry("rate_limit_ip_resolution_failed", true);
    }

    @Test
    void aPaddedChainWhoseRightmostHopsAreAllTrustedKeepsTheRightmostHop() throws Exception {
        String trustedPadding = String.join(", ", Collections.nCopies(ClientIpResolver.MAX_HOPS, "10.0.1.6"));

        send(BDC_PROXIES, breachingPrevalidation(), PADDING + ", " + trustedPadding + ", " + ALB);

        assertThat(emitted().getRequest().getSrcIp()).isEqualTo(ALB);
    }
}
