package edu.harvard.hms.dbmi.avillach;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Map;
import java.util.UUID;

import javax.ws.rs.*;
import javax.ws.rs.core.Response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.domain.*;
import org.apache.commons.io.IOUtils;
import org.apache.http.Header;
import org.apache.http.HttpResponse;

import edu.harvard.dbmi.avillach.service.IResourceRS;
import org.apache.http.message.BasicHeader;
import org.apache.http.util.EntityUtils;
import org.apache.log4j.Logger;

import static edu.harvard.dbmi.avillach.service.HttpClientUtil.*;


@Path("/v1.4")
@Produces("application/json")
@Consumes("application/json")
public class IRCTResourceRS implements IResourceRS
{
	private static final String TARGET_IRCT_URL = System.getenv("TARGET_IRCT_URL");
//	private static final UUID TARGET_UUID = UUID.fromString(System.getProperty("TEST_UUID"));
	public static final String IRCT_BEARER_TOKEN_KEY = "IRCT_BEARER_TOKEN";
	private static final String AUTHORIZATION = "AUTHORIZATION";
	private static final String BEARER_STRING = "Bearer ";
	private final static ObjectMapper json = new ObjectMapper();
	private Logger logger = Logger.getLogger(this.getClass());


	public IRCTResourceRS() {
		if(TARGET_IRCT_URL == null)
			throw new RuntimeException("TARGET_IRCT_URL environment variable must be set.");
		/*if(TARGET_UUID == null)
			throw new RuntimeException("TEST_UUID must be set");*/
	}
	
	@GET
	@Path("/status")
	public Response status() {
		return Response.ok().build();
	}
	
	@POST
	@Path("/info")
	@Override
	public ResourceInfo info(Map<String,String> resourceCredentials) {
		if (resourceCredentials == null){
			throw new RuntimeException("Missing credentials");
		}
		String pathName = "/resourceService/resources";
		String token = resourceCredentials.get(IRCT_BEARER_TOKEN_KEY);
		if (token == null){
			throw new RuntimeException("Missing credentials");
		}
		Header authorizationHeader = new BasicHeader(AUTHORIZATION, BEARER_STRING + token);
		Header[] headers = new Header[1];
		headers[0] = authorizationHeader;
		HttpResponse resourcesResponse = retrieveGetResponse(TARGET_IRCT_URL + pathName, headers);
		if (resourcesResponse.getStatusLine().getStatusCode() != 200){
			throw new RuntimeException("Resource did not return a 200");
		}
		return new ResourceInfo().setName("IRCT Resource : " + TARGET_IRCT_URL)
				.setQueryFormats(
						readListFromResponse(resourcesResponse, QueryFormat.class));
	}

	@POST
	@Path("/search")
	@Override
	public SearchResults search(QueryRequest searchJson) {
		try {
			if (searchJson == null) {
				throw new RuntimeException("Missing query request data");
			}
			Map<String, String> resourceCredentials = searchJson.getResourceCredentials();
			if (resourceCredentials == null) {
				throw new RuntimeException("Missing credentials");
			}
			String token = resourceCredentials.get(IRCT_BEARER_TOKEN_KEY);
			if (token == null) {
				throw new RuntimeException("Missing credentials");
			}
			String searchTerm = searchJson.getQuery();
			if (searchTerm == null) {
				throw new RuntimeException(("Missing query request data"));
			}
			Header authorizationHeader = new BasicHeader(AUTHORIZATION, BEARER_STRING + token);
			Header[] headers = new Header[1];
			headers[0] = authorizationHeader;
			String pathName = "/resourceService/find?term=" + URLEncoder.encode(searchTerm, "UTF-8");
			HttpResponse resourcesResponse = retrieveGetResponse(TARGET_IRCT_URL + pathName, headers);
			if (resourcesResponse.getStatusLine().getStatusCode() != 200) {
				throw new RuntimeException("Resource did not return a 200");
			}
			SearchResults results = new SearchResults();
			results.setSearchQuery(searchTerm);
			results.setResults(readObjectFromResponse(resourcesResponse, Object.class));
			return results;
		} catch (UnsupportedEncodingException e){
			//TODO what to do about this
			throw new RuntimeException(e);
		}
	}

	@POST
	@Path("/query")
	@Override
	public QueryResults query(QueryRequest queryJson) {
		if (queryJson == null) {
			throw new RuntimeException("Missing query request data");
		}
		Map<String, String> resourceCredentials = queryJson.getResourceCredentials();
		if (resourceCredentials == null) {
			throw new RuntimeException("Missing credentials");
		}
		String token = resourceCredentials.get(IRCT_BEARER_TOKEN_KEY);
		if (token == null) {
			throw new RuntimeException("Missing credentials");
		}
		String queryString = queryJson.getQuery();
		if (queryString == null) {
			throw new RuntimeException(("Missing query request data"));
		}
		//TODO Will we need to do any parsing of the query string?
		Header authorizationHeader = new BasicHeader(AUTHORIZATION, BEARER_STRING + token);
		Header[] headers = new Header[1];
		headers[0] = authorizationHeader;
		String pathName = "/queryService/runQuery";
		HttpResponse resourcesResponse = retrievePostResponse(TARGET_IRCT_URL + pathName, headers, queryString);
		if (resourcesResponse.getStatusLine().getStatusCode() != 200) {
			throw new RuntimeException("Resource did not return a 200");
		}
		QueryResults results = new QueryResults();
		//Returns an object like so: {"resultId":230464}
		//TODO later Needs to persist things like starttime and duration and expiration
		try {
			String responseBody = IOUtils.toString(resourcesResponse.getEntity().getContent(), "UTF-8");
			JsonNode responseNode = json.readTree(responseBody);
			String resultId = responseNode.get("resultId").asText();
			results.setResourceResultId(resultId);
			//TODO what else do we need to set on this QueryResults object?
			//TODO What is the status?
		} catch (IOException e){
			//TODO: Deal with this
			throw new RuntimeException(e);
		}
		return results;
	}

	@POST
	@Path("/query/{resourceQueryId}/status")
	@Override
	public QueryStatus queryStatus(@PathParam("resourceQueryId") String queryId, Map<String, String> resourceCredentials) {
		logger.debug("calling IRCT Resource queryStatus() for query " + queryId);
		if (resourceCredentials == null) {
			throw new RuntimeException("Missing credentials");
		}
		String token = resourceCredentials.get(IRCT_BEARER_TOKEN_KEY);
		if (token == null) {
			throw new RuntimeException("Missing credentials");
		}
		Header authorizationHeader = new BasicHeader(AUTHORIZATION, BEARER_STRING + token);
		Header[] headers = new Header[1];
		headers[0] = authorizationHeader;
		String pathName = "/resultService/resultStatus/"+queryId;
		HttpResponse resourcesResponse = retrieveGetResponse(TARGET_IRCT_URL + pathName, headers);
		if (resourcesResponse.getStatusLine().getStatusCode() != 200) {
			throw new RuntimeException("Resource did not return a 200");
		}
		QueryStatus status = new QueryStatus();
		//Returns an object like so: {"resultId":230958,"status":"AVAILABLE"}
		try {
			//TODO Is this the best way to do this?
			JsonNode responseNode = json.readTree(resourcesResponse.getEntity().getContent());
			//Is this an object as expected or an error message?
			if (responseNode.get("message") != null){
				//TODO Custom exception
				throw new RuntimeException(responseNode.get("message").asText());
			}
			String resourceStatus = responseNode.get("status").asText();
			status.setResourceStatus(resourceStatus);
			//TODO make a proper mapping here
			status.setStatus(mapStatus(resourceStatus));
		} catch (IOException e){
			//TODO: Deal with this
			throw new RuntimeException(e);
		}
		return status;
	}

	@POST
	@Path("/query/{resourceQueryId}/result")
	@Override
	public QueryResults queryResult(@PathParam("resourceQueryId") String queryId, Map<String, String> resourceCredentials) {
		logger.debug("calling IRCT Resource queryResult() for query " + queryId);
		if (resourceCredentials == null) {
			throw new RuntimeException("Missing credentials");
		}
		String token = resourceCredentials.get(IRCT_BEARER_TOKEN_KEY);
		if (token == null) {
			throw new RuntimeException("Missing credentials");
		}
		Header authorizationHeader = new BasicHeader(AUTHORIZATION, BEARER_STRING + token);
		Header[] headers = new Header[1];
		headers[0] = authorizationHeader;
		//TODO How to select the format?
		String pathName = "/resultService/result/"+queryId+"/CSV";
		HttpResponse resourcesResponse = retrieveGetResponse(TARGET_IRCT_URL + pathName, headers);
		if (resourcesResponse.getStatusLine().getStatusCode() != 200) {
			logger.error(resourcesResponse.getStatusLine().getStatusCode() + " " + resourcesResponse.getStatusLine().getReasonPhrase());
			throw new RuntimeException("Resource did not return a 200");
		}
		QueryResults result = new QueryResults();
		//Returns a String basically in the format requested
		try {
			byte[] resultBytes = EntityUtils.toByteArray(resourcesResponse.getEntity());

			//TODO Check if this is data or if there's an error message
			try {
				JsonNode responseNode = json.readTree(resultBytes);
				//Is this an object as expected or an error message?
				if (responseNode.get("message") != null){
					//TODO Custom exception
					throw new RuntimeException(responseNode.get("message").asText());
				}
			} catch (IOException e){
				//This is good, it means that we don't have a node with an error message
			}
			//TODO Is this how we want to do this?
			result.setResults(resultBytes);
			result.setResourceResultId(queryId);
			//TODO What else do we want to fill in?
		} catch (IOException e){
			//TODO: Deal with this
			throw new RuntimeException(e);
		}
		return result;
	}

	private PicSureStatus mapStatus(String resourceStatus){
		//TODO what are actually all the options?
		switch (resourceStatus) {
			case "RUNNING":
				return PicSureStatus.PENDING;
			case "AVAILABLE":
				return PicSureStatus.AVAILABLE;
			case "ERROR":
				return PicSureStatus.ERROR;
			default:
				return null;
		}

	}

}
