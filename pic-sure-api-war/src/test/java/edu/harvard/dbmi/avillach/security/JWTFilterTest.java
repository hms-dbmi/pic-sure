package edu.harvard.dbmi.avillach.security;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.UUID;

import javax.ws.rs.HttpMethod;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

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
import edu.harvard.dbmi.avillach.service.ResourceWebClient;
import edu.harvard.dbmi.avillach.util.response.PICSUREResponseError;

public class JWTFilterTest {

	private static final UUID QUERY_UUID = UUID.fromString("e830138f-2943-4661-90ae-da053bd94a18");

	private static final UUID RESOURCE_UUID = UUID.fromString("30ef4941-9656-4b47-af80-528f2b98cf17");

	@Rule
	public WireMockRule wireMockRule = new WireMockRule(
			wireMockConfig().dynamicPort().dynamicHttpsPort());

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
		query.setQuery("{\"resourceUUID\":\""+RESOURCE_UUID+"\"}");
		query.setResource(resource);
		when(filter.queryRepo.getById(
				QUERY_UUID))
		.thenReturn(query);
		return query;
	}

	private Resource basicResource() {
		Resource resource = mock(Resource.class);
		when(resource.getResourceRSPath()).thenReturn("http://localhost:" + wireMockRule.port() + "/resource");
		when(resource.getToken()).thenReturn("RESOURCE_TOKEN");
		when(resource.getUuid()).thenReturn(RESOURCE_UUID);

		when(filter.resourceRepo.getById(
				RESOURCE_UUID))
		.thenReturn(resource);
		return resource;
	}

	private void tokenIntrospectionStub(String tokenIntrospectionResult) {
		stubFor(post(urlEqualTo("/introspection_endpoint"))
				.willReturn(aResponse()
						.withStatus(200)
						.withHeader("Content-Type", "application/json")
						.withBody("{\"active\":" + tokenIntrospectionResult + ",\"sub\":\"TEST_USER\"}")));
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
	public void testFilterCallsTokenIntrospectionAppropriatelyForQuerySync() throws IOException {

		tokenIntrospectionStub("true");

		ContainerRequestContext ctx = createRequestContext();
		when(ctx.getUriInfo().getPath()).thenReturn("/query/sync");
		when(ctx.getRequest().getMethod()).thenReturn(HttpMethod.POST);
		InputStream entityStream = new ByteArrayInputStream("{\"query\":\"test\"}".getBytes());
		when(ctx.getEntityStream()).thenReturn(entityStream);
		when(ctx.getHeaderString(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer USER_TOKEN");
		filter.filter(ctx);

		verify(postRequestedFor(
				urlEqualTo("/introspection_endpoint")).
				withRequestBody(matchingJsonPath(
						"$.request.['Target Service']", matching("/query/sync")))
				.withRequestBody(matchingJsonPath(
						"$.request.['query']", matchingJsonPath("query",matching("test"))))
				.withRequestBody(matchingJsonPath(
						"$.token", matching("USER_TOKEN"))));
	}

	@Test
	public void testFilterCallsTokenIntrospectionAppropriatelyForQuery() throws IOException {

		tokenIntrospectionStub("true");

		ContainerRequestContext ctx = createRequestContext();
		when(ctx.getUriInfo().getPath()).thenReturn("/query");
		when(ctx.getRequest().getMethod()).thenReturn(HttpMethod.POST);
		InputStream entityStream = new ByteArrayInputStream("{\"query\":\"test\"}".getBytes());
		when(ctx.getEntityStream()).thenReturn(entityStream);
		when(ctx.getHeaderString(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer USER_TOKEN");
		filter.filter(ctx);

		verify(postRequestedFor(
				urlEqualTo("/introspection_endpoint")).
				withRequestBody(matchingJsonPath(
						"$.request.['Target Service']", matching("/query")))
				.withRequestBody(matchingJsonPath(
						"$.request.['query']", matchingJsonPath("query",matching("test"))))
				.withRequestBody(matchingJsonPath(
						"$.token", matching("USER_TOKEN"))));
	}

	@Test
	public void testFilterCallsTokenIntrospectionAppropriatelyForResultWithoutTrailingSlash() throws IOException {

		tokenIntrospectionStub("true");

		queryFormatStub();

		Query query = persistedQuery();

		ContainerRequestContext ctx = createRequestContext();
		when(ctx.getUriInfo().getPath()).thenReturn("/query/e830138f-2943-4661-90ae-da053bd94a18/result");
		when(ctx.getRequest().getMethod()).thenReturn(HttpMethod.POST);
		when(ctx.getHeaderString(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer USER_TOKEN");
		filter.filter(ctx);

		verify(postRequestedFor(
				urlEqualTo("/resource/query/format")));
		verify(postRequestedFor(
				urlEqualTo("/introspection_endpoint")).
				withRequestBody(matchingJsonPath(
						"$.request.['Target Service']", matching("/query/e830138f-2943-4661-90ae-da053bd94a18/result")))
				.withRequestBody(matchingJsonPath(
						"$.request.query", equalToJson(query.getQuery())))
				.withRequestBody(matchingJsonPath(
						"$.request.formattedQuery", equalToJson("{\"formatted\":\"query\"}")))
				.withRequestBody(matchingJsonPath(
						"$.token", matching("USER_TOKEN"))));
	}

	@Test
	public void testFilterCallsTokenIntrospectionAppropriatelyForResultWithTrailingSlash() throws IOException {

		tokenIntrospectionStub("true");

		queryFormatStub();

		filter.queryRepo = mock(QueryRepository.class);

		Query query = persistedQuery();

		ContainerRequestContext ctx = createRequestContext();
		when(ctx.getUriInfo().getPath()).thenReturn("/query/e830138f-2943-4661-90ae-da053bd94a18/result/");
		when(ctx.getRequest().getMethod()).thenReturn(HttpMethod.POST);
		when(ctx.getHeaderString(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer USER_TOKEN");
		filter.filter(ctx);
		ArgumentCaptor<Map> requestBody = ArgumentCaptor.forClass(Map.class);
		verify(postRequestedFor(
				urlEqualTo("/introspection_endpoint")).
				withRequestBody(matchingJsonPath(
						"$.request.['Target Service']", matching("/query/e830138f-2943-4661-90ae-da053bd94a18/result/")))
				.withRequestBody(matchingJsonPath(
						"$.request.query", equalToJson(query.getQuery())))
				.withRequestBody(matchingJsonPath(
						"$.request.formattedQuery", equalToJson("{\"formatted\":\"query\"}")))
				.withRequestBody(matchingJsonPath(
						"$.token", matching("USER_TOKEN"))));
	}

	private void queryFormatStub() {
		stubFor(post(urlEqualTo("/resource/query/format"))
				.willReturn(aResponse()
						.withStatus(200)
						.withHeader("Content-Type", "application/json")
						.withBody("{\"formatted\":\"query\"}")));
	}

	@Test
	public void testFilterAbortsRequestIfTokenIntrospectionReturnsFalse() throws IOException {
		String tokenIntrospectionResult = "false";
		tokenIntrospectionStub(tokenIntrospectionResult);

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
		assertEquals(((PICSUREResponseError)abortedRequestContext.getValue().getEntity()).getMessage(), "User is not authorized. [Token invalid or expired]");
	}

	@Test
	public void testFilterSetsUsernameIfTokenIntrospectionReturnsTrue() throws IOException {
		tokenIntrospectionStub("true");

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

		tokenIntrospectionStub("true");

		ContainerRequestContext ctx = createRequestContext();
		when(ctx.getUriInfo().getPath()).thenReturn("/query");
		when(ctx.getRequest().getMethod()).thenReturn(HttpMethod.POST);
		InputStream entityStream = new ByteArrayInputStream(("{\"query\":\"test\", \"resourceUUID\":\""+RESOURCE_UUID+"\", \"resourceCredentials\":\"foobar\"}").getBytes());
		when(ctx.getEntityStream()).thenReturn(entityStream);
		when(ctx.getHeaderString(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer USER_TOKEN");
		filter.filter(ctx);

		verify(postRequestedFor(
				urlEqualTo("/introspection_endpoint")).
				withRequestBody(matchingJsonPath(
						"$.request.['Target Service']", matching("/query")))
				.withRequestBody(matchingJsonPath(
						"$.request.['query']", matchingJsonPath("query",matching("test"))))
				.withRequestBody(matchingJsonPath(
						"$.request.['query']", notMatching("resourceCredentials")))
				.withRequestBody(matchingJsonPath(
						"$.token", matching("USER_TOKEN"))));
	}

}
