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
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.client.RestClientException;

import edu.harvard.hms.dbmi.avillach.commons.audit.AuditContext;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUserResolver;
import edu.harvard.hms.dbmi.avillach.gateway.auth.BufferedRequestWrapper;
import edu.harvard.hms.dbmi.avillach.gateway.auth.OpenAccessValidation;
import edu.harvard.hms.dbmi.avillach.gateway.auth.PsamaClient;
import edu.harvard.hms.dbmi.avillach.gateway.auth.PublicEndpointPolicy;
import edu.harvard.hms.dbmi.avillach.gateway.auth.ShippedPublicRoutes;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

class OpenAccessFilterTest {

    private OpenAccessFilter filter(PsamaClient client, AuditContext ctx, boolean enabled) {
        return new OpenAccessFilter(client, ctx, enabled, new PublicEndpointPolicy(ShippedPublicRoutes.routes()));
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
    void enabledOpenAccessSkipsValidationForPublicSystemStatus() throws Exception {
        PsamaClient client = mock(PsamaClient.class);
        when(client.validateOpenAccess(any())).thenReturn(OpenAccessValidation.fromBoolean(false));
        OpenAccessFilter f = filter(client, new AuditContext(), true);
        BufferedRequestWrapper req = wrap(null, "/system/status", "GET");
        FilterChain chain = mock(FilterChain.class);

        f.doFilter(req, new MockHttpServletResponse(), chain);

        verify(chain).doFilter(eq(req), any());
        verifyNoInteractions(client);
    }

    @Test
    void enabledOpenAccessNoBearerSendsRealPathShapeAndGrants() throws Exception {
        PsamaClient client = mock(PsamaClient.class);
        when(client.validateOpenAccess(any())).thenReturn(OpenAccessValidation.fromBoolean(true));
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
    void enabledOpenAccessForwardsNonBlankApiKeyAtTopLevelValidationPayload() throws Exception {
        PsamaClient client = mock(PsamaClient.class);
        when(client.validateOpenAccess(any())).thenReturn(OpenAccessValidation.fromBoolean(true));
        OpenAccessFilter f = filter(client, new AuditContext(), true);

        BufferedRequestWrapper req = wrap(null, "picsure_testKeyValue123");
        f.doFilter(req, mock(HttpServletResponse.class), mock(FilterChain.class));

        ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(client).validateOpenAccess(cap.capture());
        assertThat(cap.getValue()).containsEntry("apiKey", "picsure_testKeyValue123");
    }

    @Test
    void enabledOpenAccessOmitsApiKeyWhenHeaderAbsent() throws Exception {
        PsamaClient client = mock(PsamaClient.class);
        when(client.validateOpenAccess(any())).thenReturn(OpenAccessValidation.fromBoolean(true));
        OpenAccessFilter f = filter(client, new AuditContext(), true);

        f.doFilter(wrap(null), mock(HttpServletResponse.class), mock(FilterChain.class));

        ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(client).validateOpenAccess(cap.capture());
        assertThat(cap.getValue()).doesNotContainKey("apiKey");
    }

    @Test
    void enabledOpenAccessOmitsApiKeyWhenHeaderBlank() throws Exception {
        PsamaClient client = mock(PsamaClient.class);
        when(client.validateOpenAccess(any())).thenReturn(OpenAccessValidation.fromBoolean(true));
        OpenAccessFilter f = filter(client, new AuditContext(), true);

        f.doFilter(wrap(null, "   "), mock(HttpServletResponse.class), mock(FilterChain.class));

        ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(client).validateOpenAccess(cap.capture());
        assertThat(cap.getValue()).doesNotContainKey("apiKey");
    }

    @Test
    void grantSetsOpenAccessTypeAttribute() throws Exception {
        // IdentityPropagationFilter turns this attribute into X-Picsure-Access-Type. Without it, downstream services
        // fall back to inspecting X-User-Id, whose open-access value (OPEN_ACCESS:<host>) is non-blank and so reads as
        // authorized -- which is exactly how open requests ended up on the authorized HPDS backend.
        PsamaClient client = mock(PsamaClient.class);
        when(client.validateOpenAccess(any())).thenReturn(OpenAccessValidation.fromBoolean(true));
        OpenAccessFilter f = filter(client, new AuditContext(), true);

        BufferedRequestWrapper req = wrap(null);
        f.doFilter(req, mock(HttpServletResponse.class), mock(FilterChain.class));

        assertThat(req.getAttribute(GatewayUserResolver.HEADER_ACCESS_TYPE)).isEqualTo(GatewayUserResolver.ACCESS_TYPE_OPEN);
    }

    @Test
    void denialSetsNoAccessTypeAttribute() throws Exception {
        PsamaClient client = mock(PsamaClient.class);
        when(client.validateOpenAccess(any())).thenReturn(OpenAccessValidation.fromBoolean(false));
        OpenAccessFilter f = filter(client, new AuditContext(), true);

        BufferedRequestWrapper req = wrap(null);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        lenient().when(resp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        f.doFilter(req, resp, mock(FilterChain.class));

        assertThat(req.getAttribute(GatewayUserResolver.HEADER_ACCESS_TYPE)).isNull();
    }

    @Test
    void realBearerSetsNoAccessTypeAttributeHere() throws Exception {
        // A real token passes straight through; stamping `authorized` is PsamaIntrospectionFilter's job, and only
        // after the token actually validates.
        PsamaClient client = mock(PsamaClient.class);
        OpenAccessFilter f = filter(client, new AuditContext(), true);

        BufferedRequestWrapper req = wrap("Bearer real-token");
        f.doFilter(req, mock(HttpServletResponse.class), mock(FilterChain.class));

        assertThat(req.getAttribute(GatewayUserResolver.HEADER_ACCESS_TYPE)).isNull();
    }

    @Test
    void grantSetsDedicatedOpenAccessGrantAttribute() throws Exception {
        PsamaClient client = mock(PsamaClient.class);
        when(client.validateOpenAccess(any())).thenReturn(OpenAccessValidation.fromBoolean(true));
        OpenAccessFilter f = filter(client, new AuditContext(), true);

        BufferedRequestWrapper req = wrap(null);
        f.doFilter(req, mock(HttpServletResponse.class), mock(FilterChain.class));

        assertThat(req.getAttribute(OpenAccessFilter.ATTR_OPEN_ACCESS_GRANTED)).isEqualTo(Boolean.TRUE);
    }

    @Test
    void deniedRequestDoesNotSetOpenAccessGrantAttribute() throws Exception {
        PsamaClient client = mock(PsamaClient.class);
        when(client.validateOpenAccess(any())).thenReturn(OpenAccessValidation.fromBoolean(false));
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
        when(client.validateOpenAccess(any())).thenReturn(OpenAccessValidation.fromBoolean(false));
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

    @Test
    void grantStoresValidationResultForLaterFilters() throws Exception {
        OpenAccessValidation validation =
            new OpenAccessValidation(true, "USER", "7c5e0618-0000-0000-0000-000000000000", "AbCd1234", null, null);
        PsamaClient client = mock(PsamaClient.class);
        when(client.validateOpenAccess(any())).thenReturn(validation);
        AuditContext ctx = new AuditContext();
        OpenAccessFilter f = filter(client, ctx, true);

        BufferedRequestWrapper req = wrap(null, "picsure_testKeyValue123");
        FilterChain chain = mock(FilterChain.class);
        f.doFilter(req, new MockHttpServletResponse(), chain);

        assertThat(req.getAttribute(OpenAccessFilter.ATTR_OPEN_ACCESS_VALIDATION)).isSameAs(validation);
        verify(chain).doFilter(eq(req), any());
        assertThat(ctx.getMetadata()).containsEntry("auth_result", "success").containsEntry("auth_action", "open_access.granted")
            .doesNotContainKey("auth_failure_reason");
    }

    @Test
    void sessionRefreshIsSetBeforeTheChainRuns() throws Exception {
        PsamaClient client = mock(PsamaClient.class);
        when(client.validateOpenAccess(any())).thenReturn(
            new OpenAccessValidation(true, "SESSION", "7c5e0618-0000-0000-0000-000000000000", null, null, "picsure_s_refreshed")
        );
        OpenAccessFilter f = filter(client, new AuditContext(), true);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        AtomicReference<String> seenByChain = new AtomicReference<>();

        f.doFilter(
            wrap(null, "picsure_s_current"), resp,
            (request, response) -> seenByChain.set(((HttpServletResponse) response).getHeader(OpenAccessFilter.SESSION_REFRESH_HEADER))
        );

        // a proxied response is committed by the time the chain returns, so the header must already be there when it runs
        assertThat(seenByChain.get()).isEqualTo("picsure_s_refreshed");
        assertThat(resp.getHeader(OpenAccessFilter.SESSION_REFRESH_HEADER)).isEqualTo("picsure_s_refreshed");
    }

    @Test
    void noSessionRefreshHeaderWithoutARefreshedToken() throws Exception {
        PsamaClient client = mock(PsamaClient.class);
        when(client.validateOpenAccess(any()))
            .thenReturn(new OpenAccessValidation(true, "SESSION", "7c5e0618-0000-0000-0000-000000000000", null, null, null));
        OpenAccessFilter f = filter(client, new AuditContext(), true);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        f.doFilter(wrap(null, "picsure_s_current"), resp, mock(FilterChain.class));

        assertThat(resp.containsHeader(OpenAccessFilter.SESSION_REFRESH_HEADER)).isFalse();
    }

    // PSAMA never refreshes on a denial; if one ever did, a denied request must still not hand out a credential
    @Test
    void deniedRequestNeverCarriesASessionRefresh() throws Exception {
        PsamaClient client = mock(PsamaClient.class);
        when(client.validateOpenAccess(any()))
            .thenReturn(new OpenAccessValidation(false, null, null, null, OpenAccessValidation.DENIAL_RULES, "picsure_s_refreshed"));
        OpenAccessFilter f = filter(client, new AuditContext(), true);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        f.doFilter(wrap(null, "picsure_s_current"), resp, mock(FilterChain.class));

        assertThat(resp.getStatus()).isEqualTo(401);
        assertThat(resp.containsHeader(OpenAccessFilter.SESSION_REFRESH_HEADER)).isFalse();
    }

    @Test
    void bareBooleanGrantStoresAnonymousValidationResult() throws Exception {
        PsamaClient client = mock(PsamaClient.class);
        when(client.validateOpenAccess(any())).thenReturn(OpenAccessValidation.fromBoolean(true));
        OpenAccessFilter f = filter(client, new AuditContext(), true);

        BufferedRequestWrapper req = wrap(null);
        f.doFilter(req, new MockHttpServletResponse(), mock(FilterChain.class));

        OpenAccessValidation stored = (OpenAccessValidation) req.getAttribute(OpenAccessFilter.ATTR_OPEN_ACCESS_VALIDATION);
        assertThat(stored.valid()).isTrue();
        assertThat(stored.keyType()).isNull();
        assertThat(stored.keyId()).isNull();
    }

    static Stream<Arguments> denialsAndErrorTypes() {
        return Stream.of(
            Arguments.of(OpenAccessValidation.DENIAL_KEY_MISSING, OpenAccessFilter.ERROR_API_KEY_MISSING),
            Arguments.of(OpenAccessValidation.DENIAL_KEY_INVALID, OpenAccessFilter.ERROR_API_KEY_INVALID),
            Arguments.of(OpenAccessValidation.DENIAL_RULES, "unauthorized"),
            // no reason at all: what a bare-boolean false from an older PSAMA becomes
            Arguments.of(null, "unauthorized"), Arguments.of("some_future_reason", "unauthorized")
        );
    }

    @ParameterizedTest
    @MethodSource("denialsAndErrorTypes")
    void eachDenialMapsToItsErrorType(String denial, String expectedErrorType) throws Exception {
        PsamaClient client = mock(PsamaClient.class);
        when(client.validateOpenAccess(any())).thenReturn(new OpenAccessValidation(false, null, null, null, denial, null));
        AuditContext ctx = new AuditContext();
        OpenAccessFilter f = filter(client, ctx, true);

        MockHttpServletResponse resp = new MockHttpServletResponse();
        BufferedRequestWrapper req = wrap(null, "picsure_testKeyValue123");
        FilterChain chain = mock(FilterChain.class);
        f.doFilter(req, resp, chain);

        assertThat(resp.getStatus()).isEqualTo(401);
        assertThat(resp.getContentAsString()).contains("\"errorType\":\"" + expectedErrorType + "\"")
            .doesNotContain("picsure_testKeyValue123");
        verify(chain, never()).doFilter(any(), any());
        assertThat(req.getAttribute(OpenAccessFilter.ATTR_OPEN_ACCESS_VALIDATION)).isNull();
        assertThat(ctx.getMetadata()).containsEntry("auth_result", "failure").containsEntry("auth_action", "open_access.denied")
            .containsEntry("auth_failure_reason", expectedErrorType);
    }

    @Test
    void psamaTransportFailureYields502StructuredErrorWithoutPropagating() throws Exception {
        PsamaClient client = mock(PsamaClient.class);
        when(client.validateOpenAccess(any())).thenThrow(new RestClientException("connection refused"));
        AuditContext ctx = new AuditContext();
        OpenAccessFilter f = filter(client, ctx, true);

        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        f.doFilter(wrap(null), resp, chain); // must not propagate the RestClientException

        assertThat(resp.getStatus()).isEqualTo(502);
        assertThat(resp.getContentAsString()).contains("\"errorType\":\"open_access_unreachable\"");
        verify(chain, never()).doFilter(any(), any());
        assertThat(ctx.getMetadata()).containsEntry("auth_result", "failure")
            .containsEntry("auth_failure_reason", "open_access_unreachable");
    }

    private static BufferedRequestWrapper wrap(String authHeader) {
        return wrap(authHeader, null);
    }

    private static BufferedRequestWrapper wrap(String authHeader, String apiKeyHeader) {
        return wrap(authHeader, "/v3/search/abc", "POST", apiKeyHeader);
    }

    private static BufferedRequestWrapper wrap(String authHeader, String uri, String method) {
        return wrap(authHeader, uri, method, null);
    }

    private static BufferedRequestWrapper wrap(String authHeader, String uri, String method, String apiKeyHeader) {
        HttpServletRequest base = mock(HttpServletRequest.class);
        when(base.getRequestURI()).thenReturn(uri);
        when(base.getContextPath()).thenReturn("");
        lenient().when(base.getMethod()).thenReturn(method);
        if (authHeader != null) when(base.getHeader("Authorization")).thenReturn(authHeader);
        if (apiKeyHeader != null) when(base.getHeader(OpenAccessFilter.API_KEY_HEADER)).thenReturn(apiKeyHeader);
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
