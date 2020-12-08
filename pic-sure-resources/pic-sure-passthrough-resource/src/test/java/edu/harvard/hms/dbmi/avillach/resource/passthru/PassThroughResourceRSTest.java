package edu.harvard.hms.dbmi.avillach.resource.passthru;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.ws.rs.NotAuthorizedException;

import org.apache.commons.io.IOUtils;
import org.apache.http.Header;
import org.apache.http.HeaderElement;
import org.apache.http.HttpResponse;
import org.apache.http.ParseException;
import org.apache.http.StatusLine;
import org.apache.http.entity.StringEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;

import edu.harvard.dbmi.avillach.domain.QueryFormat;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.dbmi.avillach.domain.QueryStatus;
import edu.harvard.dbmi.avillach.domain.ResourceInfo;
import edu.harvard.dbmi.avillach.domain.SearchResults;
import edu.harvard.dbmi.avillach.util.PicSureStatus;
import edu.harvard.dbmi.avillach.util.exception.ProtocolException;
import edu.harvard.dbmi.avillach.util.exception.ResourceInterfaceException;

@ExtendWith(MockitoExtension.class)
class PassThroughResourceRSTest {

	HttpClient httpClient;
	PassThroughResourceRS resource;
	ObjectMapper objectMapper = new ObjectMapper();

	@BeforeEach
	void init() {
		ApplicationProperties appProperties = mock(ApplicationProperties.class);
		lenient().when(appProperties.getTargetPicsureToken()).thenReturn("/tmp/unit_test");
		lenient().when(appProperties.getTargetPicsureUrl()).thenReturn("http://test");
		lenient().when(appProperties.getTargetResourceId()).thenReturn(UUID.randomUUID().toString());

		httpClient = mock(HttpClient.class);
		// not mocking these methods...
		lenient().doCallRealMethod().when(httpClient).composeURL(anyString(), anyString());
		lenient().doCallRealMethod().when(httpClient).readObjectFromResponse(any(HttpResponse.class), any());
		lenient().doCallRealMethod().when(httpClient).throwResponseError(any(HttpResponse.class), anyString());

		resource = new PassThroughResourceRS(appProperties, httpClient);
	}

	@Test
	void testInfo() throws Exception {
		// setup http response mocks
		HttpResponse httpResponse = mock(HttpResponse.class);
		StringEntity httpResponseEntity = new StringEntity("");
		StatusLine statusLine = mock(StatusLine.class);
		when(httpResponse.getStatusLine()).thenReturn(statusLine);
		when(httpResponse.getEntity()).thenReturn(httpResponseEntity);
		when(httpClient.retrievePostResponse(anyString(), any(Header[].class), anyString())).thenReturn(httpResponse);

		when(statusLine.getStatusCode()).thenReturn(500);
		assertThrows(ResourceInterfaceException.class, () -> {
			resource.info(new QueryRequest());
		}, "Downstream Resource returned 500 and should cause 'ResourceInterfaceException'");

		when(statusLine.getStatusCode()).thenReturn(401);
		assertThrows(NotAuthorizedException.class, () -> {
			resource.info(new QueryRequest());
		}, "Downstream Resource returned 401 and should cause 'NotAuthorizedException'");

		when(statusLine.getStatusCode()).thenReturn(200);
		ResourceInfo resourceInfo = newResourceInfo();
		httpResponseEntity = new StringEntity(new String(objectMapper.writeValueAsBytes(resourceInfo)));
		when(httpResponse.getEntity()).thenReturn(httpResponseEntity);
		ResourceInfo returnVal = resource.info(new QueryRequest());
		assertEquals(resourceInfo.getId(), returnVal.getId(), "Downstream ResourceInfo Id should match output");
		assertEquals(resourceInfo.getName(), returnVal.getName(), "Downstream ResourceInfo Name should match output");
		assertEquals(resourceInfo.getQueryFormats().size(), returnVal.getQueryFormats().size(),
				"Downstream ResourceInfo QueryFormats should match output");
	}

	@Test
	void testQuery() throws Exception {
		// setup http response mocks
		HttpResponse httpResponse = mock(HttpResponse.class);
		StringEntity httpResponseEntity = new StringEntity("");
		StatusLine statusLine = mock(StatusLine.class);
		when(statusLine.getStatusCode()).thenReturn(200);
		when(httpResponse.getStatusLine()).thenReturn(statusLine);
		when(httpResponse.getEntity()).thenReturn(httpResponseEntity);
		when(httpClient.retrievePostResponse(anyString(), any(Header[].class), anyString())).thenReturn(httpResponse);

		assertThrows(ProtocolException.class, () -> {
			resource.query(null);
		}, "QueryRequest is required");

		assertThrows(ProtocolException.class, () -> {
			resource.query(new QueryRequest());
		}, "Query is required");

		when(statusLine.getStatusCode()).thenReturn(500);
		assertThrows(ResourceInterfaceException.class, () -> {
			resource.query(newQueryRequest("test"));
		}, "Downstream Resource returned 500 and should cause 'ResourceInterfaceException'");

		when(statusLine.getStatusCode()).thenReturn(401);
		assertThrows(NotAuthorizedException.class, () -> {
			resource.query(newQueryRequest("test"));
		}, "Downstream Resource returned 401 and should cause 'NotAuthorizedException'");

		when(statusLine.getStatusCode()).thenReturn(200);
		QueryStatus queryStatus = newQueryStatus(null);
		httpResponseEntity = new StringEntity(new String(objectMapper.writeValueAsBytes(queryStatus)));
		when(httpResponse.getEntity()).thenReturn(httpResponseEntity);
		QueryRequest queryRequest = newQueryRequest("test");
		QueryStatus returnVal = resource.query(queryRequest);
		assertEquals(queryRequest.getResourceUUID(), returnVal.getResourceID());
	}

	@Test
	void testQueryResult() throws Exception {
		// setup http response mocks
		HttpResponse httpResponse = mock(HttpResponse.class);
		StringEntity httpResponseEntity = new StringEntity("");
		StatusLine statusLine = mock(StatusLine.class);
		when(statusLine.getStatusCode()).thenReturn(200);
		when(httpResponse.getStatusLine()).thenReturn(statusLine);
		when(httpResponse.getEntity()).thenReturn(httpResponseEntity);
		when(httpClient.retrievePostResponse(anyString(), any(Header[].class), anyString())).thenReturn(httpResponse);

		assertThrows(ProtocolException.class, () -> {
			resource.queryResult(null, null);
		}, "QueryID is required");

		assertThrows(ProtocolException.class, () -> {
			resource.queryResult(UUID.randomUUID().toString(), null);
		}, "QueryRequest is required");

		when(statusLine.getStatusCode()).thenReturn(500);
		assertThrows(ResourceInterfaceException.class, () -> {
			resource.queryResult(UUID.randomUUID().toString(), newQueryRequest(null));
		}, "Downstream Resource returned 500 and should cause 'ResourceInterfaceException'");

		when(statusLine.getStatusCode()).thenReturn(401);
		assertThrows(NotAuthorizedException.class, () -> {
			resource.queryResult(UUID.randomUUID().toString(), newQueryRequest(null));
		}, "Downstream Resource returned 401 and should cause 'NotAuthorizedException'");

		when(statusLine.getStatusCode()).thenReturn(200);
		String resultId = UUID.randomUUID().toString();
		UUID queryId = UUID.randomUUID();
		lenient().when(httpResponse.getFirstHeader("resultId")).thenReturn(newHeader("resultId", resultId));
		httpResponseEntity = new StringEntity("4");
		when(httpResponse.getEntity()).thenReturn(httpResponseEntity);
		QueryRequest queryRequest = newQueryRequest(null);
		javax.ws.rs.core.Response returnVal = resource.queryResult(queryId.toString(), queryRequest);
		assertEquals("4", IOUtils.toString((InputStream) returnVal.getEntity(), StandardCharsets.UTF_8));
		//assertEquals(resultId, returnVal.getHeaderString("resultId"));
	}

	@Test
	void testQueryStatus() throws Exception {
		// setup http response mocks
		HttpResponse httpResponse = mock(HttpResponse.class);
		StringEntity httpResponseEntity = new StringEntity("");
		StatusLine statusLine = mock(StatusLine.class);
		when(statusLine.getStatusCode()).thenReturn(200);
		when(httpResponse.getStatusLine()).thenReturn(statusLine);
		when(httpResponse.getEntity()).thenReturn(httpResponseEntity);
		when(httpClient.retrievePostResponse(anyString(), any(Header[].class), anyString())).thenReturn(httpResponse);

		assertThrows(ProtocolException.class, () -> {
			resource.queryStatus(null, null);
		}, "QueryID is required");

		assertThrows(ProtocolException.class, () -> {
			resource.queryStatus(UUID.randomUUID().toString(), null);
		}, "QueryRequest is required");

		when(statusLine.getStatusCode()).thenReturn(500);
		assertThrows(ResourceInterfaceException.class, () -> {
			resource.queryStatus(UUID.randomUUID().toString(), newQueryRequest(null));
		}, "Downstream Resource returned 500 and should cause 'ResourceInterfaceException'");

		when(statusLine.getStatusCode()).thenReturn(401);
		assertThrows(NotAuthorizedException.class, () -> {
			resource.queryStatus(UUID.randomUUID().toString(), newQueryRequest(null));
		}, "Downstream Resource returned 401 and should cause 'NotAuthorizedException'");

		when(statusLine.getStatusCode()).thenReturn(200);
		UUID queryId = UUID.randomUUID();
		QueryStatus queryStatus = newQueryStatus(queryId);
		httpResponseEntity = new StringEntity(new String(objectMapper.writeValueAsBytes(queryStatus)));
		when(httpResponse.getEntity()).thenReturn(httpResponseEntity);
		QueryRequest queryRequest = newQueryRequest(null);
		QueryStatus returnVal = resource.queryStatus(queryId.toString(), queryRequest);
		assertEquals(queryId, returnVal.getPicsureResultId());
		assertEquals(queryStatus.getStatus(), returnVal.getStatus());
		assertEquals(queryStatus.getResourceStatus(), returnVal.getResourceStatus());
	}

	@Test
	void testQuerySync() throws Exception {
		// setup http response mocks
		HttpResponse httpResponse = mock(HttpResponse.class);
		StringEntity httpResponseEntity = new StringEntity("");
		StatusLine statusLine = mock(StatusLine.class);
		when(statusLine.getStatusCode()).thenReturn(200);
		when(httpResponse.getStatusLine()).thenReturn(statusLine);
		when(httpResponse.getEntity()).thenReturn(httpResponseEntity);
		when(httpClient.retrievePostResponse(anyString(), any(Header[].class), anyString())).thenReturn(httpResponse);

		assertThrows(ProtocolException.class, () -> {
			resource.querySync(null);
		}, "QueryRequest is required");

		assertThrows(ProtocolException.class, () -> {
			resource.querySync(new QueryRequest());
		}, "Query is required");

		when(statusLine.getStatusCode()).thenReturn(500);
		assertThrows(ResourceInterfaceException.class, () -> {
			resource.querySync(newQueryRequest("test"));
		}, "Downstream Resource returned 500 and should cause 'ResourceInterfaceException'");

		when(statusLine.getStatusCode()).thenReturn(401);
		assertThrows(NotAuthorizedException.class, () -> {
			resource.querySync(newQueryRequest("test"));
		}, "Downstream Resource returned 401 and should cause 'NotAuthorizedException'");

		when(statusLine.getStatusCode()).thenReturn(200);
		String resultId = UUID.randomUUID().toString();
		lenient().when(httpResponse.getFirstHeader("resultId")).thenReturn(newHeader("resultId", resultId));
		httpResponseEntity = new StringEntity("4");
		when(httpResponse.getEntity()).thenReturn(httpResponseEntity);
		QueryRequest queryRequest = newQueryRequest(newQuery());
		javax.ws.rs.core.Response returnVal = resource.querySync(queryRequest);
		assertEquals("4", IOUtils.toString((InputStream) returnVal.getEntity(), StandardCharsets.UTF_8));
		//assertEquals(resultId, returnVal.getHeaderString("resultId"));
	}

	@Test
	void testSearch() throws Exception {
		// setup http response mocks
		HttpResponse httpResponse = mock(HttpResponse.class);
		StringEntity httpResponseEntity = new StringEntity("");
		StatusLine statusLine = mock(StatusLine.class);
		when(statusLine.getStatusCode()).thenReturn(200);
		when(httpResponse.getStatusLine()).thenReturn(statusLine);
		when(httpResponse.getEntity()).thenReturn(httpResponseEntity);
		when(httpClient.retrievePostResponse(anyString(), any(Header[].class), anyString())).thenReturn(httpResponse);

		assertThrows(ProtocolException.class, () -> {
			resource.search(null);
		}, "QueryRequest is required");

		assertThrows(ProtocolException.class, () -> {
			resource.search(new QueryRequest());
		}, "Query is required");

		when(statusLine.getStatusCode()).thenReturn(500);
		assertThrows(ResourceInterfaceException.class, () -> {
			resource.search(newQueryRequest("test"));
		}, "Downstream Resource returned 500 and should cause 'ResourceInterfaceException'");

		when(statusLine.getStatusCode()).thenReturn(401);
		assertThrows(NotAuthorizedException.class, () -> {
			resource.search(newQueryRequest("test"));
		}, "Downstream Resource returned 401 and should cause 'NotAuthorizedException'");

		String queryText = "test";
		when(statusLine.getStatusCode()).thenReturn(200);
		SearchResults searchResults = new SearchResults();
		searchResults.setSearchQuery(queryText);
		httpResponseEntity = new StringEntity(new String(objectMapper.writeValueAsBytes(searchResults)));
		when(httpResponse.getEntity()).thenReturn(httpResponseEntity);
		QueryRequest queryRequest = newQueryRequest(queryText);
		SearchResults returnVal = resource.search(queryRequest);
		assertEquals(queryText, returnVal.getSearchQuery());
	}

	private ResourceInfo newResourceInfo() {
		ResourceInfo info = new ResourceInfo();
		info.setName("PhenoCube v1.0-SNAPSHOT");
		info.setId(UUID.randomUUID());
		info.setQueryFormats(ImmutableList
				.of(new QueryFormat().setDescription("PhenoCube Query Format").setName("PhenoCube Query Format")));
		return info;
	}

	private QueryStatus newQueryStatus(UUID picsureResultId) {
		QueryStatus status = new QueryStatus();
		status.setStatus(PicSureStatus.QUEUED);
		status.setResourceID(UUID.randomUUID());
		status.setResourceStatus("PENDING");
		status.setPicsureResultId(picsureResultId);
		status.setResourceResultId(UUID.randomUUID().toString());
		return status;
	}

	private QueryRequest newQueryRequest(Object query) {
		QueryRequest request = new QueryRequest();
		request.setResourceUUID(UUID.randomUUID());
		request.setQuery(query);
		return request;
	}
	
	// replace with Map 
	private Map<String,String> newQuery() {
		Map<String,String> queryMap = new HashMap<>();
		queryMap.put("requiredFields", "\\TEST");
		queryMap.put("expectedResultType", "COUNT");
		return queryMap;
	}

	private Header newHeader(final String name, final String value) {
		return new Header() {
			@Override
			public String getValue() {
				return value;
			}

			@Override
			public String getName() {
				return name;
			}

			@Override
			public HeaderElement[] getElements() throws ParseException {
				return null;
			}
		};
	}
}