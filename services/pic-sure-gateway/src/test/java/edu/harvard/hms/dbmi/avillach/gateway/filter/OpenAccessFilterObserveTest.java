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
import edu.harvard.hms.dbmi.avillach.gateway.auth.GatewayAuthProperties;
import edu.harvard.hms.dbmi.avillach.gateway.auth.GatewayAuthScope;
import edu.harvard.hms.dbmi.avillach.gateway.auth.PsamaClient;
import edu.harvard.hms.dbmi.avillach.gateway.shadow.ShadowSupport;
import edu.harvard.hms.dbmi.avillach.gateway.shadow.ShadowTestAppender;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Task 5: in {@link GatewayAuthMode#OBSERVE}, {@code OpenAccessFilter} builds the open-access request shape it would otherwise validate,
 * emits one {@code SHADOW_GW} record (channel=open-access) via {@link ShadowSupport}, and forwards the request UNCHANGED -- no
 * {@code validateOpenAccess} call, no attribute mutation. Triggered on the same no-bearer-token precondition as the real enforce path;
 * requests carrying a real bearer token are left to {@code PsamaIntrospectionFilter}'s own observe branch (channel=introspection).
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
            client, new AuditContext(), new ObjectMapper(), SCOPE, openAccessEnabled, new GatewayAuthProperties(GatewayAuthMode.OBSERVE)
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

    private static BufferedRequestWrapper wrap(String authHeader, byte[] body) {
        HttpServletRequest base = mock(HttpServletRequest.class);
        when(base.getRequestURI()).thenReturn("/v3/search/abc");
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
