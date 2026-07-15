package edu.harvard.hms.dbmi.avillach.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.hms.dbmi.avillach.commons.audit.AuditContext;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUserResolver;
import edu.harvard.hms.dbmi.avillach.gateway.auth.BufferedRequestWrapper;
import edu.harvard.hms.dbmi.avillach.gateway.auth.PsamaClient;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

class OpenAccessFilterTest {

    private OpenAccessFilter filter(PsamaClient client, AuditContext ctx, boolean enabled) {
        return new OpenAccessFilter(client, ctx, new ObjectMapper(), enabled);
    }

    @Test
    void enabledOpenAccessWithRealBearerPassesThroughToIntrospection() throws Exception {
        PsamaClient client = mock(PsamaClient.class);
        OpenAccessFilter f = filter(client, new AuditContext(), true);
        BufferedRequestWrapper req = wrap("Bearer real-token");
        FilterChain chain = mock(FilterChain.class);
        f.doFilter(req, mock(HttpServletResponse.class), chain);
        verify(chain).doFilter(eq(req), any());
        verifyNoInteractions(client);
    }

    @Test
    void enabledOpenAccessNoBearerSendsRealPathShapeAndGrants() throws Exception {
        PsamaClient client = mock(PsamaClient.class);
        when(client.validateOpenAccess(any())).thenReturn(true);
        AuditContext ctx = new AuditContext();
        OpenAccessFilter f = filter(client, ctx, true);

        BufferedRequestWrapper req = wrap(null); // URI is /v3/search/abc
        f.doFilter(req, mock(HttpServletResponse.class), mock(FilterChain.class));

        assertThat(req.getAttribute(GatewayUserResolver.HEADER_USER_ID).toString()).startsWith("OPEN_ACCESS:");
        assertThat(ctx.getMetadata()).containsEntry("auth_action", "open_access.granted");

        ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(client).validateOpenAccess(cap.capture());
        Map<String, Object> body = cap.getValue();
        assertThat(body).doesNotContainKey("token");
        assertThat(body.get("ipAddress").toString()).startsWith("OPEN_ACCESS:");
        @SuppressWarnings("unchecked")
        Map<String, Object> request = (Map<String, Object>) body.get("request");
        assertThat(request.get("Target Service")).isEqualTo("/v3/search/abc"); // real path verbatim
    }

    @Test
    void grantSetsDedicatedOpenAccessGrantAttribute() throws Exception {
        PsamaClient client = mock(PsamaClient.class);
        when(client.validateOpenAccess(any())).thenReturn(true);
        OpenAccessFilter f = filter(client, new AuditContext(), true);

        BufferedRequestWrapper req = wrap(null);
        f.doFilter(req, mock(HttpServletResponse.class), mock(FilterChain.class));

        assertThat(req.getAttribute(OpenAccessFilter.ATTR_OPEN_ACCESS_GRANTED)).isEqualTo(Boolean.TRUE);
    }

    @Test
    void deniedRequestDoesNotSetOpenAccessGrantAttribute() throws Exception {
        PsamaClient client = mock(PsamaClient.class);
        when(client.validateOpenAccess(any())).thenReturn(false);
        OpenAccessFilter f = filter(client, new AuditContext(), true);

        HttpServletResponse resp = mock(HttpServletResponse.class);
        lenient().when(resp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        BufferedRequestWrapper req = wrap(null);
        f.doFilter(req, resp, mock(FilterChain.class));

        assertThat(req.getAttribute(OpenAccessFilter.ATTR_OPEN_ACCESS_GRANTED)).isNull();
    }

    @Test
    void enabledOpenAccessFalseValidationReturns401() throws Exception {
        PsamaClient client = mock(PsamaClient.class);
        when(client.validateOpenAccess(any())).thenReturn(false);
        AuditContext ctx = new AuditContext();
        OpenAccessFilter f = filter(client, ctx, true);

        HttpServletResponse resp = mock(HttpServletResponse.class);
        lenient().when(resp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        FilterChain chain = mock(FilterChain.class);
        f.doFilter(wrap(null), resp, chain);

        verify(resp).setStatus(401);
        verify(chain, never()).doFilter(any(), any());
        assertThat(ctx.getMetadata()).containsEntry("auth_action", "open_access.denied");
    }

    private static BufferedRequestWrapper wrap(String authHeader) {
        HttpServletRequest base = mock(HttpServletRequest.class);
        when(base.getRequestURI()).thenReturn("/v3/search/abc");
        lenient().when(base.getMethod()).thenReturn("POST");
        if (authHeader != null) when(base.getHeader("Authorization")).thenReturn(authHeader);
        lenient().when(base.getServerName()).thenReturn("aio.local");
        // Bare Mockito mocks don't retain state across calls; BufferedRequestWrapper delegates
        // setAttribute/getAttribute to the wrapped request (HttpServletRequestWrapper default), so back
        // them with a real map here to exercise that delegation faithfully.
        Map<String, Object> attributes = new HashMap<>();
        lenient().doAnswer(inv -> {
            attributes.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(base).setAttribute(any(), any());
        lenient().when(base.getAttribute(any())).thenAnswer(inv -> attributes.get(inv.getArgument(0)));
        return new BufferedRequestWrapper(base, new byte[0]);
    }
}
