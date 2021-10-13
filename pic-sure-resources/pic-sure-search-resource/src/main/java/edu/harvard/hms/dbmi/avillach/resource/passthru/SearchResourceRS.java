package edu.harvard.hms.dbmi.avillach.resource.passthru;

import java.io.IOException;
import java.util.UUID;

import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;

import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.message.BasicHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.dbmi.avillach.domain.QueryStatus;
import edu.harvard.dbmi.avillach.domain.ResourceInfo;
import edu.harvard.dbmi.avillach.domain.SearchResults;
import edu.harvard.dbmi.avillach.service.IResourceRS;
import edu.harvard.dbmi.avillach.util.exception.ApplicationException;
import edu.harvard.dbmi.avillach.util.exception.ProtocolException;

@Path("/passthru")
@Produces("application/json")
@Consumes("application/json")
public class SearchResourceRS implements IResourceRS {

	private static final String BEARER_STRING = "Bearer ";

	private static final ObjectMapper objectMapper = new ObjectMapper();
	private static final Logger logger = LoggerFactory.getLogger(SearchResourceRS.class);

	@Inject
	private ApplicationProperties properties;
	@Inject
	private HttpClient httpClient;

	public SearchResourceRS() {
	}

	@Inject
	public SearchResourceRS(ApplicationProperties applicationProperties, HttpClient httpClient) {
		this.properties = applicationProperties;
		this.httpClient = httpClient;
	}

	@POST
	@Path("/info")
	public ResourceInfo info(QueryRequest infoRequest) {
		String pathName = "/info";

		try {
			QueryRequest chainRequest = new QueryRequest();
			if (infoRequest != null) {
				chainRequest.setQuery(infoRequest.getQuery());
				chainRequest.setResourceCredentials(infoRequest.getResourceCredentials());
			}
			chainRequest.setResourceUUID(UUID.fromString(properties.getTargetResourceId()));

			String payload = objectMapper.writeValueAsString(chainRequest);

			HttpResponse response = httpClient.retrievePostResponse(
					httpClient.composeURL(properties.getTargetPicsureUrl(), pathName), createAuthHeader(), payload);
			if (response.getStatusLine().getStatusCode() != 200) {
				logger.error("{}{} did not return a 200: {} {} ", properties.getTargetPicsureUrl(), pathName,
						response.getStatusLine().getStatusCode(), response.getStatusLine().getReasonPhrase());
				httpClient.throwResponseError(response, properties.getTargetPicsureUrl());
			}

			ResourceInfo resourceInfo = httpClient.readObjectFromResponse(response, ResourceInfo.class);
			if (infoRequest != null && infoRequest.getResourceUUID() != null) {
				resourceInfo.setId(infoRequest.getResourceUUID());
			}
			return resourceInfo;
		} catch (IOException e) {
			throw new ApplicationException(
					"Error encoding query for resource with id " + infoRequest.getResourceUUID());
		} catch (ClassCastException | IllegalArgumentException e) {
			logger.error(e.getMessage());
			throw new ProtocolException(ProtocolException.INCORRECTLY_FORMATTED_REQUEST);
		}
	}

	@POST
	@Path("/query")
	public QueryStatus query(QueryRequest queryRequest) {
		if (queryRequest == null) {
			throw new ProtocolException(ProtocolException.MISSING_DATA);
		}
		Object search = queryRequest.getQuery();
		if (search == null) {
			throw new ProtocolException((ProtocolException.MISSING_DATA));
		}

		String pathName = "/query";

		try {
			QueryRequest chainRequest = new QueryRequest();
			chainRequest.setQuery(queryRequest.getQuery());
			chainRequest.setResourceCredentials(queryRequest.getResourceCredentials());
			chainRequest.setResourceUUID(UUID.fromString(properties.getTargetResourceId()));

			String payload = objectMapper.writeValueAsString(chainRequest);
			HttpResponse response = httpClient.retrievePostResponse(
					httpClient.composeURL(properties.getTargetPicsureUrl(), pathName), createAuthHeader(), payload);
			if (response.getStatusLine().getStatusCode() != 200) {
				logger.error("{}{} calling resource with id {} did not return a 200: {} {} ",
						properties.getTargetPicsureUrl(), pathName, chainRequest.getResourceUUID(),
						response.getStatusLine().getStatusCode(), response.getStatusLine().getReasonPhrase());
				httpClient.throwResponseError(response, properties.getTargetPicsureUrl());
			}
			QueryStatus queryStatus = httpClient.readObjectFromResponse(response, QueryStatus.class);
			queryStatus.setResourceID(queryRequest.getResourceUUID());
			return queryStatus;
		} catch (IOException e) {
			throw new ApplicationException(
					"Error encoding query for resource with id " + queryRequest.getResourceUUID());
		} catch (ClassCastException | IllegalArgumentException e) {
			logger.error(e.getMessage());
			throw new ProtocolException(ProtocolException.INCORRECTLY_FORMATTED_REQUEST);
		}
	}

	@POST
	@Path("/query/{resourceQueryId}/result")
	public Response queryResult(@PathParam("resourceQueryId") String queryId, QueryRequest resultRequest) {
		if (resultRequest == null) {
			throw new ProtocolException(ProtocolException.MISSING_DATA);
		}

		String pathName = "/query/" + queryId + "/result";

		try {
			QueryRequest chainRequest = new QueryRequest();
			chainRequest.setQuery(resultRequest.getQuery());
			chainRequest.setResourceCredentials(resultRequest.getResourceCredentials());
			chainRequest.setResourceUUID(UUID.fromString(properties.getTargetResourceId()));

			String payload = objectMapper.writeValueAsString(chainRequest);
			HttpResponse response = httpClient.retrievePostResponse(
					httpClient.composeURL(properties.getTargetPicsureUrl(), pathName), createAuthHeader(), payload);
			if (response.getStatusLine().getStatusCode() != 200) {
				logger.error("{}{} calling resource with id {} did not return a 200: {} {} ",
						properties.getTargetPicsureUrl(), pathName, chainRequest.getResourceUUID(),
						response.getStatusLine().getStatusCode(), response.getStatusLine().getReasonPhrase());
				httpClient.throwResponseError(response, properties.getTargetPicsureUrl());
			}

			return Response.ok(response.getEntity().getContent()).build();
		} catch (IOException e) {
			throw new ApplicationException(
					"Error encoding query for resource with id " + resultRequest.getResourceUUID());
		} catch (ClassCastException | IllegalArgumentException e) {
			logger.error(e.getMessage());
			throw new ProtocolException(ProtocolException.INCORRECTLY_FORMATTED_REQUEST);
		}
	}

	@POST
	@Path("/query/{resourceQueryId}/status")
	public QueryStatus queryStatus(@PathParam("resourceQueryId") String queryId, QueryRequest statusRequest) {
		// JNix: Retaining for future use...
		if (statusRequest == null) {
			throw new ProtocolException(ProtocolException.MISSING_DATA);
		}

		String pathName = "/query/" + queryId + "/status";

		try {
			QueryRequest chainRequest = new QueryRequest();
			chainRequest.setQuery(statusRequest.getQuery());
			chainRequest.setResourceCredentials(statusRequest.getResourceCredentials());
			chainRequest.setResourceUUID(UUID.fromString(properties.getTargetResourceId()));

			String payload = objectMapper.writeValueAsString(chainRequest);
			HttpResponse response = httpClient.retrievePostResponse(
					httpClient.composeURL(properties.getTargetPicsureUrl(), pathName), createAuthHeader(), payload);
			if (response.getStatusLine().getStatusCode() != 200) {
				logger.error("{}{} calling resource with id {} did not return a 200: {} {} ",
						properties.getTargetPicsureUrl(), pathName, chainRequest.getResourceUUID(),
						response.getStatusLine().getStatusCode(), response.getStatusLine().getReasonPhrase());
				httpClient.throwResponseError(response, properties.getTargetPicsureUrl());
			}
			QueryStatus queryStatus = httpClient.readObjectFromResponse(response, QueryStatus.class);
			queryStatus.setResourceID(statusRequest.getResourceUUID());
			return queryStatus;
		} catch (IOException e) {
			throw new ApplicationException(
					"Error encoding query for resource with id " + statusRequest.getResourceUUID());
		} catch (ClassCastException | IllegalArgumentException e) {
			logger.error(e.getMessage());
			throw new ProtocolException(ProtocolException.INCORRECTLY_FORMATTED_REQUEST);
		}
	}

	@POST
	@Path("/query/sync")
	@Override
	public Response querySync(QueryRequest queryRequest) {
		if (queryRequest == null) {
			throw new ProtocolException(ProtocolException.MISSING_DATA);
		}
		Object search = queryRequest.getQuery();
		if (search == null) {
			throw new ProtocolException((ProtocolException.MISSING_DATA));
		}

		String pathName = "/query/sync";

		try {
			QueryRequest chainRequest = new QueryRequest();
			chainRequest.setQuery(queryRequest.getQuery());
			chainRequest.setResourceCredentials(queryRequest.getResourceCredentials());
			chainRequest.setResourceUUID(UUID.fromString(properties.getTargetResourceId()));

			String payload = objectMapper.writeValueAsString(chainRequest);
			HttpResponse response = httpClient.retrievePostResponse(
					httpClient.composeURL(properties.getTargetPicsureUrl(), pathName), createAuthHeader(), payload);
			if (response.getStatusLine().getStatusCode() != 200) {
				logger.error("{}{} calling resource with id {} did not return a 200: {} {} ",
						properties.getTargetPicsureUrl(), pathName, chainRequest.getResourceUUID(),
						response.getStatusLine().getStatusCode(), response.getStatusLine().getReasonPhrase());
				httpClient.throwResponseError(response, properties.getTargetPicsureUrl());
			}

			return Response.ok(response.getEntity().getContent()).build();
		} catch (IOException e) {
			throw new ApplicationException(
					"Error encoding query for resource with id " + queryRequest.getResourceUUID());
		} catch (ClassCastException | IllegalArgumentException e) {
			logger.error(e.getMessage());
			throw new ProtocolException(ProtocolException.INCORRECTLY_FORMATTED_REQUEST);
		}
	}

	@POST
	@Path("/search")
	public SearchResults search(QueryRequest searchRequest) {
		if (searchRequest == null) {
			throw new ProtocolException(ProtocolException.MISSING_DATA);
		}
		Object search = searchRequest.getQuery();
		if (search == null) {
			throw new ProtocolException((ProtocolException.MISSING_DATA));
		}

		String pathName = "/search/" + properties.getTargetResourceId();
		try {
			QueryRequest chainRequest = new QueryRequest();
			chainRequest.setQuery(searchRequest.getQuery());
			chainRequest.setResourceCredentials(searchRequest.getResourceCredentials());
			chainRequest.setResourceUUID(UUID.fromString(properties.getTargetResourceId()));

			String payload = objectMapper.writeValueAsString(chainRequest);
			HttpResponse response = httpClient.retrievePostResponse(
					httpClient.composeURL(properties.getTargetPicsureUrl(), pathName), createAuthHeader(), payload);
			if (response.getStatusLine().getStatusCode() != 200) {
				logger.error("{}{} did not return a 200: {} {} ", properties.getTargetPicsureUrl(), pathName,
						response.getStatusLine().getStatusCode(), response.getStatusLine().getReasonPhrase());
				httpClient.throwResponseError(response, properties.getTargetPicsureUrl());
			}
			return httpClient.readObjectFromResponse(response, SearchResults.class);
		} catch (IOException e) {
			// Note: this shouldn't ever happen
			logger.error("Error encoding search payload", e);
			throw new ApplicationException(
					"Error encoding search for resource with id " + searchRequest.getResourceUUID());
		}
	}

	private Header[] createAuthHeader() {
		return new Header[] {
				new BasicHeader(HttpHeaders.AUTHORIZATION, BEARER_STRING + properties.getTargetPicsureToken()) };
	}
}
