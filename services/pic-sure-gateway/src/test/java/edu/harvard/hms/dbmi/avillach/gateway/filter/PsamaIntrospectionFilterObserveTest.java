package edu.harvard.hms.dbmi.avillach.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import com.fasterxml.jackson.databind.ObjectMapper;

import ch.qos.logback.classic.Logger;
import edu.harvard.hms.dbmi.avillach.commons.audit.AuditContext;
import edu.harvard.hms.dbmi.avillach.gateway.auth.BufferedRequestWrapper;
import edu.harvard.hms.dbmi.avillach.gateway.auth.GatewayAuthScope;
import edu.harvard.hms.dbmi.avillach.gateway.auth.GatewayModeResolver;
import edu.harvard.hms.dbmi.avillach.gateway.auth.IntrospectionResponse;
import edu.harvard.hms.dbmi.avillach.gateway.auth.PsamaClient;
import edu.harvard.hms.dbmi.avillach.gateway.auth.QueryAuthFetcher;
import edu.harvard.hms.dbmi.avillach.gateway.shadow.ShadowSupport;
import edu.harvard.hms.dbmi.avillach.gateway.shadow.ShadowTestAppender;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * OBSERVE-mode behavior of {@code PsamaIntrospectionFilter}. The observe branch is taken ONLY on the legacy catch-all surface: the filter
 * builds the exact introspection request it would otherwise send, emits one {@code SHADOW_GW} record (channel=introspection) via
 * {@link ShadowSupport}, and forwards the request UNCHANGED -- no PSAMA call, no body/attribute mutation (WildFly stays the sole enforcer
 * there). Gateway-owned routes ({@code /hpds}, {@code /dictionary}, ...) run the full enforce path even in OBSERVE and emit NO shadow
 * record.
 */
class PsamaIntrospectionFilterObserveTest {

    private static final GatewayAuthScope SCOPE = new GatewayAuthScope(false, List.of(".*/query/[^/]+/(?:result|signed-url)/?$"));

    private ShadowTestAppender appender;

    @AfterEach
    void detach() {
        if (appender != null) {
            ((Logger) org.slf4j.LoggerFactory.getLogger("picsure.shadow")).detachAppender(appender);
        }
    }

    private PsamaIntrospectionFilter observeFilter(PsamaClient client, QueryAuthFetcher fetcher) {
        return new PsamaIntrospectionFilter(
            client, new AuditContext(), new ObjectMapper(), fetcher, SCOPE, List.of("/actuator", "/openapi", "/swagger-ui", "/logging"),
            "userId", GatewayModeResolver.observing()
        );
    }

    @Test
    void observeModeEmitsShadowAndDoesNotCallPsama() throws Exception {
        appender = ShadowTestAppender.attach("picsure.shadow");
        PsamaClient client = mock(PsamaClient.class);
        QueryAuthFetcher fetcher = mock(QueryAuthFetcher.class);
        when(fetcher.queryJsonForPath(any())).thenReturn(Optional.empty());
        PsamaIntrospectionFilter f = observeFilter(client, fetcher);

        BufferedRequestWrapper req = wrap("Bearer abc", "{\"resourceUUID\":\"x\",\"query\":{}}".getBytes(), "/picsure/query/sync");
        req.setAttribute(ShadowSupport.ATTR_CORRELATION_ID, "cid-9");
        FilterChain chain = mock(FilterChain.class);

        f.doFilter(req, mock(HttpServletResponse.class), chain);

        verifyNoInteractions(client);
        verify(chain).doFilter(ArgumentMatchers.eq(req), any());
        assertThat(appender.lines()).hasSize(1);
        String line = appender.lines().get(0);
        assertThat(line).contains("\"side\":\"GW\"").contains("\"correlationId\":\"cid-9\"").contains("\"channel\":\"introspection\"")
            .contains("\"targetService\":\"/picsure/query/sync\"");
    }

    @Test
    void observeModeForwardsWithoutTokenAndDoesNotDeny() throws Exception {
        appender = ShadowTestAppender.attach("picsure.shadow");
        PsamaClient client = mock(PsamaClient.class);
        QueryAuthFetcher fetcher = mock(QueryAuthFetcher.class);
        when(fetcher.queryJsonForPath(any())).thenReturn(Optional.empty());
        PsamaIntrospectionFilter f = observeFilter(client, fetcher);

        BufferedRequestWrapper req = wrap(null, new byte[0], "/picsure/query/sync");
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        f.doFilter(req, resp, chain);

        verifyNoInteractions(client);
        verify(chain).doFilter(ArgumentMatchers.eq(req), any());
        // never denies in observe mode, even without a bearer token
        org.mockito.Mockito.verify(resp, org.mockito.Mockito.never()).setStatus(401);
        assertThat(appender.lines()).hasSize(1);
        assertThat(appender.lines().get(0)).contains("\"tokenHash\":null");
    }

    @Test
    void observeModeSkipsAllowListedPathsWithoutEmittingShadow() throws Exception {
        appender = ShadowTestAppender.attach("picsure.shadow");
        PsamaClient client = mock(PsamaClient.class);
        PsamaIntrospectionFilter f = observeFilter(client, mock(QueryAuthFetcher.class));

        BufferedRequestWrapper req = wrap(null, new byte[0], "/logging/event");
        FilterChain chain = mock(FilterChain.class);

        f.doFilter(req, mock(HttpServletResponse.class), chain);

        verifyNoInteractions(client);
        verify(chain).doFilter(ArgumentMatchers.eq(req), any());
        assertThat(appender.lines()).isEmpty();
    }

    @Test
    void observeOwnedRouteEnforcesCallingPsamaAndAppliesMutationWithoutEmittingShadow() throws Exception {
        // Gateway-owned routes have no WildFly counterpart, so OBSERVE still runs the full enforce path there (and emits
        // NO shadow record -- an owned-route SHADOW_GW would be an unpaired record).
        appender = ShadowTestAppender.attach("picsure.shadow");
        PsamaClient client = mock(PsamaClient.class);
        QueryAuthFetcher fetcher = mock(QueryAuthFetcher.class);
        when(fetcher.queryJsonForPath(any())).thenReturn(Optional.empty());
        when(client.introspect(ArgumentMatchers.eq("user-token"), any()))
            .thenReturn(new IntrospectionResponse(true, "u-1", "s-1", "a@b", "ADMIN", List.of("SUPER_ADMIN"), false, null, "{\"new\":1}"));
        PsamaIntrospectionFilter f = observeFilter(client, fetcher);

        BufferedRequestWrapper req = wrap("Bearer user-token", "{\"query\":{}}".getBytes(), "/hpds/auth/v3/query/sync");
        FilterChain chain = mock(FilterChain.class);

        f.doFilter(req, mock(HttpServletResponse.class), chain);

        verify(client).introspect(ArgumentMatchers.eq("user-token"), any()); // enforced, not observed
        assertThat(req.getAttribute(BodyMutationFilter.ATTR_MUTATED_QUERY)).isEqualTo("{\"new\":1}");
        verify(chain).doFilter(ArgumentMatchers.eq(req), any());
        assertThat(appender.lines()).isEmpty(); // no SHADOW_GW for an owned route
    }

    @Test
    void observeOwnedRouteRejectsUnauthenticatedExactlyAsEnforce() throws Exception {
        // Unauthenticated request to a gateway-owned route (e.g. /dictionary/**) is 401'd, not forwarded -- an observe
        // window must never unprotect owned routes.
        appender = ShadowTestAppender.attach("picsure.shadow");
        PsamaClient client = mock(PsamaClient.class);
        PsamaIntrospectionFilter f = observeFilter(client, mock(QueryAuthFetcher.class));

        BufferedRequestWrapper req = wrap(null, new byte[0], "/dictionary/concepts");
        HttpServletResponse resp = mock(HttpServletResponse.class);
        org.mockito.Mockito.lenient().when(resp.getWriter()).thenReturn(new java.io.PrintWriter(new java.io.StringWriter()));
        FilterChain chain = mock(FilterChain.class);

        f.doFilter(req, resp, chain);

        verifyNoInteractions(client);
        verify(chain, org.mockito.Mockito.never()).doFilter(any(), any());
        verify(resp).setStatus(401);
        assertThat(appender.lines()).isEmpty();
    }

    @Test
    void observeCatchAllShadowBuildFailureDoesNotAffectResponse() throws Exception {
        // A shadow-side failure (e.g. QueryAuthFetcher blowing up) must be swallowed and the request forwarded unchanged.
        appender = ShadowTestAppender.attach("picsure.shadow");
        PsamaClient client = mock(PsamaClient.class);
        QueryAuthFetcher fetcher = mock(QueryAuthFetcher.class);
        when(fetcher.queryJsonForPath(any())).thenThrow(new RuntimeException("boom"));
        PsamaIntrospectionFilter f = observeFilter(client, fetcher);

        BufferedRequestWrapper req = wrap("Bearer abc", "{\"query\":{}}".getBytes(), "/picsure/query/sync");
        FilterChain chain = mock(FilterChain.class);

        f.doFilter(req, mock(HttpServletResponse.class), chain); // must not throw

        verifyNoInteractions(client);
        verify(chain).doFilter(ArgumentMatchers.eq(req), any()); // forwarded unchanged despite the shadow failure
        assertThat(appender.lines()).isEmpty();
    }

    private static BufferedRequestWrapper wrap(String authHeader, byte[] body, String uri) {
        HttpServletRequest base = mock(HttpServletRequest.class);
        if (authHeader != null) when(base.getHeader("Authorization")).thenReturn(authHeader);
        when(base.getRequestURI()).thenReturn(uri);
        org.mockito.Mockito.lenient().when(base.getMethod()).thenReturn("POST");
        Map<String, Object> attributes = new HashMap<>();
        org.mockito.Mockito.lenient().doAnswer(inv -> {
            attributes.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(base).setAttribute(any(), any());
        org.mockito.Mockito.lenient().when(base.getAttribute(any())).thenAnswer(inv -> attributes.get(inv.getArgument(0)));
        return new BufferedRequestWrapper(base, body);
    }
}
