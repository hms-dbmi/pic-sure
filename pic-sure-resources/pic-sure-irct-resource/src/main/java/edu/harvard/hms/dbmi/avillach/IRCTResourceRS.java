package edu.harvard.hms.dbmi.avillach;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Date;
import java.util.Map;

import javax.ws.rs.*;
import javax.ws.rs.core.Response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.domain.*;
import edu.harvard.dbmi.avillach.exception.ResourceCommunicationException;
import org.apache.commons.io.IOUtils;
import org.apache.http.Header;
import org.apache.http.HttpResponse;

import edu.harvard.dbmi.avillach.service.IResourceRS;
import org.apache.http.message.BasicHeader;
import org.apache.log4j.Logger;

import static edu.harvard.dbmi.avillach.service.HttpClientUtil.*;


@Path("/v1.4")
@Produces("application/json")
@Consumes("application/json")
public class IRCTResourceRS implements IResourceRS
{
	private static final String TARGET_IRCT_URL = System.getenv("TARGET_IRCT_URL");
	private static final String RESULT_FORMAT = System.getenv("RESULT_FORMAT");
	public static final String IRCT_BEARER_TOKEN_KEY = "IRCT_BEARER_TOKEN";
	private static final String AUTHORIZATION = "AUTHORIZATION";
	private static final String BEARER_STRING = "Bearer ";
	private final static ObjectMapper json = new ObjectMapper();
	private Logger logger = Logger.getLogger(this.getClass());


	public IRCTResourceRS() {
		if(TARGET_IRCT_URL == null)
			throw new RuntimeException("TARGET_IRCT_URL environment variable must be set.");
		if(RESULT_FORMAT == null)
			throw new RuntimeException("RESULT_FORMAT environment variable must be set.");
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
		logger.debug("Calling IRCT Resource info()");
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
		HttpResponse response = retrieveGetResponse(TARGET_IRCT_URL + pathName, headers);
		if (response.getStatusLine().getStatusCode() != 200){
			logger.error(TARGET_IRCT_URL + " did not return a 200: " + response.getStatusLine().getStatusCode() + " " + response.getStatusLine().getReasonPhrase());
			throw new ResourceCommunicationException(TARGET_IRCT_URL, response.getStatusLine().getStatusCode() + " " + response.getStatusLine().getReasonPhrase());
		}
		return new ResourceInfo().setName("IRCT Resource : " + TARGET_IRCT_URL)
				.setQueryFormats(
						readListFromResponse(response, QueryFormat.class));
	}

	@POST
	@Path("/search")
	@Override
	public SearchResults search(QueryRequest searchJson) {
		logger.debug("Calling IRCT Resource search()");
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
			Object search = searchJson.getQuery();
			if (search == null) {
				throw new RuntimeException(("Missing query request data"));
			}
			String searchTerm = search.toString();
			Header authorizationHeader = new BasicHeader(AUTHORIZATION, BEARER_STRING + token);
			Header[] headers = new Header[1];
			headers[0] = authorizationHeader;
			String pathName = "/resourceService/find?term=" + URLEncoder.encode(searchTerm, "UTF-8");
			HttpResponse response = retrieveGetResponse(TARGET_IRCT_URL + pathName, headers);
			if (response.getStatusLine().getStatusCode() != 200) {
				logger.error(TARGET_IRCT_URL + " did not return a 200: " + response.getStatusLine().getStatusCode() + " " + response.getStatusLine().getReasonPhrase());
				throw new ResourceCommunicationException(TARGET_IRCT_URL, response.getStatusLine().getStatusCode() + " " + response.getStatusLine().getReasonPhrase());
			}
			SearchResults results = new SearchResults();
			results.setSearchQuery(searchTerm);
			results.setResults(readObjectFromResponse(response, Object.class));
			return results;
		} catch (UnsupportedEncodingException e){
			//TODO what to do about this
			throw new RuntimeException(e);
		}
	}

	@POST
	@Path("/query")
	@Override
	public QueryStatus query(QueryRequest queryJson) {
		logger.debug("Calling IRCT Resource query()");
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

		//TODO Do we want/need to do it this way, should we revert query field back to string?
		Object queryObject = queryJson.getQuery();
		if (queryObject == null) {
			throw new RuntimeException(("Missing query request data"));
		}

		JsonNode queryNode = json.valueToTree(queryObject);
		String queryString = null;

		JsonNode query = queryNode.get("queryString");
		if (query == null){
			//Assume this means the entire string is the query
			queryString = queryNode.asText();
		} else {
			queryString = query.asText();
		}

		Header authorizationHeader = new BasicHeader(AUTHORIZATION, BEARER_STRING + token);
		Header[] headers = new Header[1];
		headers[0] = authorizationHeader;
		String pathName = "/queryService/runQuery";
		long starttime = new Date().getTime();
		HttpResponse response = retrievePostResponse(TARGET_IRCT_URL + pathName, headers, queryString);
		if (response.getStatusLine().getStatusCode() != 200) {
			logger.error(TARGET_IRCT_URL + " did not return a 200: " + response.getStatusLine().getStatusCode() + " " + response.getStatusLine().getReasonPhrase());
			throw new ResourceCommunicationException(TARGET_IRCT_URL, response.getStatusLine().getStatusCode() + " " + response.getStatusLine().getReasonPhrase());
		}
		//Returns an object like so: {"resultId":230464}
		//TODO later Add things like duration and expiration
		try {
			String responseBody = IOUtils.toString(response.getEntity().getContent(), "UTF-8");
			JsonNode responseNode = json.readTree(responseBody);
			String resultId = responseNode.get("resultId").asText();
			//Check to see if it's ready yet, if not just send back running with no results
			QueryStatus status = queryStatus(resultId, resourceCredentials);
			status.setResourceResultId(resultId);
			status.setStartTime(starttime);
			//Changing response to QueryStatus from QueryResponse makes it impossible to send back results right away
			/*			results.setStatus(status);
			//If it's already ready go ahead and get the results
			if(status.getStatus() == PicSureStatus.AVAILABLE){
				results = queryResult(resultId, resourceCredentials);
				results.getStatus().setStartTime(starttime);
			}*/
			return status;
		} catch (IOException e){
			//TODO: Deal with this
			throw new RuntimeException(e);
		}
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
		HttpResponse response = retrieveGetResponse(TARGET_IRCT_URL + pathName, headers);
		if (response.getStatusLine().getStatusCode() != 200) {
			logger.error(TARGET_IRCT_URL + " did not return a 200: " + response.getStatusLine().getStatusCode() + " " + response.getStatusLine().getReasonPhrase());
			throw new ResourceCommunicationException(TARGET_IRCT_URL, response.getStatusLine().getStatusCode() + " " + response.getStatusLine().getReasonPhrase());
		}
		//Returns an object like so: {"resultId":230958,"status":"AVAILABLE"}
		QueryStatus status = new QueryStatus();
		try {
			//TODO Is this the best way to do this?
			JsonNode responseNode = json.readTree(response.getEntity().getContent());
			//Is this an object as expected or an error message?
			if (responseNode.get("message") != null){
				//TODO Custom exception
				throw new RuntimeException(responseNode.get("message").asText());
			}
			String resourceStatus = responseNode.get("status").asText();
			status.setResourceStatus(resourceStatus);
			status.setStatus(mapStatus(resourceStatus));
			status.setResourceResultId(responseNode.get("resultId").asText());
		} catch (IOException e){
			//TODO: Deal with this
			throw new RuntimeException(e);
		}
		return status;
	}

	@POST
	@Path("/query/{resourceQueryId}/result")
	@Override
	public Response queryResult(@PathParam("resourceQueryId") String queryId, Map<String, String> resourceCredentials) {
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
		String pathName = "/resultService/result/"+queryId+"/"+RESULT_FORMAT;
		//Returns a String in the format requested
		HttpResponse response = retrieveGetResponse(TARGET_IRCT_URL + pathName, headers);
		if (response.getStatusLine().getStatusCode() != 200) {
			logger.error(TARGET_IRCT_URL + " did not return a 200: " + response.getStatusLine().getStatusCode() + " " + response.getStatusLine().getReasonPhrase());
			throw new ResourceCommunicationException(TARGET_IRCT_URL, response.getStatusLine().getStatusCode() + " " + response.getStatusLine().getReasonPhrase());
		}
		try {
			return Response.ok(response.getEntity().getContent()).build();
		} catch (IOException e){
			//TODO: Deal with this
			throw new RuntimeException(e);
		}
	}

	private PicSureStatus mapStatus(String resourceStatus){
		//TODO what are actually all the options?  What should the default be? What if it's something that doesn't match?
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
