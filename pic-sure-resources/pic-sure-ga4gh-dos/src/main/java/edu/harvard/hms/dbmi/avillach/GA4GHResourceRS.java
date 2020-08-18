package edu.harvard.hms.dbmi.avillach;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.annotation.Annotation;
import java.net.URI;
import java.net.URLEncoder;
import java.util.*;

import javax.lang.model.type.ArrayType;
import javax.persistence.NoResultException;
import javax.ws.rs.*;
import javax.ws.rs.core.*;

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
	private String TARGET_URL = System.getenv("GA4GH_TARGET_URL");
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
        if (TARGET_URL == null)
            throw new ApplicationException(ApplicationException.MISSING_TARGET_URL);
	}

	@GET
	@Path("/status")
	public Response status() {
        Map<String, Object> statusResponse = new HashMap<>();
        statusResponse.put("status", "ok");
        statusResponse.put("name", "GA4GH-DOS-Server_ResourceInterface");
		return Response.ok().entity(statusResponse).build();
	}

    @GET
    @Path("/info")
    public ResourceInfo info() {
        logger.debug("GET /info");

        // TODO: fake it for now, until `service-info` becomes available
        List<QueryFormat> qformatList = new ArrayList<QueryFormat>();

        qformatList.add(new QueryFormat()
                .setName("POST /search")
                .setDescription("Search the datasource for a specific object. If `query` is empty, all objects will be returned."+
                " If `id` is specified, the server will try to look up the details for the specific object."));

        qformatList.add(new QueryFormat()
                .setName("POST /query")
                .setDescription("Retrieve a specific object from the server. The `query` has to specify the `id` of the object."));

        return new ResourceInfo().setName("GA4GH DOS API Server").setQueryFormats(qformatList);
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
		List<QueryFormat> queryFormats = new ArrayList<>(Arrays.asList(qf));
		return new ResourceInfo().setName("GA4GH DOS API Server").setQueryFormats(queryFormats);
	}

	@POST
	@Path("/search")
	@Override
	public SearchResults search(QueryRequest searchJson) {
		logger.debug("POST /search Starting...");

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
                    JsonNode searchTermNode = queryNode.get("id");
                    if (searchTermNode != null){
                        searchTerm = searchTermNode.asText();// toString().replace("\"","");
                    } else {
                        searchTerm = null;
                    }
                }
            }

			String targetURL = TARGET_URL + "dataobjects";
			logger.debug("search() searchTerm is now `"+searchTerm+"`");
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

				// If 404 is returned, it means that the term could not be found on the server.
                if (response.getStatusLine().getStatusCode() == 404) {
				    // logger.error("Search endpoint could not find the requested object. Throwing an application error.");
				    // throw new NoResultException("Could not find any obect matching that id.");

                    Map<String, Object> statusResponse = new HashMap<>();
                    statusResponse.put("status", "NOT_FOUND");
                    statusResponse.put("message", "The server could not find any object with id `"+searchTerm+"`");
                    results.setResults(statusResponse);
                    return results;
                }

				throw new ResourceInterfaceException("Search endpoint exception:" +
                        response.getStatusLine().getStatusCode() +
                        " " +
                        response.getStatusLine().getReasonPhrase()
                );
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
		logger.debug("POST /query Starting...");

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

		// Get the objectId from the query being passed to the RS
        Object queryObject = queryJson.getQuery();
		if (queryObject == null) {
			throw new ProtocolException((MISSING_REQUEST_DATA_MESSAGE));
		}
		JsonNode queryNode = json.valueToTree(queryObject);
		String objectIdString = null;
		JsonNode objectId = queryNode.get("id");
		if (objectId == null){
			//Assume this means the entire string is the query - Object nodes return blank asText but JsonNodes add too many quotes
			objectIdString = StringUtils.isBlank(queryNode.asText()) ? queryNode.toString() : queryNode.asText();
		} else {
            objectIdString = objectId.asText();
		}
		logger.debug("query() objectIdString: "+objectIdString);

		// Generate the endpoint URL
		String endpointURL = TARGET_URL + "dataobjects/"+objectIdString.replace("\"", "");
		logger.debug("query() endpointURL: "+endpointURL);

		// Get the response from the endpoint URL, this is (for now) a GET
		HttpResponse response = edu.harvard.hms.dbmi.avillach.HttpClientUtil.retrieveGetResponse(endpointURL, null);
		if (response.getStatusLine().getStatusCode() != 200) {
			logger.error("The query endpoint did not return a 200. code:{} reason:{} ",
                    response.getStatusLine().getStatusCode(),
                    response.getStatusLine().getReasonPhrase()
            );
			// Throw the HTTP error as an RS Exception to the client.
			throw new ResourceInterfaceException("Query endpoint exception: " + response.getStatusLine().getStatusCode() + " " + response.getStatusLine().getReasonPhrase());
		}

		// If the HTTP Response is a success, then returns an object like so: {"resultId":230464}
		//TODO later Add things like duration and expiration
		try {
			String responseBody = IOUtils.toString(response.getEntity().getContent(), "UTF-8");
			JsonNode responseNode = json.readTree(responseBody);

            long endtime = new Date().getTime();
            status.setDuration(endtime-starttime);
			status.setPicsureResultId(UUID.fromString(responseNode.get("data_object").get("id").asText()));
			status.setStatus(PicSureStatus.AVAILABLE);
			
			Map<String, Object> metadata = new HashMap<String, Object>();
	        metadata.put("queryResultMetadata", responseBody);
	        status.setResultMetadata(metadata);
			
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
