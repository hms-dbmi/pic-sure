package edu.harvard.hms.dbmi.avillach;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.Weigher;
import edu.harvard.dbmi.avillach.domain.*;
import edu.harvard.dbmi.avillach.service.IResourceRS;
import edu.harvard.dbmi.avillach.service.ResourceWebClient;
import edu.harvard.dbmi.avillach.util.PicSureStatus;
import edu.harvard.dbmi.avillach.util.exception.ApplicationException;
import edu.harvard.dbmi.avillach.util.exception.ProtocolException;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.message.BasicHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.ServletContext;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static edu.harvard.dbmi.avillach.util.HttpClientUtil.*;


@Path("/v1.4")
@Produces("application/json")
@Consumes("application/json")
public class IRCTResourceRS implements IResourceRS
{
	private static String RESULT_FORMAT = System.getenv("RESULT_FORMAT");
	private static final String DEFAULT_RESULT_FORMAT = "JSON";

	public static final String IRCT_BEARER_TOKEN_KEY = "IRCT_BEARER_TOKEN";

	public static final String MISSING_CREDENTIALS_MESSAGE = "Missing credentials";

    private static String targetURL;

    public static LoadingCache<String, SearchResults> searchLoadingCache = CacheBuilder.newBuilder()
            .maximumWeight(JAXRSConfiguration.MAXIMUM_WEIGHT)
            .weigher(new Weigher<String, SearchResults>() {
                @Override
                public int weigh(String s, SearchResults searchResults) {
                    return ((String)searchResults.getResults()).length();
                }
            })
            .build(
                new CacheLoader<>() {
                @Override
                public SearchResults load(String searchString) throws Exception {
                    return IRCTResourceRS.loadSearchResults(searchString);
                }
            }
        );

	/**
	 * to store token for current thread
	 */
	private String token;

	@Context
	private ServletContext context;

	private final static ObjectMapper json = new ObjectMapper();
	private static Logger logger = LoggerFactory.getLogger(IRCTResourceRS.class);

	public IRCTResourceRS() {
		if(RESULT_FORMAT == null || RESULT_FORMAT.isEmpty()){
			logger.warn("RESULT_FORMAT environment variable has not been set yet. Using the default one: " + DEFAULT_RESULT_FORMAT);
			RESULT_FORMAT = DEFAULT_RESULT_FORMAT;
		}

	}

	@PostConstruct
	public void init() {
		try {
			InitialContext ctx = new InitialContext();
			targetURL = (String) ctx.lookup("java:global/target_url_" + context.getContextPath().replaceAll("/",""));
		} catch (NamingException e) {
			throw new RuntimeException("Could not find JNDI name : "  + "java:global/target_url_" + context.getContextPath().replaceAll("/","") + " --- please put your irct target url here");
		}

	}

	@GET
	@Path("/status")
	public Response status() {
		return Response.ok().build();
	}

	@POST
	@Path("/info")
	//	@Override
	public ResourceInfo info(QueryRequest queryRequest) {
		logger.debug("Calling IRCT Resource info()");
		if (targetURL == null || targetURL.isEmpty())
			throw new ApplicationException(ApplicationException.MISSING_TARGET_URL);

		if (queryRequest == null){
			throw new ProtocolException(ProtocolException.MISSING_DATA);
		}
		if (queryRequest.getResourceCredentials() == null){
			throw new NotAuthorizedException(MISSING_CREDENTIALS_MESSAGE);
		}
		String token = queryRequest.getResourceCredentials().get(IRCT_BEARER_TOKEN_KEY);
		if (token == null){
			throw new NotAuthorizedException(MISSING_CREDENTIALS_MESSAGE);
		}
		String pathName = "resourceService/resources";

		HttpResponse response = retrieveGetResponse(composeURL(targetURL, pathName), createAuthorizationHeader(token));
		if (response.getStatusLine().getStatusCode() != 200){
			logger.error(targetURL + " did not return a 200: {} {}", response.getStatusLine().getStatusCode(), response.getStatusLine().getReasonPhrase());
			throwResponseError(response, targetURL);
		}
		return new ResourceInfo().setName("IRCT Resource : " + targetURL)
				.setQueryFormats(
						readListFromResponse(response, QueryFormat.class));
	}

	@POST
	@Path("/search")
	@Override
	public SearchResults search(QueryRequest searchJson) {
		logger.debug("Calling IRCT Resource search()");
		try {
			return getSearchResults(searchJson);
		} catch (UnsupportedEncodingException e){
			//TODO what to do about this
			throw new ApplicationException("Error encoding search term: " + e.getMessage());
		} catch (IOException e){
			throw new ApplicationException("Error reading response: " + e.getMessage());
		}
	}

	/**
	 * for cache loader load result
	 * @param search
	 * @return
	 * @throws NotAuthorizedException
	 * @throws IOException
	 */
	private static SearchResults loadSearchResults(String search)
			throws NotAuthorizedException, IOException {

		String searchTerm = search;

		String pathName = "resourceService/find";
		String queryParameter = "?term=" + URLEncoder.encode(searchTerm, "UTF-8");
		HttpResponse response = retrieveGetResponse(composeURL(targetURL, pathName) + queryParameter, createAuthorizationHeader(JAXRSConfiguration.search_token));
		SearchResults results = new SearchResults();
		results.setSearchQuery(searchTerm);
		if (response.getStatusLine().getStatusCode() != 200) {
			logger.error(targetURL + " did not return a 200: {} {}",response.getStatusLine().getStatusCode(), response.getStatusLine().getReasonPhrase());
			//If the result is empty, a 500 is thrown for some reason
			JsonNode responseObject = json.readTree(response.getEntity().getContent());
			if (response.getStatusLine().getStatusCode() == 500 && responseObject.get("message") != null && responseObject.get("message").asText().equals("No entities were found.")) {
				return results;
			}
			throwResponseError(response, targetURL);
		}
		results.setResults(readObjectFromResponse(response, Object.class));
		return results;
	}

	private SearchResults getSearchResults(QueryRequest searchJson)
			throws IOException{
		if (targetURL == null || targetURL.isEmpty()) {
			throw new ApplicationException(ApplicationException.MISSING_TARGET_URL);
		}
		if (searchJson == null) {
			throw new ProtocolException(ProtocolException.MISSING_DATA);
		}
		Map<String, String> resourceCredentials = searchJson.getResourceCredentials();
		if (resourceCredentials == null) {
			throw new NotAuthorizedException(MISSING_CREDENTIALS_MESSAGE);
		}
//		String token = resourceCredentials.get(IRCT_BEARER_TOKEN_KEY);
//		if (token == null) {
//			throw new NotAuthorizedException(MISSING_CREDENTIALS_MESSAGE);
//		} else {
//			this.token = token;
//		}
		Object search = searchJson.getQuery();
		if (search == null) {
			throw new ProtocolException((ProtocolException.MISSING_DATA));
		}
		if (search instanceof String){
			return searchLoadingCache.getUnchecked((String)search);
		} else {
			// This must be a list of searches, because that's the only other thing we support
			ObjectMapper mapper = new ObjectMapper();
			try{
				List<String> entityPaths = mapper.readValue(mapper.writeValueAsString(search), List.class);
			} catch(Exception e) {
				logger.error("Could not parse jsonPaths, client made a mistake of some kind : " + mapper.writeValueAsString(search));
				throw new ProtocolException(ProtocolException.INCORRECTLY_FORMATTED_REQUEST);
			}
			HttpResponse response = retrievePostResponse(composeURL(targetURL, "resourceService/jsonPath"), createAuthorizationHeader(token), mapper.writeValueAsString(search));
			SearchResults results = new SearchResults();
			results.setSearchQuery(mapper.writeValueAsString(search));
			results.setResults(mapper.readValue(response.getEntity().getContent(), List.class));
			return results;
		}
	}

	@POST
	@Path("/query")
	@Override
	public QueryStatus query(QueryRequest queryJson) {
		logger.debug("Calling IRCT Resource query()");
		if (targetURL == null || targetURL.isEmpty()){
			throw new ApplicationException(ApplicationException.MISSING_TARGET_URL);
		}
		if (queryJson == null) {
			throw new ProtocolException(ProtocolException.MISSING_DATA);
		}
		Map<String, String> resourceCredentials = queryJson.getResourceCredentials();
		if (resourceCredentials == null) {
			throw new NotAuthorizedException(MISSING_CREDENTIALS_MESSAGE);
		}
		String token = resourceCredentials.get(IRCT_BEARER_TOKEN_KEY);
		if (token == null) {
			throw new NotAuthorizedException(MISSING_CREDENTIALS_MESSAGE);
		}

		//TODO Do we want/need to do it this way, should we revert query field back to string?
		Object queryObject = queryJson.getQuery();
		if (queryObject == null) {
			throw new ProtocolException(ProtocolException.MISSING_DATA);
		}

		JsonNode queryNode = json.valueToTree(queryObject);
		String queryString = null;

		JsonNode query = queryNode.get("queryString");
		if (query == null){
			//Assume this means the entire string is the query - Object nodes return blank asText but JsonNodes add too many quotes
			queryString = StringUtils.isBlank(queryNode.asText()) ? queryNode.toString() : queryNode.asText();
		} else {
			queryString = query.toString();
		}

		String pathName = "queryService/runQuery";
		long starttime = new Date().getTime();
		HttpResponse response = retrievePostResponse(composeURL(targetURL, pathName), createAuthorizationHeader(token), queryString);
		if (response.getStatusLine().getStatusCode() != 200) {
			logger.error(targetURL + " did not return a 200: {} {} ", response.getStatusLine().getStatusCode(), response.getStatusLine().getReasonPhrase());
			throwResponseError(response, targetURL);
		}
		//Returns an object like so: {"resultId":230464}
		//TODO later Add things like duration and expiration
		try {
			String responseBody = IOUtils.toString(response.getEntity().getContent(), "UTF-8");
			JsonNode responseNode = json.readTree(responseBody);
			String resultId = responseNode.get("resultId").asText();

			logger.debug("IRCT_RS query() get a resultId: " + resultId);
			//Check to see if it's ready yet, if not just send back running with no results
			QueryStatus status = queryStatus(resultId, queryJson);
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
			throw new ApplicationException(e);
		}
	}

	private String getTargetURL() {
		// TODO Auto-generated method stub
		return null;
	}

	@POST
	@Path("/query/{resourceQueryId}/status")
	@Override
	public QueryStatus queryStatus(@PathParam("resourceQueryId") String queryId, QueryRequest statusQuery) {
		logger.debug("calling IRCT Resource queryStatus() for query {}", queryId);
		if (targetURL == null || targetURL.isEmpty()){
			throw new ApplicationException(ApplicationException.MISSING_TARGET_URL);
		}
		if (statusQuery == null){
			throw new ProtocolException(ProtocolException.MISSING_DATA);
		}
		Map<String, String> resourceCredentials = statusQuery.getResourceCredentials();
		if (resourceCredentials == null) {
			throw new NotAuthorizedException(MISSING_CREDENTIALS_MESSAGE);
		}
		String token = resourceCredentials.get(IRCT_BEARER_TOKEN_KEY);
		if (token == null) {
			throw new NotAuthorizedException(MISSING_CREDENTIALS_MESSAGE);
		}
		String pathName = "resultService/resultStatus/"+queryId;
		HttpResponse response = retrieveGetResponse(composeURL(targetURL, pathName), createAuthorizationHeader(token));
		if (response.getStatusLine().getStatusCode() != 200) {
			logger.error(targetURL + " did not return a 200: {} {}", response.getStatusLine().getStatusCode(), response.getStatusLine().getReasonPhrase());
			throwResponseError(response, targetURL);
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

			logger.debug("IRCT_RS queryStatus() returns response: " + responseNode.toString());
			String resourceStatus = responseNode.get("status").asText();
			status.setResourceStatus(resourceStatus);
			status.setStatus(mapStatus(resourceStatus));
			status.setResourceResultId(responseNode.get("resultId").asText());
		} catch (IOException e){
			//TODO: Deal with this
			throw new ApplicationException(e);
		}
		return status;
	}

	@POST
	@Path("/query/{resourceQueryId}/result")
	@Override
	public Response queryResult(@PathParam("resourceQueryId") String queryId, QueryRequest resultRequest) {
		logger.debug("calling IRCT Resource queryResult() for query {}", queryId);
		if (targetURL == null || targetURL.isEmpty()){
			throw new ApplicationException(ApplicationException.MISSING_TARGET_URL);
		}
		if (resultRequest == null){
			throw new ProtocolException(ProtocolException.MISSING_DATA);
		}
		Map<String, String> resourceCredentials = resultRequest.getResourceCredentials();
		if (resourceCredentials == null) {
			throw new NotAuthorizedException(MISSING_CREDENTIALS_MESSAGE);
		}
		String token = resourceCredentials.get(IRCT_BEARER_TOKEN_KEY);
		if (token == null) {
			throw new NotAuthorizedException(MISSING_CREDENTIALS_MESSAGE);
		}
		String pathName = "resultService/result/"+queryId+"/"+RESULT_FORMAT;
		//Returns a String in the format requested
		HttpResponse response = retrieveGetResponse(composeURL(targetURL, pathName), createAuthorizationHeader(token));
		if (response.getStatusLine().getStatusCode() != 200) {
			logger.error(targetURL + " did not return a 200: {} {}", response.getStatusLine().getStatusCode(), response.getStatusLine().getReasonPhrase());
			throwResponseError(response, targetURL);
		}
		try {
			return Response.ok(response.getEntity().getContent()).build();
		} catch (IOException e){
			//TODO: Deal with this
			throw new ApplicationException(e);
		}
	}

	@POST
	@Path("/query/sync")
	@Override
	public Response querySync(QueryRequest resultRequest) {
		logger.debug("calling IRCT Resource querySync()");
		throw new UnsupportedOperationException("Query Sync is not implemented in this resource.  Please use query");
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

	private static Header[] createAuthorizationHeader(String token){
		Header authorizationHeader = new BasicHeader(HttpHeaders.AUTHORIZATION, ResourceWebClient.BEARER_STRING + token);
		Header[] headers = {authorizationHeader};
		return headers;
	}

}
