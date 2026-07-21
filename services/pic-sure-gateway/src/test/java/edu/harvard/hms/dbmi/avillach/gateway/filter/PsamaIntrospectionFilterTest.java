package edu.harvard.hms.dbmi.avillach.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.hms.dbmi.avillach.commons.audit.AuditContext;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUserResolver;
import edu.harvard.hms.dbmi.avillach.gateway.auth.BufferedRequestWrapper;
import edu.harvard.hms.dbmi.avillach.gateway.auth.IntrospectionResponse;
import edu.harvard.hms.dbmi.avillach.gateway.auth.PsamaClient;
import edu.harvard.hms.dbmi.avillach.gateway.auth.QueryAuthFetcher;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

class PsamaIntrospectionFilterTest {

    private PsamaIntrospectionFilter filter(PsamaClient client, AuditContext ctx, QueryAuthFetcher fetcher) {
        return new PsamaIntrospectionFilter(
            client, ctx, new ObjectMapper(), fetcher, List.of("/actuator", "/openapi", "/swagger-ui", "/logging"), "userId"
        );
    }

    @Test
    void rejectsMissingAuthorizationHeader() throws Exception {
        PsamaClient client = mock(PsamaClient.class);
        QueryAuthFetcher fetcher = mock(QueryAuthFetcher.class);
        AuditContext ctx = new AuditContext();
        PsamaIntrospectionFilter f = filter(client, ctx, fetcher);

        BufferedRequestWrapper req = wrap(null, new byte[0], "/query");
        HttpServletResponse resp = mock(HttpServletResponse.class);
        lenient().when(resp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        FilterChain chain = mock(FilterChain.class);

        f.doFilter(req, resp, chain);

        verify(resp).setStatus(401);
        verify(chain, never()).doFilter(any(), any());
        assertThat(ctx.getMetadata()).containsEntry("auth_failure_reason", "missing_token");
    }

    @Test
    void forwardsRequestAlreadyGrantedByOpenAccessFilterWithoutBearerToken() throws Exception {
        PsamaClient client = mock(PsamaClient.class);
        QueryAuthFetcher fetcher = mock(QueryAuthFetcher.class);
        AuditContext ctx = new AuditContext();
        PsamaIntrospectionFilter f = filter(client, ctx, fetcher);

        BufferedRequestWrapper req = wrap(null, "{}".getBytes(), "/hpds/open/query/sync");
        req.setAttribute(GatewayUserResolver.HEADER_USER_ID, "OPEN_ACCESS:localhost");
        req.setAttribute(OpenAccessFilter.ATTR_OPEN_ACCESS_GRANTED, Boolean.TRUE);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        lenient().when(resp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        FilterChain chain = mock(FilterChain.class);

        f.doFilter(req, resp, chain);

        verify(chain).doFilter(eq(req), any());
        verify(resp, never()).setStatus(401);
        verifyNoInteractions(client);
    }

    @Test
    void openAccessGrantAttributeAloneForwardsWithoutBearerToken() throws Exception {
        PsamaClient client = mock(PsamaClient.class);
        QueryAuthFetcher fetcher = mock(QueryAuthFetcher.class);
        AuditContext ctx = new AuditContext();
        PsamaIntrospectionFilter f = filter(client, ctx, fetcher);

        BufferedRequestWrapper req = wrap(null, "{}".getBytes(), "/hpds/open/query/sync");
        req.setAttribute(OpenAccessFilter.ATTR_OPEN_ACCESS_GRANTED, Boolean.TRUE);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        lenient().when(resp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        FilterChain chain = mock(FilterChain.class);

        f.doFilter(req, resp, chain);

        verify(chain).doFilter(eq(req), any());
        verify(resp, never()).setStatus(401);
        verifyNoInteractions(client);
    }

    @Test
    void userIdAttributeWithoutOpenAccessGrantDoesNotBypassIntrospection() throws Exception {
        PsamaClient client = mock(PsamaClient.class);
        QueryAuthFetcher fetcher = mock(QueryAuthFetcher.class);
        AuditContext ctx = new AuditContext();
        PsamaIntrospectionFilter f = filter(client, ctx, fetcher);

        // Some hypothetical earlier filter resolved an identity WITHOUT an open-access grant; introspection must still run.
        BufferedRequestWrapper req = wrap(null, "{}".getBytes(), "/query");
        req.setAttribute(GatewayUserResolver.HEADER_USER_ID, "some-internal-identity");
        HttpServletResponse resp = mock(HttpServletResponse.class);
        lenient().when(resp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        FilterChain chain = mock(FilterChain.class);

        f.doFilter(req, resp, chain);

        verify(resp).setStatus(401);
        verify(chain, never()).doFilter(any(), any());
        assertThat(ctx.getMetadata()).containsEntry("auth_failure_reason", "missing_token");
    }

    @Test
    void onActiveTokenStoresClaimsIncludingPrivilegesAndForwards() throws Exception {
        PsamaClient client = mock(PsamaClient.class);
        QueryAuthFetcher fetcher = mock(QueryAuthFetcher.class);
        when(fetcher.queryJsonForPath(any())).thenReturn(Optional.empty());
        when(client.introspect(eq("user-token"), any())).thenReturn(
            new IntrospectionResponse(true, "u-1", "s-1", "alice@example.com", "ADMIN", List.of("SUPER_ADMIN"), false, null, null)
        );
        AuditContext ctx = new AuditContext();
        PsamaIntrospectionFilter f = filter(client, ctx, fetcher);

        BufferedRequestWrapper req = wrap("Bearer user-token", "{}".getBytes(), "/query");
        HttpServletResponse resp = mock(HttpServletResponse.class);
        lenient().when(resp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        FilterChain chain = mock(FilterChain.class);
        f.doFilter(req, resp, chain);

        assertThat(req.getAttribute(GatewayUserResolver.HEADER_USER_ID)).isEqualTo("u-1");
        assertThat(req.getAttribute(GatewayUserResolver.HEADER_USER_EMAIL)).isEqualTo("alice@example.com");
        assertThat(req.getAttribute(GatewayUserResolver.HEADER_USER_PRIVILEGES)).isEqualTo("SUPER_ADMIN");
        assertThat(ctx.getMetadata()).containsEntry("auth_result", "success");
        verify(chain).doFilter(req, resp);
    }

    @Test
    void onActiveTokenWithMultiplePrivilegesJoinsThemWithComma() throws Exception {
        PsamaClient client = mock(PsamaClient.class);
        QueryAuthFetcher fetcher = mock(QueryAuthFetcher.class);
        when(fetcher.queryJsonForPath(any())).thenReturn(Optional.empty());
        when(client.introspect(eq("user-token"), any())).thenReturn(
            new IntrospectionResponse(
                true, "u-1", "s-1", "alice@example.com", "ADMIN", List.of("SUPER_ADMIN", "DATA_ADMIN", "USER"), false, null, null
            )
        );
        AuditContext ctx = new AuditContext();
        PsamaIntrospectionFilter f = filter(client, ctx, fetcher);

        BufferedRequestWrapper req = wrap("Bearer user-token", "{}".getBytes(), "/query");
        HttpServletResponse resp = mock(HttpServletResponse.class);
        lenient().when(resp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        FilterChain chain = mock(FilterChain.class);
        f.doFilter(req, resp, chain);

        assertThat(req.getAttribute(GatewayUserResolver.HEADER_USER_PRIVILEGES)).isEqualTo("SUPER_ADMIN,DATA_ADMIN,USER");
        verify(chain).doFilter(req, resp);
    }

    @Test
    void sendsRealPathAsTargetServiceAndStripsResourceCredentials() throws Exception {
        PsamaClient client = mock(PsamaClient.class);
        QueryAuthFetcher fetcher = mock(QueryAuthFetcher.class);
        when(fetcher.queryJsonForPath(any())).thenReturn(Optional.empty());
        when(client.introspect(eq("user-token"), any()))
            .thenReturn(new IntrospectionResponse(true, "u-1", "s-1", "a@b", "ADMIN", List.of(), false, null, null));
        PsamaIntrospectionFilter f = filter(client, new AuditContext(), fetcher);

        byte[] body = "{\"resourceCredentials\":{\"BEARER_TOKEN\":\"secret\"},\"query\":{\"a\":1}}".getBytes();
        // real path is sent verbatim: no /v3 rewriting, no canonical mapping
        BufferedRequestWrapper req = wrap("Bearer user-token", body, "/v3/query");
        HttpServletResponse resp = mock(HttpServletResponse.class);
        lenient().when(resp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        f.doFilter(req, resp, mock(FilterChain.class));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> meta = ArgumentCaptor.forClass(Map.class);
        verify(client).introspect(eq("user-token"), meta.capture());
        assertThat(meta.getValue().get("Target Service")).isEqualTo("/v3/query");
        assertThat(meta.getValue().toString()).doesNotContain("resourceCredentials");
        assertThat(meta.getValue().toString()).doesNotContain("formattedQuery");
    }

    @Test
    void malformedJsonBodyOmitsQueryAndDoesNotLeakSecrets() throws Exception {
        PsamaClient client = mock(PsamaClient.class);
        QueryAuthFetcher fetcher = mock(QueryAuthFetcher.class);
        when(fetcher.queryJsonForPath(any())).thenReturn(Optional.empty());
        when(client.introspect(eq("user-token"), any()))
            .thenReturn(new IntrospectionResponse(true, "u-1", "s-1", "a@b", "ADMIN", List.of(), false, null, null));
        PsamaIntrospectionFilter f = filter(client, new AuditContext(), fetcher);

        // Truncated/invalid JSON that still textually contains a resourceCredentials secret.
        byte[] malformed = "{\"resourceCredentials\":{\"BEARER_TOKEN\":\"super-secret-value\"".getBytes();
        BufferedRequestWrapper req = wrap("Bearer user-token", malformed, "/query");
        HttpServletResponse resp = mock(HttpServletResponse.class);
        lenient().when(resp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        f.doFilter(req, resp, mock(FilterChain.class));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> meta = ArgumentCaptor.forClass(Map.class);
        verify(client).introspect(eq("user-token"), meta.capture());
        assertThat(meta.getValue()).doesNotContainKey("query");
        assertThat(meta.getValue().toString()).doesNotContain("super-secret-value");
    }

    @Test
    void invalidTokenReturns401() throws Exception {
        PsamaClient client = mock(PsamaClient.class);
        QueryAuthFetcher fetcher = mock(QueryAuthFetcher.class);
        when(fetcher.queryJsonForPath(any())).thenReturn(Optional.empty());
        when(client.introspect(any(), any())).thenReturn(new IntrospectionResponse(false, null, null, null, null, null, null, null, null));
        PsamaIntrospectionFilter f = filter(client, new AuditContext(), fetcher);

        BufferedRequestWrapper req = wrap("Bearer user-token", "{}".getBytes(), "/query");
        HttpServletResponse resp = mock(HttpServletResponse.class);
        lenient().when(resp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        FilterChain chain = mock(FilterChain.class);
        f.doFilter(req, resp, chain);

        verify(resp).setStatus(401);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void stashesRefreshedTokenAndMutatedQueryAttributes() throws Exception {
        PsamaClient client = mock(PsamaClient.class);
        QueryAuthFetcher fetcher = mock(QueryAuthFetcher.class);
        when(fetcher.queryJsonForPath(any())).thenReturn(Optional.empty());
        when(client.introspect(any(), any()))
            .thenReturn(new IntrospectionResponse(true, "u-1", "s-1", "a@b", "ADMIN", List.of(), true, "new-token", "{\"new\":1}"));
        PsamaIntrospectionFilter f = filter(client, new AuditContext(), fetcher);

        BufferedRequestWrapper req = wrap("Bearer user-token", "{}".getBytes(), "/query");
        HttpServletResponse resp = mock(HttpServletResponse.class);
        lenient().when(resp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        f.doFilter(req, resp, mock(FilterChain.class));

        assertThat(req.getAttribute(PsamaIntrospectionFilter.ATTR_REFRESHED_TOKEN)).isEqualTo("new-token");
        assertThat(req.getAttribute(BodyMutationFilter.ATTR_MUTATED_QUERY)).isEqualTo("{\"new\":1}");
    }

    @Test
    void systemStatusGetIsAllowListedAsSystemMonitor() throws Exception {
        PsamaClient client = mock(PsamaClient.class);
        AuditContext ctx = new AuditContext();
        PsamaIntrospectionFilter f = filter(client, ctx, mock(QueryAuthFetcher.class));

        BufferedRequestWrapper req = wrap(null, new byte[0], "/system/status", "GET");
        FilterChain chain = mock(FilterChain.class);
        f.doFilter(req, mock(HttpServletResponse.class), chain);

        verifyNoInteractions(client);
        verify(chain).doFilter(eq(req), any());
        assertThat(ctx.getMetadata()).containsEntry("username", "SYSTEM_MONITOR");
    }

    @Test
    void prefixAllowListSkipsIntrospection() throws Exception {
        PsamaClient client = mock(PsamaClient.class);
        PsamaIntrospectionFilter f = filter(client, new AuditContext(), mock(QueryAuthFetcher.class));

        BufferedRequestWrapper req = wrap(null, new byte[0], "/logging/event");
        FilterChain chain = mock(FilterChain.class);
        f.doFilter(req, mock(HttpServletResponse.class), chain);

        verifyNoInteractions(client);
        verify(chain).doFilter(eq(req), any());
    }

    @Test
    void nestedSystemStatusPathIsNotAllowListed() throws Exception {
        PsamaClient client = mock(PsamaClient.class);
        AuditContext ctx = new AuditContext();
        PsamaIntrospectionFilter f = filter(client, ctx, mock(QueryAuthFetcher.class));

        BufferedRequestWrapper req = wrap(null, new byte[0], "/foo/system/status", "GET");
        HttpServletResponse resp = mock(HttpServletResponse.class);
        lenient().when(resp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        FilterChain chain = mock(FilterChain.class);
        f.doFilter(req, resp, chain);

        verifyNoInteractions(client);
        verify(chain, never()).doFilter(any(), any());
        verify(resp).setStatus(401);
        assertThat(ctx.getMetadata()).doesNotContainEntry("username", "SYSTEM_MONITOR");
    }

    @Test
    void prefixAllowListDoesNotMatchSiblingPathWithSamePrefixText() throws Exception {
        PsamaClient client = mock(PsamaClient.class);
        PsamaIntrospectionFilter f = filter(client, new AuditContext(), mock(QueryAuthFetcher.class));

        // Allow-listed prefix is "/logging"; "/loggingAdmin/x" must NOT match it.
        BufferedRequestWrapper req = wrap(null, new byte[0], "/loggingAdmin/x");
        HttpServletResponse resp = mock(HttpServletResponse.class);
        lenient().when(resp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        FilterChain chain = mock(FilterChain.class);
        f.doFilter(req, resp, chain);

        verifyNoInteractions(client);
        verify(chain, never()).doFilter(any(), any());
        verify(resp).setStatus(401);
    }

    @Test
    void publicConfigurationRootGetIsNotIntrospected() throws Exception {
        PsamaIntrospectionFilter f = filter(mock(PsamaClient.class), new AuditContext(), mock(QueryAuthFetcher.class));
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn("/operations/configuration/");
        when(req.getMethod()).thenReturn("GET");
        assertThat(f.shouldNotFilter(req)).isTrue();
    }

    @Test
    void publicConfigurationRootGetWithoutTrailingSlashIsNotIntrospected() throws Exception {
        // The slash-less form is what the controller actually serves (Spring 6 has no lenient slash matching).
        PsamaIntrospectionFilter f = filter(mock(PsamaClient.class), new AuditContext(), mock(QueryAuthFetcher.class));
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn("/operations/configuration");
        when(req.getMethod()).thenReturn("GET");
        assertThat(f.shouldNotFilter(req)).isTrue();
    }

    @Test
    void publicConfigurationIdReadGetIsNotIntrospected() throws Exception {
        PsamaIntrospectionFilter f = filter(mock(PsamaClient.class), new AuditContext(), mock(QueryAuthFetcher.class));
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn("/operations/configuration/3f2c8e1a-uuid/");
        when(req.getMethod()).thenReturn("GET");
        assertThat(f.shouldNotFilter(req)).isTrue();
    }

    @Test
    void publicConfigurationIdReadGetWithoutTrailingSlashIsNotIntrospected() throws Exception {
        PsamaIntrospectionFilter f = filter(mock(PsamaClient.class), new AuditContext(), mock(QueryAuthFetcher.class));
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn("/operations/configuration/3f2c8e1a-uuid");
        when(req.getMethod()).thenReturn("GET");
        assertThat(f.shouldNotFilter(req)).isTrue();
    }

    @Test
    void configurationAdminGetIsIntrospected() throws Exception {
        PsamaIntrospectionFilter f = filter(mock(PsamaClient.class), new AuditContext(), mock(QueryAuthFetcher.class));
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn("/operations/configuration/admin/x");
        when(req.getMethod()).thenReturn("GET");
        assertThat(f.shouldNotFilter(req)).isFalse();
    }

    @Test
    void configurationAdminRootGetIsIntrospected() throws Exception {
        PsamaIntrospectionFilter f = filter(mock(PsamaClient.class), new AuditContext(), mock(QueryAuthFetcher.class));
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn("/operations/configuration/admin");
        when(req.getMethod()).thenReturn("GET");
        assertThat(f.shouldNotFilter(req)).isFalse();
    }

    @Test
    void configurationAdminPostIsIntrospected() throws Exception {
        PsamaIntrospectionFilter f = filter(mock(PsamaClient.class), new AuditContext(), mock(QueryAuthFetcher.class));
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn("/operations/configuration/admin/x");
        when(req.getMethod()).thenReturn("POST");
        assertThat(f.shouldNotFilter(req)).isFalse();
    }

    @Test
    void configurationRootPostIsIntrospected() throws Exception {
        PsamaIntrospectionFilter f = filter(mock(PsamaClient.class), new AuditContext(), mock(QueryAuthFetcher.class));
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn("/operations/configuration/");
        when(req.getMethod()).thenReturn("POST");
        assertThat(f.shouldNotFilter(req)).isFalse();
    }

    @Test
    void configurationIdReadPostIsIntrospected() throws Exception {
        PsamaIntrospectionFilter f = filter(mock(PsamaClient.class), new AuditContext(), mock(QueryAuthFetcher.class));
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn("/operations/configuration/abc-123/");
        when(req.getMethod()).thenReturn("POST");
        assertThat(f.shouldNotFilter(req)).isFalse();
    }

    @Test
    void datasetPathIsIntrospected() throws Exception {
        PsamaIntrospectionFilter f = filter(mock(PsamaClient.class), new AuditContext(), mock(QueryAuthFetcher.class));
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn("/operations/dataset/named/abc-123");
        when(req.getMethod()).thenReturn("GET");
        assertThat(f.shouldNotFilter(req)).isFalse();
    }

    /**
     * A dispatch failure that is NOT a PicsureException must still deny with a shaped body. This is the exact production shape of an unset
     * OPERATIONS_SERVICE_URL: QueryAuthFetcher only converts RestClientException subtypes, so RestClient's IllegalArgumentException("URI
     * with undefined scheme") used to escape the filter entirely -- and because a servlet filter's exceptions never reach Spring MVC's
     * exception resolvers, it surfaced as Boot's unshaped 500 on downloads alone.
     */
    @Test
    void deniesWithShapedBodyWhenDispatchFailsWithANonPicsureException() throws Exception {
        PsamaClient client = mock(PsamaClient.class);
        QueryAuthFetcher fetcher = mock(QueryAuthFetcher.class);
        when(fetcher.queryJsonForPath(any())).thenThrow(new IllegalArgumentException("URI with undefined scheme"));
        AuditContext ctx = new AuditContext();
        PsamaIntrospectionFilter f = filter(client, ctx, fetcher);

        BufferedRequestWrapper req =
            wrap("Bearer user-token", "{}".getBytes(), "/hpds/auth/v3/query/47a6655a-5e2a-4785-ac8e-e0f7e41c718f/result");
        StringWriter written = new StringWriter();
        HttpServletResponse resp = mock(HttpServletResponse.class);
        when(resp.getWriter()).thenReturn(new PrintWriter(written, true));
        FilterChain chain = mock(FilterChain.class);

        f.doFilter(req, resp, chain);

        verify(resp).setStatus(502);
        assertThat(written.toString()).contains("\"errorType\":\"dispatch_failed\"");
        assertThat(ctx.getMetadata()).containsEntry("auth_failure_reason", "dispatch_failed");
        // Fail closed: the request must never reach the downstream route, and PSAMA is never consulted.
        verify(chain, never()).doFilter(any(), any());
        verifyNoInteractions(client);
    }

    private static BufferedRequestWrapper wrap(String authHeader, byte[] body, String uri) {
        return wrap(authHeader, body, uri, "POST");
    }

    private static BufferedRequestWrapper wrap(String authHeader, byte[] body, String uri, String method) {
        HttpServletRequest base = mock(HttpServletRequest.class);
        if (authHeader != null) when(base.getHeader("Authorization")).thenReturn(authHeader);
        when(base.getRequestURI()).thenReturn(uri);
        lenient().when(base.getMethod()).thenReturn(method);
        // Bare Mockito mocks don't retain state across calls; BufferedRequestWrapper delegates
        // setAttribute/getAttribute to the wrapped request (HttpServletRequestWrapper default), so back
        // them with a real map here to exercise that delegation faithfully.
        Map<String, Object> attributes = new HashMap<>();
        lenient().doAnswer(inv -> {
            attributes.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(base).setAttribute(any(), any());
        lenient().when(base.getAttribute(any())).thenAnswer(inv -> attributes.get(inv.getArgument(0)));
        return new BufferedRequestWrapper(base, body);
    }
}
