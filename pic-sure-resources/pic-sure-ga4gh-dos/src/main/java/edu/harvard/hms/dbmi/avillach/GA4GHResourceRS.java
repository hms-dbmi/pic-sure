package edu.harvard.hms.dbmi.avillach;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.*;

import javax.ws.rs.*;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.domain.*;
import edu.harvard.dbmi.avillach.service.ResourceWebClient;
import edu.harvard.dbmi.avillach.util.PicSureStatus;
import edu.harvard.dbmi.avillach.util.exception.ApplicationException;
import edu.harvard.dbmi.avillach.util.exception.ProtocolException;
import edu.harvard.dbmi.avillach.util.exception.ResourceInterfaceException;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.SerializationUtils;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.commons.lang3.StringUtils;

import edu.harvard.dbmi.avillach.service.IResourceRS;
import org.apache.http.message.BasicHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/datasource")
@Produces("application/json")
@Consumes("application/json")
public class GA4GHResourceRS implements IResourceRS
{
	private String TARGET_URL = System.getenv("TARGET_URL");
	private String RESULT_FORMAT = System.getenv("RESULT_FORMAT");

    private Header[] hdrs = {};

	public static final String BEARER_STRING = "Bearer ";

	public static final String MISSING_REQUEST_DATA_MESSAGE = "Missing query request data";
	public static final String MISSING_CREDENTIALS_MESSAGE = "Missing credentials";

	private final static ObjectMapper json = new ObjectMapper();
	private Logger logger = LoggerFactory.getLogger(this.getClass());

	public GA4GHResourceRS() {
//		if(TARGET_URL == null)
//			throw new RuntimeException("TARGET_URL environment variable must be set.");
//		if(RESULT_FORMAT == null)
//			throw new RuntimeException("RESULT_FORMAT environment variable must be set.");
	}

    /**
     * Create an instance with configuration passed in with a Properties object. It overwrites the defaults
     * from the environment variables, that might be missing.
     * 
     * @param config
     */
	public GA4GHResourceRS(Properties config) {
		if(config.get("TARGET_URL") == null)
			throw new RuntimeException("TARGET_URL environment variable must be set.");

		// TODO: RESULT_FORMAT should be optional and it should default to something, most likely JSON
		if(config.get("RESULT_FORMAT") == null)
			throw new RuntimeException("RESULT_FORMAT environment variable must be set.");

		this.TARGET_URL = (String) config.get("TARGET_URL");
		this.RESULT_FORMAT = (String) config.get("RESULT_FORMAT");
	}

	/**
	 *
	 * @param queryRequest
	 */
	private void retrieveTargetUrl(QueryRequest queryRequest){
		TARGET_URL = queryRequest.getTargetURL();
		if (TARGET_URL == null)
			throw new ApplicationException("This resource needs a target_url to be pre-configured, please contact admin.");
	}

	@GET
	@Path("/status")
	public Response status() {
		return Response.ok().build();
	}

	@POST
	@Path("/info")
	@Override
	public ResourceInfo info(QueryRequest queryRequest) {
		logger.debug("Getting information about the datasource");
		retrieveTargetUrl(queryRequest);

		// TODO the `service-info` should be dynamically assigned, on a per resource basis, or come from the request
		/*String targetURL = TARGET_URL + "service-info";
		HttpResponse response = retrieveGetResponse(targetURL, this.hdrs);

		if (response.getStatusLine().getStatusCode() != 200){
            logger.error(targetURL +" did not return a 200: {} {}",
					response.getStatusLine().getStatusCode(),
					response.getStatusLine().getReasonPhrase()
			);

			// TODO Is there a better way to make sure the correct exception type is thrown?
		    if (response.getStatusLine().getStatusCode() == 401) {
                throw new NotAuthorizedException(targetURL + " " + response.getStatusLine().getStatusCode() + " " + response.getStatusLine().getReasonPhrase());
            }

			throw new ResourceInterfaceException(targetURL + " " + response.getStatusLine().getStatusCode() + " " + response.getStatusLine().getReasonPhrase());
		}*/

		// TODO: fake it for now, until `service-info` becomes available
		QueryFormat qf = new QueryFormat();
		qf.setDescription("Sample Description 1");
		qf.setName("Sample QueryFormat name");
		List<QueryFormat> queryFormats = Arrays.asList(qf);
		return new ResourceInfo().setName("GA4GH DOS API Server").setQueryFormats(queryFormats);
	}

	@POST
	@Path("/search")
	@Override
	public SearchResults search(QueryRequest searchJson) {
		logger.debug("Searching datasource for objects");
		retrieveTargetUrl(searchJson);
		String searchTerm = null;
		try {
			if (searchJson == null) {
				throw new ProtocolException(MISSING_REQUEST_DATA_MESSAGE);
			} else {
                Object searchQuery = searchJson.getQuery();
                if (searchQuery == null) {
                    throw new ProtocolException((MISSING_REQUEST_DATA_MESSAGE));
                } else {
                	logger.debug("/search search "+searchQuery.toString());
                    JsonNode queryNode = json.valueToTree(searchQuery);
                    JsonNode searchTermNode = queryNode.get("searchTerm");
                    if (searchTermNode != null){
                        searchTerm = searchTermNode.toString().replace("\"","");
                    }
                }
            }

			String targetURL = TARGET_URL + "dataobjects";
			if (searchTerm != null) {
			    targetURL = targetURL + "/" + searchTerm;
            }
			logger.debug("search() targetURL:"+targetURL);

			HttpResponse response = edu.harvard.hms.dbmi.avillach.HttpClientUtil.retrieveGetResponse(targetURL, null);
			SearchResults results = new SearchResults();

			//results.setSearchQuery(searchTerm);
			if (response.getStatusLine().getStatusCode() != 200) {
				logger.error(targetURL + " did not return a 200: {} {}",response.getStatusLine().getStatusCode(), response.getStatusLine().getReasonPhrase());
				//If the result is empty, a 500 is thrown for some reason
				JsonNode responseObject = json.readTree(response.getEntity().getContent());
				if (response.getStatusLine().getStatusCode() == 500 && responseObject.get("message") != null && responseObject.get("message").asText().equals("No entities were found.")) {
					return results;
				}
					//TODO Is there a better way to make sure the correct exception type is thrown?
				if (response.getStatusLine().getStatusCode() == 401) {
					throw new NotAuthorizedException(TARGET_URL + " " + response.getStatusLine().getStatusCode() + " " + response.getStatusLine().getReasonPhrase());
				}
				throw new ResourceInterfaceException(TARGET_URL + " " + response.getStatusLine().getStatusCode() + " " + response.getStatusLine().getReasonPhrase());
			}

			results.setResults(edu.harvard.hms.dbmi.avillach.HttpClientUtil.readDataObjectsFromResponse(response, Object.class));
			return results;

		} catch (UnsupportedEncodingException e){
			//TODO what to do about this
			throw new ApplicationException("Error encoding search term: " + e.getMessage());
		} catch (IOException e){
			throw new ApplicationException("Error reading response: " + e.getMessage());
		}
	}

	@POST
	@Path("/query")
	@Override
	public QueryStatus query(QueryRequest queryJson) {
		logger.debug("Query the datasource");

		retrieveTargetUrl(queryJson);
		if (queryJson == null) {
			throw new ProtocolException(MISSING_REQUEST_DATA_MESSAGE);
		}

		Map<String, String> resourceCredentials = queryJson.getResourceCredentials();
		/*if (resourceCredentials == null) {
			throw new NotAuthorizedException(MISSING_CREDENTIALS_MESSAGE);
		}
		String token = resourceCredentials.get(BEARER_TOKEN);
		if (token == null) {
			throw new NotAuthorizedException(MISSING_CREDENTIALS_MESSAGE);
		}*/

        long starttime = new Date().getTime();
        QueryStatus status = new QueryStatus();
        status.setStartTime(starttime);
        status.setStatus(PicSureStatus.QUEUED);

		//TODO Do we want/need to do it this way, should we revert query field back to string?
		Object queryObject = queryJson.getQuery();
		if (queryObject == null) {
			throw new ProtocolException((MISSING_REQUEST_DATA_MESSAGE));
		} else {
		    logger.debug("query() "+queryObject.toString());
        }

		JsonNode queryNode = json.valueToTree(queryObject);
        logger.debug("query() queryNode (toString):"+queryNode.toString());
        if (queryNode.isTextual()) {
            logger.debug("query() queryNode (asText):" + queryNode.asText());
            logger.debug("query() queryNode (toString):" + queryNode.textValue());
        }
		String queryString = null;

		JsonNode query = queryNode.get("queryString");
		if (query == null){
			//Assume this means the entire string is the query - Object nodes return blank asText but JsonNodes add too many quotes
			queryString = StringUtils.isBlank(queryNode.asText()) ? queryNode.toString() : queryNode.asText();
		} else {
			queryString = query.toString();
		}
		logger.debug("query() queryString: "+queryString);

		String pathName = TARGET_URL + "dataobjects/"+queryString.replace("\"", "");
		logger.debug("query() pathName: "+pathName);
		HttpResponse response = edu.harvard.hms.dbmi.avillach.HttpClientUtil.retrieveGetResponse(pathName, null);

		if (response.getStatusLine().getStatusCode() != 200) {
			logger.error(TARGET_URL + pathName + " did not return a 200: {} {} ", response.getStatusLine().getStatusCode(), response.getStatusLine().getReasonPhrase());
			//TODO Is there a better way to make sure the correct exception type is thrown?
			if (response.getStatusLine().getStatusCode() == 401) {
				throw new NotAuthorizedException(TARGET_URL + " " + response.getStatusLine().getStatusCode() + " " + response.getStatusLine().getReasonPhrase());
			}
			throw new ResourceInterfaceException(TARGET_URL + " " + response.getStatusLine().getStatusCode() + " " + response.getStatusLine().getReasonPhrase());
		}
		//Returns an object like so: {"resultId":230464}
		//TODO later Add things like duration and expiration
		try {
			String responseBody = IOUtils.toString(response.getEntity().getContent(), "UTF-8");
			JsonNode responseNode = json.readTree(responseBody);

            long endtime = new Date().getTime();
            status.setDuration(endtime-starttime);
			status.setPicsureResultId(UUID.fromString(responseNode.get("data_object").get("id").asText()));
			status.setStatus(PicSureStatus.AVAILABLE);

			status.setResultMetadata(SerializationUtils.serialize(responseBody));

			status.setSizeInBytes(responseBody.length());
			return status;
		} catch (IOException e){
			//TODO: Deal with this
			throw new ApplicationException(e);
		}
	}

	@POST
	@Path("/query/{resourceQueryId}/status")
	@Override
	public QueryStatus queryStatus(@PathParam("resourceQueryId") String queryId, QueryRequest statusRequest) {
		logger.debug("Getting status for for queryId {}", queryId);

		retrieveTargetUrl(statusRequest);
        Map<String, String> resourceCredentials = statusRequest.getResourceCredentials();
		if (resourceCredentials == null) {
			throw new NotAuthorizedException(MISSING_CREDENTIALS_MESSAGE);
		}

		String pathName = "resultService/resultStatus/"+queryId;
		HttpResponse response = edu.harvard.hms.dbmi.avillach.HttpClientUtil.retrieveGetResponse(TARGET_URL + pathName, null);
		if (response.getStatusLine().getStatusCode() != 200) {
			logger.error(TARGET_URL + pathName + " did not return a 200: {} {}", response.getStatusLine().getStatusCode(), response.getStatusLine().getReasonPhrase());
			//TODO Is there a better way to make sure the correct exception type is thrown?
			if (response.getStatusLine().getStatusCode() == 401) {
				throw new NotAuthorizedException(TARGET_URL + " " + response.getStatusLine().getStatusCode() + " " + response.getStatusLine().getReasonPhrase());
			}
			throw new ResourceInterfaceException(TARGET_URL + " " + response.getStatusLine().getStatusCode() + " " + response.getStatusLine().getReasonPhrase());
		}
		//Returns an object like so: {"resultId":230958,"status":"AVAILABLE"}
		QueryStatus status = new QueryStatus();
		try {
			//TODO Is this the best way to do this?
			JsonNode responseNode = json.readTree(response.getEntity().getContent());
			//Is this an object as expected or an error message?
			/*if (responseNode.get("message") != null){
				//TODO Custom exception
				throw new ResourceInterfaceException(responseNode.get("message").asText());
			}*/
			String resourceStatus = responseNode.get("status").asText();
			status.setResourceStatus(resourceStatus);
			status.setStatus(mapStatus(resourceStatus));
			status.setResourceResultId(responseNode.get("resultId").asText());
		} catch (IOException e){
			//TODO: Deal with this
			throw new ApplicationException(e);
		}
		logger.debug("Finished. Returning status {}", status.toString());
		return status;
	}

	@POST
	@Path("/query/{dataObjectId}/result")
	@Override
	public Response queryResult(@PathParam("dataObjectId") String dataObjectId, QueryRequest statusRequest) {
        logger.debug("queryResult() calling dataobject/{}", dataObjectId);

		retrieveTargetUrl(statusRequest);
        Map<String, String> resourceCredentials = statusRequest.getResourceCredentials();
		String pathName = TARGET_URL + "dataobjects/"+dataObjectId;
        logger.debug("queryResult() pathName:{}", pathName);

		HttpResponse response = edu.harvard.hms.dbmi.avillach.HttpClientUtil.retrieveGetResponse(pathName, null);
		if (response.getStatusLine().getStatusCode() != 200) {
			logger.error(TARGET_URL + pathName + " did not return a 200: {} {}", response.getStatusLine().getStatusCode(), response.getStatusLine().getReasonPhrase());
			//TODO Is there a better way to make sure the correct exception type is thrown?
			if (response.getStatusLine().getStatusCode() == 401) {
				throw new NotAuthorizedException(TARGET_URL + " " + response.getStatusLine().getStatusCode() + " " + response.getStatusLine().getReasonPhrase());
			}
				throw new ResourceInterfaceException(TARGET_URL + " " + response.getStatusLine().getStatusCode() + " " + response.getStatusLine().getReasonPhrase());
		}

		try {
			return Response.ok(response.getEntity().getContent()).build();
		} catch (IOException e){
			//TODO: Deal with this
			throw new ApplicationException(e);
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

	private Header[] createAuthorizationHeader(String token){
		Header authorizationHeader = new BasicHeader(HttpHeaders.AUTHORIZATION, ResourceWebClient.BEARER_STRING + token);
		Header[] headers = {authorizationHeader};
		return headers;
	}

}
