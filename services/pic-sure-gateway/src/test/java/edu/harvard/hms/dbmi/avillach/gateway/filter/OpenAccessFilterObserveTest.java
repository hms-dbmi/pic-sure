package edu.harvard.hms.dbmi.avillach.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import ch.qos.logback.classic.Logger;
import edu.harvard.hms.dbmi.avillach.commons.audit.AuditContext;
import edu.harvard.hms.dbmi.avillach.gateway.auth.BufferedRequestWrapper;
import edu.harvard.hms.dbmi.avillach.gateway.auth.GatewayAuthMode;
import edu.harvard.hms.dbmi.avillach.gateway.auth.GatewayAuthScope;
import edu.harvard.hms.dbmi.avillach.gateway.auth.GatewayModeResolver;
import edu.harvard.hms.dbmi.avillach.gateway.auth.PsamaClient;
import edu.harvard.hms.dbmi.avillach.gateway.config.RouteSurfaces;
import edu.harvard.hms.dbmi.avillach.gateway.shadow.ShadowSupport;
import edu.harvard.hms.dbmi.avillach.gateway.shadow.ShadowTestAppender;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * OBSERVE-mode behavior of {@code OpenAccessFilter}. On the legacy catch-all surface it builds the open-access request shape it would
 * otherwise validate, emits one {@code SHADOW_GW} record (channel=open-access) via {@link ShadowSupport}, and forwards the request
 * UNCHANGED -- no {@code validateOpenAccess} call, no attribute mutation. Triggered on the same no-bearer-token precondition as the real
 * enforce path; requests carrying a real bearer token are left to {@code PsamaIntrospectionFilter}'s own observe branch. Gateway-owned
 * routes run the real enforce path even in OBSERVE and emit NO shadow record.
 */
class OpenAccessFilterObserveTest {

    private static final GatewayAuthScope SCOPE = new GatewayAuthScope(false, List.of(".*/query/[^/]+/(?:result|signed-url)/?$"));

    private ShadowTestAppender appender;

    @AfterEach
    void detach() {
        if (appender != null) {
            ((Logger) org.slf4j.LoggerFactory.getLogger("picsure.shadow")).detachAppender(appender);
        }
    }

    private OpenAccessFilter observeFilter(PsamaClient client, boolean openAccessEnabled) {
        return new OpenAccessFilter(
            client, new AuditContext(), new ObjectMapper(), SCOPE, openAccessEnabled,
            new GatewayModeResolver(GatewayAuthMode.OBSERVE, RouteSurfaces.withDefaults())
        );
    }

    @Test
    void observeModeEmitsOpenAccessShadowAndDoesNotValidate() throws Exception {
        appender = ShadowTestAppender.attach("picsure.shadow");
        PsamaClient client = mock(PsamaClient.class);
        OpenAccessFilter f = observeFilter(client, false); // even with open-access disabled locally: shadow observation is unconditional
        BufferedRequestWrapper req = wrap(null, "{\"query\":{}}".getBytes());
        req.setAttribute(ShadowSupport.ATTR_CORRELATION_ID, "cid-oa");
        FilterChain chain = mock(FilterChain.class);

        f.doFilter(req, mock(HttpServletResponse.class), chain);

        verifyNoInteractions(client);
        verify(chain).doFilter(eq(req), any());
        assertThat(appender.lines()).hasSize(1);
        String line = appender.lines().get(0);
        assertThat(line).contains("\"channel\":\"open-access\"").contains("\"correlationId\":\"cid-oa\"")
            .contains("\"ipAddress\":\"OPEN_ACCESS:");
    }

    @Test
    void observeModeDoesNotEmitForBearerTokenRequests() throws Exception {
        appender = ShadowTestAppender.attach("picsure.shadow");
        PsamaClient client = mock(PsamaClient.class);
        OpenAccessFilter f = observeFilter(client, true);
        BufferedRequestWrapper req = wrap("Bearer real-token", new byte[0]);
        FilterChain chain = mock(FilterChain.class);

        f.doFilter(req, mock(HttpServletResponse.class), chain);

        verifyNoInteractions(client);
        verify(chain).doFilter(eq(req), any());
        assertThat(appender.lines()).isEmpty();
    }

    @Test
    void observeOwnedRouteEnforcesOpenAccessWithoutEmittingShadow() throws Exception {
        // Gateway-owned route with no bearer token in OBSERVE: the real validateOpenAccess call runs (enforce), and NO
        // shadow record is emitted -- owned routes are never observed.
        appender = ShadowTestAppender.attach("picsure.shadow");
        PsamaClient client = mock(PsamaClient.class);
        when(client.validateOpenAccess(any())).thenReturn(true);
        OpenAccessFilter f = observeFilter(client, true);
        BufferedRequestWrapper req = wrap(null, new byte[0], "/dictionary/concepts");
        FilterChain chain = mock(FilterChain.class);

        f.doFilter(req, mock(HttpServletResponse.class), chain);

        verify(client).validateOpenAccess(any()); // enforced, not observed
        assertThat(appender.lines()).isEmpty();
    }

    private static BufferedRequestWrapper wrap(String authHeader, byte[] body) {
        return wrap(authHeader, body, "/v3/search/abc");
    }

    private static BufferedRequestWrapper wrap(String authHeader, byte[] body, String uri) {
        HttpServletRequest base = mock(HttpServletRequest.class);
        when(base.getRequestURI()).thenReturn(uri);
        lenient().when(base.getMethod()).thenReturn("POST");
        lenient().when(base.getServerName()).thenReturn("aio.local");
        if (authHeader != null) when(base.getHeader("Authorization")).thenReturn(authHeader);
        Map<String, Object> attributes = new HashMap<>();
        lenient().doAnswer(inv -> {
            attributes.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(base).setAttribute(any(), any());
        lenient().when(base.getAttribute(any())).thenAnswer(inv -> attributes.get(inv.getArgument(0)));
        return new BufferedRequestWrapper(base, body);
    }
}
