package edu.harvard.hms.dbmi.avillach.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import edu.harvard.hms.dbmi.avillach.commons.audit.AuditContext;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUserResolver;
import edu.harvard.hms.dbmi.avillach.gateway.auth.BufferedRequestWrapper;
import edu.harvard.hms.dbmi.avillach.gateway.auth.IntrospectionResponse;
import edu.harvard.hms.dbmi.avillach.gateway.auth.PsamaClient;
import edu.harvard.hms.dbmi.avillach.gateway.auth.PublicEndpointPolicy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

class PsamaIntrospectionFilterTest {

    private PsamaIntrospectionFilter filter(PsamaClient client, AuditContext audit) {
        return new PsamaIntrospectionFilter(
            client, audit, new PublicEndpointPolicy(List.of("/actuator", "/openapi", "/swagger-ui", "/logging"))
        );
    }

    @Test
    void rejectsMissingAuthorizationHeader() throws Exception {
        PsamaClient client = mock(PsamaClient.class);
        HttpServletResponse response = responseWithWriter();
        FilterChain chain = mock(FilterChain.class);

        filter(client, new AuditContext()).doFilter(wrap(null, new byte[0], "/query"), response, chain);

        verify(response).setStatus(401);
        verify(chain, never()).doFilter(any(), any());
        verifyNoInteractions(client);
    }

    @Test
    void activeTokenStoresClaimsAndForwards() throws Exception {
        PsamaClient client = mock(PsamaClient.class);
        when(client.introspect(eq("user-token"), any()))
            .thenReturn(new IntrospectionResponse(true, "u-1", "s-1", "alice@example.com", "ADMIN", List.of("QUERY"), false, null, null));
        BufferedRequestWrapper request = wrap("Bearer user-token", new byte[0], "/hpds/auth/v3/query/sync");
        FilterChain chain = mock(FilterChain.class);

        filter(client, new AuditContext()).doFilter(request, mock(HttpServletResponse.class), chain);

        assertThat(request.getAttribute(GatewayUserResolver.HEADER_USER_ID)).isEqualTo("u-1");
        assertThat(request.getAttribute(GatewayUserResolver.HEADER_USER_PRIVILEGES)).isEqualTo("QUERY");
        verify(chain).doFilter(eq(request), any());
    }

    @Test
    void introspectionPayloadContainsOnlyDecodedNormalizedTargetService() throws Exception {
        PsamaClient client = mock(PsamaClient.class);
        when(client.introspect(eq("user-token"), any()))
            .thenReturn(new IntrospectionResponse(true, "u-1", "s-1", "a@b", "USER", List.of(), false, null, null));
        byte[] body = "{\"resourceCredentials\":{\"BEARER_TOKEN\":\"secret\"},\"query\":{\"a\":1}}".getBytes();

        filter(client, new AuditContext()).doFilter(
            wrap("Bearer user-token", body, "/hpds/%61uth//v3/query/sync"), mock(HttpServletResponse.class), mock(FilterChain.class)
        );

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> request = ArgumentCaptor.forClass(Map.class);
        verify(client).introspect(eq("user-token"), request.capture());
        assertThat(request.getValue()).containsExactly(Map.entry("Target Service", "/hpds/auth/v3/query/sync"));
        assertThat(request.getValue().toString()).doesNotContain("secret");
    }

    @Test
    void authorizationDenialReturnsForbiddenWithCause() throws Exception {
        PsamaClient client = mock(PsamaClient.class);
        when(client.introspect(any(), any()))
            .thenReturn(new IntrospectionResponse(false, "u-1", "s-1", null, null, null, null, null, "User has no consents on file."));
        StringWriter body = new StringWriter();
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        filter(client, new AuditContext())
            .doFilter(wrap("Bearer user-token", new byte[0], "/dictionary/concepts"), response, mock(FilterChain.class));

        verify(response).setStatus(403);
        assertThat(body.toString()).contains("User has no consents on file.");
    }

    @Test
    void refreshedTokenIsRetainedWithoutAnyMutatedQueryAttribute() throws Exception {
        PsamaClient client = mock(PsamaClient.class);
        when(client.introspect(any(), any()))
            .thenReturn(new IntrospectionResponse(true, "u-1", "s-1", "a@b", "USER", List.of(), true, "new-token", null));
        BufferedRequestWrapper request = wrap("Bearer user-token", "{\"query\":{}}".getBytes(), "/query");

        filter(client, new AuditContext()).doFilter(request, mock(HttpServletResponse.class), mock(FilterChain.class));

        assertThat(request.getAttribute(PsamaIntrospectionFilter.ATTR_REFRESHED_TOKEN)).isEqualTo("new-token");
        assertThat(request.getAttribute("mutatedQuery")).isNull();
    }

    @Test
    void publicEndpointSkipsIntrospection() throws Exception {
        PsamaClient client = mock(PsamaClient.class);
        BufferedRequestWrapper request = wrap(null, new byte[0], "/logging/audit", "GET");
        FilterChain chain = mock(FilterChain.class);

        filter(client, new AuditContext()).doFilter(request, mock(HttpServletResponse.class), chain);

        verifyNoInteractions(client);
        verify(chain).doFilter(eq(request), any());
    }

    private static HttpServletResponse responseWithWriter() throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        return response;
    }

    private static BufferedRequestWrapper wrap(String authorization, byte[] body, String uri) {
        return wrap(authorization, body, uri, "POST");
    }

    private static BufferedRequestWrapper wrap(String authorization, byte[] body, String uri, String method) {
        HttpServletRequest base = mock(HttpServletRequest.class);
        when(base.getRequestURI()).thenReturn(uri);
        when(base.getContextPath()).thenReturn("");
        when(base.getMethod()).thenReturn(method);
        when(base.getCharacterEncoding()).thenReturn("UTF-8");
        if (authorization != null) {
            when(base.getHeader("Authorization")).thenReturn(authorization);
        }
        Map<String, Object> attributes = new HashMap<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            attributes.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(base).setAttribute(any(), any());
        when(base.getAttribute(any())).thenAnswer(invocation -> attributes.get(invocation.getArgument(0)));
        return new BufferedRequestWrapper(base, body);
    }
}
