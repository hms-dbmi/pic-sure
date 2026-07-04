package edu.harvard.dbmi.avillach.security;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import javax.ws.rs.HttpMethod;
import javax.ws.rs.NotAuthorizedException;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import com.github.tomakehurst.wiremock.junit.WireMockRule;

import edu.harvard.dbmi.avillach.PicSureWarInit;
import edu.harvard.dbmi.avillach.data.entity.Query;
import edu.harvard.dbmi.avillach.data.entity.Resource;
import edu.harvard.dbmi.avillach.data.repository.QueryRepository;
import edu.harvard.dbmi.avillach.data.repository.ResourceRepository;
import edu.harvard.dbmi.avillach.service.AuditContext;
import edu.harvard.dbmi.avillach.service.ResourceWebClient;
import edu.harvard.dbmi.avillach.util.response.PICSUREResponseError;

public class JWTFilterTest {
    private static final UUID QUERY_UUID = UUID.fromString("e830138f-2943-4661-90ae-da053bd94a18");

    private static final UUID RESOURCE_UUID = UUID.fromString("30ef4941-9656-4b47-af80-528f2b98cf17");

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(0);

    private int port;

    private PicSureWarInit picSureWarInit;

    private JWTFilter filter;

    private final List<LogRecord> shadowCaptured = new ArrayList<>();
    private java.util.logging.Logger shadowJulLogger;
    private Handler shadowCaptureHandler;

    @Before
    public void setup() {
        port = wireMockRule.port();
        picSureWarInit = mock(PicSureWarInit.class);
        when(picSureWarInit.getToken_introspection_token()).thenReturn("INTROSPECTION_TOKEN");
        when(picSureWarInit.getToken_introspection_url()).thenReturn("http://localhost:" + port + "/introspection_endpoint");
        filter = new JWTFilter();
        filter.setUserIdClaim("sub");
        filter.picSureWarInit = picSureWarInit;
        filter.resourceWebClient = new ResourceWebClient();
        filter.queryRepo = mock(QueryRepository.class);
        filter.resourceRepo = mock(ResourceRepository.class);
        filter.auditContext = new AuditContext();
        filter.uriInfo = mock(UriInfo.class);
        when(filter.uriInfo.getPath()).thenReturn("/test");

        // Task 7/8: SHADOW_WF capture -- this module's SLF4J binding (slf4j-jdk14) routes straight through to
        // java.util.logging, so we attach a JUL Handler to the "picsure.shadow" logger rather than a logback
        // ListAppender.
        shadowJulLogger = java.util.logging.Logger.getLogger("picsure.shadow");
        shadowJulLogger.setLevel(java.util.logging.Level.ALL);
        shadowCaptureHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                shadowCaptured.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        shadowJulLogger.addHandler(shadowCaptureHandler);
    }

    @After
    public void tearDownShadowLogging() {
        shadowJulLogger.removeHandler(shadowCaptureHandler);
        ShadowLog.setEnabledForTest(null);
    }

    private ContainerRequestContext createRequestContext() {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        Request request = mock(Request.class);
        when(ctx.getUriInfo()).thenReturn(uriInfo);
        when(ctx.getRequest()).thenReturn(request);
        return ctx;
    }


    private Query persistedQuery() {
        Resource resource = basicResource();
        Query query = new Query();
        query.setUuid(UUID.randomUUID());
        query.setQuery("{\"resourceUUID\":\"" + RESOURCE_UUID + "\"}");
        query.setResource(resource);
        when(filter.queryRepo.getById(QUERY_UUID)).thenReturn(query);
        return query;
    }

    private Resource basicResource() {
        Resource resource = mock(Resource.class);
        when(resource.getResourceRSPath()).thenReturn("http://localhost:" + wireMockRule.port() + "/resource");
        when(resource.getToken()).thenReturn("RESOURCE_TOKEN");
        when(resource.getUuid()).thenReturn(RESOURCE_UUID);

        when(filter.resourceRepo.getById(RESOURCE_UUID)).thenReturn(resource);
        return resource;
    }

    private void tokenIntrospectionStub() {
        tokenIntrospectionStub(true);
    }

    private void tokenIntrospectionStub(Boolean active) {
        stubFor(
            post(urlEqualTo("/introspection_endpoint")).willReturn(
                aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(
                    "{" + "\"active\":" + Boolean.toString(active) + "," + "\"sub\":\"TEST_USER\"," + "\"email\":\"some@email.com\","
                        + "\"roles\":\"PIC_SURE_ANY_QUERY\"," + "\"tokenRefreshed\":" + Boolean.FALSE + "}"
                )
            )
        );
    }

    // ---- Gateway-owns-auth bypass (Task 17 / Option A) ----

    @Test
    public void testGatewayOwnsAuthBypassesIntrospectionForOrdinaryPath() throws IOException {
        when(picSureWarInit.isGatewayOwnsAuth()).thenReturn(true);
        ContainerRequestContext ctx = createRequestContext();
        when(ctx.getUriInfo().getPath()).thenReturn("/query");
        when(ctx.getRequest().getMethod()).thenReturn(HttpMethod.POST);

        filter.filter(ctx);

        verify(ctx, never()).getHeaderString(HttpHeaders.AUTHORIZATION);
        verify(ctx, never()).setProperty(eq("username"), anyString());
        verify(ctx, never()).setSecurityContext(any());
    }

    @Test
    public void testGatewayOwnsAuthStillRunsFullIntrospectionForInterimResultPath() throws IOException {
        tokenIntrospectionStub();
        queryFormatStub();
        persistedQuery();

        when(picSureWarInit.isGatewayOwnsAuth()).thenReturn(true);
        when(picSureWarInit.isGatewayOwnsQueryReadAuth()).thenReturn(false);

        ContainerRequestContext ctx = createRequestContext();
        when(ctx.getUriInfo().getPath()).thenReturn("/query/e830138f-2943-4661-90ae-da053bd94a18/result");
        when(ctx.getRequest().getMethod()).thenReturn(HttpMethod.POST);
        when(ctx.getHeaderString(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer USER_TOKEN");

        filter.filter(ctx);

        verify(postRequestedFor(urlEqualTo("/introspection_endpoint")));
        verify(ctx).setProperty("username", "TEST_USER");
    }

    @Test
    public void testGatewayOwnsAuthAndQueryReadAuthBypassesResultPathToo() throws IOException {
        when(picSureWarInit.isGatewayOwnsAuth()).thenReturn(true);
        when(picSureWarInit.isGatewayOwnsQueryReadAuth()).thenReturn(true);

        ContainerRequestContext ctx = createRequestContext();
        when(ctx.getUriInfo().getPath()).thenReturn("/query/e830138f-2943-4661-90ae-da053bd94a18/result");
        when(ctx.getRequest().getMethod()).thenReturn(HttpMethod.POST);

        filter.filter(ctx);

        verify(ctx, never()).getHeaderString(HttpHeaders.AUTHORIZATION);
    }

    @Test
    public void testSystemPathDoesNotRequireAuthenticationHeader() throws IOException {
        ContainerRequestContext ctx = createRequestContext();
        when(ctx.getUriInfo().getPath()).thenReturn("/system/status");
        when(ctx.getRequest().getMethod()).thenReturn(HttpMethod.GET);
        filter.filter(ctx);
        verify(ctx).setProperty("username", "SYSTEM_MONITOR");
    }

    @Test
    public void testLoggingProxyPathSkipsAuthentication() throws IOException {
        ContainerRequestContext ctx = createRequestContext();
        when(ctx.getUriInfo().getPath()).thenReturn("/proxy/pic-sure-logging/audit");
        filter.filter(ctx);
        verify(ctx, never()).getHeaderString(HttpHeaders.AUTHORIZATION);
        verify(ctx, never()).abortWith(any(Response.class));
    }

    @Test
    public void testExcludedFilterPaths_openapi() throws IOException {
        ContainerRequestContext ctx = createRequestContext();
        when(ctx.getUriInfo().getPath()).thenReturn("/openapi.json");
        when(ctx.getRequest().getMethod()).thenReturn(HttpMethod.GET);
        filter.filter(ctx);
        verify(ctx, never()).setProperty(eq("username"), anyString());
        verify(ctx, never()).abortWith(any(Response.class));
        verify(ctx, never()).getHeaderString(HttpHeaders.AUTHORIZATION);
    }

    @Test
    public void testExcludedFilterPaths_config_validPathsWithNoAuthRequired() throws RuntimeException {
        List<String> letters = List.of(
            "", "/ui:(feature.flag3)?-wear[e12]", "/address", "/postadmin", "/data", "/meta", "/init", "/names", "/administrators",
            "/00000000-0000-0000-0000-000000000000"
        );

        List<String> exceptions = new ArrayList<>();
        for (String letter : letters) {
            ContainerRequestContext ctx = createRequestContext();
            when(ctx.getUriInfo().getPath()).thenReturn("/configuration" + letter);
            when(ctx.getRequest().getMethod()).thenReturn(HttpMethod.GET);

            try {
                filter.filter(ctx);
                verify(ctx, never()).setProperty(eq("username"), anyString());
                verify(ctx, never()).abortWith(any(Response.class));
            } catch (Exception e) {
                exceptions.add(letter);
            }
        }
        assertEquals(0, exceptions.size());
    }

    @Test(expected = NotAuthorizedException.class)
    public void testExcludedFilterPaths_config_invalidPathsHitNoAuthException() throws IOException {
        ContainerRequestContext ctx = createRequestContext();
        when(ctx.getUriInfo().getPath()).thenReturn("/configuration/SOME_FLA%G");
        when(ctx.getRequest().getMethod()).thenReturn(HttpMethod.GET);

        filter.filter(ctx);
        // Test passes if NotAuthorizedException is thrown
    }

    @Test(expected = NotAuthorizedException.class)
    public void testExcludedFilterPaths_config_blockedAdminPathHitsNoAuthException() throws IOException {
        ContainerRequestContext ctx = createRequestContext();
        when(ctx.getUriInfo().getPath()).thenReturn("/configuration/admin");
        when(ctx.getRequest().getMethod()).thenReturn(HttpMethod.GET);

        filter.filter(ctx);
        // Test passes if NotAuthorizedException is thrown
    }

    @Test
    public void testFilterCallsTokenIntrospectionAppropriatelyForQuerySync() throws IOException {

        tokenIntrospectionStub();

        ContainerRequestContext ctx = createRequestContext();
        when(ctx.getUriInfo().getPath()).thenReturn("/query/sync");
        when(ctx.getRequest().getMethod()).thenReturn(HttpMethod.POST);
        InputStream entityStream = new ByteArrayInputStream("{\"query\":\"test\"}".getBytes());
        when(ctx.getEntityStream()).thenReturn(entityStream);
        when(ctx.getHeaderString(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer USER_TOKEN");
        filter.filter(ctx);

        verify(
            postRequestedFor(urlEqualTo("/introspection_endpoint"))
                .withRequestBody(matchingJsonPath("$.request.['Target Service']", matching("/query/sync")))
                .withRequestBody(matchingJsonPath("$.request.['query']", matchingJsonPath("query", matching("test"))))
                .withRequestBody(matchingJsonPath("$.token", matching("USER_TOKEN")))
        );
    }

    @Test
    public void testFilterCallsTokenIntrospectionAppropriatelyForQuery() throws IOException {

        tokenIntrospectionStub();

        ContainerRequestContext ctx = createRequestContext();
        when(ctx.getUriInfo().getPath()).thenReturn("/query");
        when(ctx.getRequest().getMethod()).thenReturn(HttpMethod.POST);
        InputStream entityStream = new ByteArrayInputStream("{\"query\":\"test\"}".getBytes());
        when(ctx.getEntityStream()).thenReturn(entityStream);
        when(ctx.getHeaderString(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer USER_TOKEN");
        filter.filter(ctx);

        verify(
            postRequestedFor(urlEqualTo("/introspection_endpoint"))
                .withRequestBody(matchingJsonPath("$.request.['Target Service']", matching("/query")))
                .withRequestBody(matchingJsonPath("$.request.['query']", matchingJsonPath("query", matching("test"))))
                .withRequestBody(matchingJsonPath("$.token", matching("USER_TOKEN")))
        );
    }

    @Test
    public void testFilterCallsTokenIntrospectionAppropriatelyForResultWithoutTrailingSlash() throws IOException {

        tokenIntrospectionStub();

        queryFormatStub();

        Query query = persistedQuery();

        ContainerRequestContext ctx = createRequestContext();
        when(ctx.getUriInfo().getPath()).thenReturn("/query/e830138f-2943-4661-90ae-da053bd94a18/result");
        when(ctx.getRequest().getMethod()).thenReturn(HttpMethod.POST);
        when(ctx.getHeaderString(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer USER_TOKEN");
        filter.filter(ctx);

        verify(postRequestedFor(urlEqualTo("/resource/query/format")));
        verify(
            postRequestedFor(urlEqualTo("/introspection_endpoint"))
                .withRequestBody(
                    matchingJsonPath("$.request.['Target Service']", matching("/query/e830138f-2943-4661-90ae-da053bd94a18/result"))
                ).withRequestBody(matchingJsonPath("$.request.query", equalToJson(query.getQuery())))
                .withRequestBody(matchingJsonPath("$.request.formattedQuery", equalToJson("{\"formatted\":\"query\"}")))
                .withRequestBody(matchingJsonPath("$.token", matching("USER_TOKEN")))
        );
    }

    @Test
    public void testFilterCallsTokenIntrospectionAppropriatelyForResultWithTrailingSlash() throws IOException {

        tokenIntrospectionStub();

        queryFormatStub();

        filter.queryRepo = mock(QueryRepository.class);

        Query query = persistedQuery();

        ContainerRequestContext ctx = createRequestContext();
        when(ctx.getUriInfo().getPath()).thenReturn("/query/e830138f-2943-4661-90ae-da053bd94a18/result/");
        when(ctx.getRequest().getMethod()).thenReturn(HttpMethod.POST);
        when(ctx.getHeaderString(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer USER_TOKEN");
        filter.filter(ctx);
        ArgumentCaptor<Map> requestBody = ArgumentCaptor.forClass(Map.class);
        verify(
            postRequestedFor(urlEqualTo("/introspection_endpoint"))
                .withRequestBody(
                    matchingJsonPath("$.request.['Target Service']", matching("/query/e830138f-2943-4661-90ae-da053bd94a18/result/"))
                ).withRequestBody(matchingJsonPath("$.request.query", equalToJson(query.getQuery())))
                .withRequestBody(matchingJsonPath("$.request.formattedQuery", equalToJson("{\"formatted\":\"query\"}")))
                .withRequestBody(matchingJsonPath("$.token", matching("USER_TOKEN")))
        );
    }

    private void queryFormatStub() {
        stubFor(
            post(urlEqualTo("/resource/query/format")).willReturn(
                aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{\"formatted\":\"query\"}")
            )
        );
    }

    @Test
    public void testFilterAbortsRequestIfTokenIntrospectionReturnsFalse() throws IOException {
        tokenIntrospectionStub(false);

        ContainerRequestContext ctx = createRequestContext();
        when(ctx.getUriInfo().getPath()).thenReturn("/query/sync");
        when(ctx.getRequest().getMethod()).thenReturn(HttpMethod.POST);
        InputStream entityStream = new ByteArrayInputStream("{}".getBytes());
        when(ctx.getEntityStream()).thenReturn(entityStream);
        when(ctx.getHeaderString(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer USER_TOKEN");
        filter.filter(ctx);
        verify(postRequestedFor(urlEqualTo("/introspection_endpoint")));
        ArgumentCaptor<Response> abortedRequestContext = ArgumentCaptor.forClass(Response.class);
        verify(ctx).abortWith(abortedRequestContext.capture());
        assertEquals(abortedRequestContext.getValue().getStatus(), 401);
        assertEquals(
            ((PICSUREResponseError) abortedRequestContext.getValue().getEntity()).getMessage(),
            "User is not authorized. [Token invalid or expired]"
        );
    }

    @Test
    public void testFilterSetsUsernameIfTokenIntrospectionReturnsTrue() throws IOException {
        tokenIntrospectionStub();

        ContainerRequestContext ctx = createRequestContext();
        when(ctx.getUriInfo().getPath()).thenReturn("/query/sync");
        when(ctx.getRequest().getMethod()).thenReturn(HttpMethod.POST);
        InputStream entityStream = new ByteArrayInputStream("{}".getBytes());
        when(ctx.getEntityStream()).thenReturn(entityStream);
        when(ctx.getHeaderString(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer USER_TOKEN");
        filter.filter(ctx);
        verify(postRequestedFor(urlEqualTo("/introspection_endpoint")));
        verify(ctx).setProperty("username", "TEST_USER");
    }

    @Test
    public void testFilterRemovesResourceCredentialsBeforeSendingToTokenIntrospectionOrFormatter() throws IOException {

        tokenIntrospectionStub();

        ContainerRequestContext ctx = createRequestContext();
        when(ctx.getUriInfo().getPath()).thenReturn("/query");
        when(ctx.getRequest().getMethod()).thenReturn(HttpMethod.POST);
        InputStream entityStream = new ByteArrayInputStream(
            ("{\"query\":\"test\", \"resourceUUID\":\"" + RESOURCE_UUID + "\", \"resourceCredentials\":\"foobar\"}").getBytes()
        );
        when(ctx.getEntityStream()).thenReturn(entityStream);
        when(ctx.getHeaderString(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer USER_TOKEN");
        filter.filter(ctx);

        verify(
            postRequestedFor(urlEqualTo("/introspection_endpoint"))
                .withRequestBody(matchingJsonPath("$.request.['Target Service']", matching("/query")))
                .withRequestBody(matchingJsonPath("$.request.['query']", matchingJsonPath("query", matching("test"))))
                .withRequestBody(matchingJsonPath("$.request.['query']", notMatching("resourceCredentials")))
                .withRequestBody(matchingJsonPath("$.token", matching("USER_TOKEN")))
        );
    }

    // ---- Task 7/8: flag-gated SHADOW_WF logging (throwaway parity-verification scaffolding) ----

    @Test
    public void testShadowLoggingEmitsWfIntrospectionLineWithCorrelationIdAndTokenHashWhenFlagEnabled() throws IOException {
        ShadowLog.setEnabledForTest(true);
        tokenIntrospectionStub();

        ContainerRequestContext ctx = createRequestContext();
        when(ctx.getUriInfo().getPath()).thenReturn("/query/sync");
        when(ctx.getRequest().getMethod()).thenReturn(HttpMethod.POST);
        InputStream entityStream = new ByteArrayInputStream("{\"query\":\"test\"}".getBytes());
        when(ctx.getEntityStream()).thenReturn(entityStream);
        when(ctx.getHeaderString(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer USER_TOKEN");
        when(ctx.getHeaderString("X-PICSURE-Shadow-Id")).thenReturn("cid-integration-1");

        filter.filter(ctx);

        assertEquals(1, shadowCaptured.size());
        String line = shadowCaptured.get(0).getMessage();
        assertTrue(line.contains("\"side\":\"WF\""));
        assertTrue(line.contains("\"correlationId\":\"cid-integration-1\""));
        assertTrue(line.contains("\"channel\":\"introspection\""));
        assertTrue(line.contains("\"targetService\":\"/query/sync\""));
        // Same SHA-256 algorithm as the gateway's ShadowSupport.tokenHash -- hashes for the same bearer token
        // must match byte-for-byte so the reconciler can join the two sides.
        assertTrue(line.contains("\"tokenHash\":\"" + ShadowLog.tokenHash("USER_TOKEN") + "\""));
        assertTrue(line.contains("\"decision\":\"active\""));
    }

    @Test
    public void testShadowLoggingEmitsInactiveDecisionEvenWhenIntrospectionRejectsTokenAndFlagEnabled() throws IOException {
        ShadowLog.setEnabledForTest(true);
        tokenIntrospectionStub(false);

        ContainerRequestContext ctx = createRequestContext();
        when(ctx.getUriInfo().getPath()).thenReturn("/query/sync");
        when(ctx.getRequest().getMethod()).thenReturn(HttpMethod.POST);
        InputStream entityStream = new ByteArrayInputStream("{}".getBytes());
        when(ctx.getEntityStream()).thenReturn(entityStream);
        when(ctx.getHeaderString(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer USER_TOKEN");
        when(ctx.getHeaderString("X-PICSURE-Shadow-Id")).thenReturn("cid-integration-2");

        filter.filter(ctx);

        // Existing reject behavior is unchanged -- the shadow log is observational only.
        verify(ctx).abortWith(any(Response.class));

        assertEquals(1, shadowCaptured.size());
        String line = shadowCaptured.get(0).getMessage();
        assertTrue(line.contains("\"correlationId\":\"cid-integration-2\""));
        assertTrue(line.contains("\"decision\":\"inactive\""));
    }

    @Test
    public void testShadowLoggingEmitsNothingWhenFlagDisabled() throws IOException {
        ShadowLog.setEnabledForTest(false);
        tokenIntrospectionStub();

        ContainerRequestContext ctx = createRequestContext();
        when(ctx.getUriInfo().getPath()).thenReturn("/query/sync");
        when(ctx.getRequest().getMethod()).thenReturn(HttpMethod.POST);
        InputStream entityStream = new ByteArrayInputStream("{\"query\":\"test\"}".getBytes());
        when(ctx.getEntityStream()).thenReturn(entityStream);
        when(ctx.getHeaderString(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer USER_TOKEN");
        when(ctx.getHeaderString("X-PICSURE-Shadow-Id")).thenReturn("cid-should-not-appear");

        filter.filter(ctx);

        // Flag off => zero behavior change: no shadow log lines, existing success path unaffected.
        assertTrue(shadowCaptured.isEmpty());
        verify(ctx).setProperty("username", "TEST_USER");
    }

    @Test
    public void testShadowLoggingEmitsWfOpenAccessLineWhenFlagEnabled() throws IOException {
        ShadowLog.setEnabledForTest(true);
        when(picSureWarInit.isOpenAccessEnabled()).thenReturn(true);
        when(picSureWarInit.getOpenAccessValidateUrl()).thenReturn("http://localhost:" + port + "/open_access_endpoint");
        stubFor(
            post(urlEqualTo("/open_access_endpoint"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("true"))
        );

        ContainerRequestContext ctx = createRequestContext();
        when(ctx.getUriInfo().getPath()).thenReturn("/query/sync");
        when(ctx.getUriInfo().getRequestUri()).thenReturn(java.net.URI.create("http://picsure.example.org/query/sync"));
        when(ctx.getRequest().getMethod()).thenReturn(HttpMethod.POST);
        InputStream entityStream = new ByteArrayInputStream("{\"query\":\"test\"}".getBytes());
        when(ctx.getEntityStream()).thenReturn(entityStream);
        when(ctx.getHeaderString("X-PICSURE-Shadow-Id")).thenReturn("cid-oa-1");

        filter.filter(ctx);

        assertEquals(1, shadowCaptured.size());
        String line = shadowCaptured.get(0).getMessage();
        assertTrue(line.contains("\"correlationId\":\"cid-oa-1\""));
        assertTrue(line.contains("\"channel\":\"open-access\""));
        assertTrue(line.contains("\"targetService\":\"/query/sync\""));
        assertTrue(line.contains("\"tokenHash\":null"));
        assertTrue(line.contains("\"ipAddress\":\"OPEN_ACCESS:picsure.example.org\""));
        assertTrue(line.contains("\"decision\":\"allow\""));
    }

}
