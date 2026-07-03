package edu.harvard.dbmi.avillach.security;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.ws.rs.HttpMethod;
import javax.ws.rs.NotAuthorizedException;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;
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
        verify(
            postRequestedFor(urlEqualTo("/introspection_endpoint"))
                .withRequestBody(
                    matchingJsonPath("$.request.['Target Service']", matching("/query/e830138f-2943-4661-90ae-da053bd94a18/result/"))
                ).withRequestBody(matchingJsonPath("$.request.query", equalToJson(query.getQuery())))
                .withRequestBody(matchingJsonPath("$.request.formattedQuery", equalToJson("{\"formatted\":\"query\"}")))
                .withRequestBody(matchingJsonPath("$.token", matching("USER_TOKEN")))
        );
    }

    /**
     * Regression test for the double-JSON-encoding bug: when PSAMA's token introspection response carries an updated "query" (it rewrote
     * authorizationFilters for the caller's consents), the filter must parse that value back into an object before re-attaching it to the
     * request, not store PSAMA's raw JSON-string text. Storing it as text causes it to be JSON-escaped an extra time on every subsequent
     * serialization - including when the query is ultimately persisted - corrupting stored queries with doubled backslashes.
     */
    @Test
    public void testFilterParsesPsamaUpdatedQueryIntoObjectInsteadOfRawText() throws IOException {
        ObjectMapper mapper = new ObjectMapper();

        Map<String, Object> updatedQuery = new HashMap<>();
        updatedQuery.put("select", java.util.List.of("\\_consents\\"));
        updatedQuery.put("authorizationFilters", java.util.List.of());
        String updatedQueryAsJsonString = mapper.writeValueAsString(updatedQuery);

        // PSAMA's introspection contract carries the rewritten query as a JSON string value, not a nested object
        Map<String, Object> introspectionResponse = new HashMap<>();
        introspectionResponse.put("active", true);
        introspectionResponse.put("sub", "TEST_USER");
        introspectionResponse.put("email", "some@email.com");
        introspectionResponse.put("roles", "PIC_SURE_ANY_QUERY");
        introspectionResponse.put("tokenRefreshed", false);
        introspectionResponse.put("query", updatedQueryAsJsonString);

        stubFor(
            post(urlEqualTo("/introspection_endpoint")).willReturn(
                aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                    .withBody(mapper.writeValueAsString(introspectionResponse))
            )
        );

        String originalRequestBody = "{\"query\":{\"select\":[\"\\\\_consents\\\\\"]},\"resourceUUID\":\"" + RESOURCE_UUID + "\"}";

        // A mock ContainerRequestContext has no real backing field for its entity stream, so getEntityStream()
        // has to be wired to reflect whatever setEntityStream() last stored - mirroring how the filter reads,
        // buffers, resets, and re-reads the stream within a single filter() call.
        byte[][] currentBody = {originalRequestBody.getBytes()};
        ContainerRequestContext ctx = createRequestContext();
        when(ctx.getUriInfo().getPath()).thenReturn("/query/sync");
        when(ctx.getRequest().getMethod()).thenReturn(HttpMethod.POST);
        when(ctx.getEntityStream()).thenAnswer(inv -> new ByteArrayInputStream(currentBody[0]));
        doAnswer(inv -> {
            currentBody[0] = ((InputStream) inv.getArgument(0)).readAllBytes();
            return null;
        }).when(ctx).setEntityStream(any());
        when(ctx.getHeaderString(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer USER_TOKEN");

        filter.filter(ctx);

        Map<?, ?> finalRequestBody = mapper.readValue(currentBody[0], Map.class);
        Object query = finalRequestBody.get("query");
        assertTrue("PSAMA's updated query must be stored as a nested object, not a re-escaped JSON string", query instanceof Map);
        assertEquals(updatedQuery, query);
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

}
