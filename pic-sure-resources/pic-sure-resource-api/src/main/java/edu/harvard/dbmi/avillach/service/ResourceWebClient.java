package edu.harvard.dbmi.avillach.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.domain.*;
import edu.harvard.dbmi.avillach.util.exception.ApplicationException;
import edu.harvard.dbmi.avillach.util.exception.ProtocolException;
import edu.harvard.dbmi.avillach.util.exception.ResourceInterfaceException;
import edu.harvard.dbmi.avillach.util.exception.NotAuthorizedException;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.message.BasicHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Map;

import static edu.harvard.dbmi.avillach.util.HttpClientUtil.*;


/**
 * The ResourceWebClient class implements the client side logic for the endpoints specified in IResourceRS.

 The PicsureInfoService, PicsureQueryService and PicsureSearchService would then use this class
 to serve calls from their methods to each configured Resource target url
 after looking up the target url from the ResourceRepository.
 */
@ApplicationScoped
public class ResourceWebClient {

    private Logger logger = LoggerFactory.getLogger(this.getClass());
    private final static ObjectMapper json = new ObjectMapper();
    public static final String BEARER_STRING = "Bearer ";
    public static final String BEARER_TOKEN_KEY = "BEARER_TOKEN";
    public static final String QUERY_METADATA_FIELD = "queryMetadata";
    
    public ResourceWebClient() { }

    public ResourceInfo info(String rsURL, QueryRequest queryRequest){
        logger.debug("Calling ResourceWebClient info()");
        try {
            if (queryRequest == null){
                throw new ProtocolException(ProtocolException.MISSING_DATA);
            }
            if (queryRequest.getResourceCredentials() == null){
                throw new NotAuthorizedException(NotAuthorizedException.MISSING_CREDENTIALS);
            }
            if (rsURL == null){
                throw new ApplicationException(ApplicationException.MISSING_RESOURCE_PATH);
            }
            logger.debug("Calling /info at ResourceURL: {}", rsURL);
            String pathName = "/info";
            String body = json.writeValueAsString(queryRequest);
            HttpResponse resourcesResponse = retrievePostResponse(composeURL(rsURL, pathName), createHeaders(queryRequest.getResourceCredentials()), body);
            if (resourcesResponse.getStatusLine().getStatusCode() != 200) {
                logger.error("ResourceRS did not return a 200");
                throwResponseError(resourcesResponse, rsURL);
            }
            return readObjectFromResponse(resourcesResponse, ResourceInfo.class);
        } catch (JsonProcessingException e){
            throw new NotAuthorizedException("Unable to encode resource credentials", e);
        }
    }

    public PaginatedSearchResult<?> searchConceptValues(String rsURL, QueryRequest queryRequest, String conceptPath, String query, Integer page, Integer size) {
        try {
            logger.info("Calling /search/values at ResourceURL: {}");
            logger.info("conceptPath: " + conceptPath);
            logger.info("query: " + query);
            logger.info("page: " + page);
            logger.info("size: " + size);
            String pathName = "/search/values/";
            URIBuilder uriBuilder = new URIBuilder(rsURL);
            uriBuilder.setPath(pathName);
            uriBuilder.addParameter("conceptPath", conceptPath);
            uriBuilder.addParameter("query", query);
            if (page != null) {
                uriBuilder.addParameter("page", page.toString());
            }
            if (size != null) {
                uriBuilder.addParameter("size", size.toString());
            }
            HttpResponse resourcesResponse = retrievePostResponse(uriBuilder.build().toString(), createHeaders(queryRequest.getResourceCredentials()), "");
            if (resourcesResponse.getStatusLine().getStatusCode() != 200) {
                logger.error("ResourceRS did not return a 200");
                throwResponseError(resourcesResponse, rsURL);
            }
            return readObjectFromResponse(resourcesResponse, PaginatedSearchResult.class);
        } catch (URISyntaxException e) {
            throw new ApplicationException("rsURL invalid : " + rsURL, e);
        }
    }

    public SearchResults search(String rsURL, QueryRequest searchQueryRequest){
        logger.debug("Calling ResourceWebClient search()");
        try {
            if (searchQueryRequest == null || searchQueryRequest.getQuery() == null){
                throw new ProtocolException(ProtocolException.MISSING_DATA);
            }
            if (rsURL == null){
                throw new ApplicationException(ApplicationException.MISSING_RESOURCE_PATH);
            }

            if (searchQueryRequest.getResourceCredentials() == null){
                throw new NotAuthorizedException(NotAuthorizedException.MISSING_CREDENTIALS);
            }
            String pathName = "/search";
            String body = json.writeValueAsString(searchQueryRequest);

            HttpResponse resourcesResponse = retrievePostResponse(composeURL(rsURL, pathName), createHeaders(searchQueryRequest.getResourceCredentials()), body);
            if (resourcesResponse.getStatusLine().getStatusCode() != 200) {
                logger.error("ResourceRS did not return a 200");
                throwResponseError(resourcesResponse, rsURL);
            }
            return readObjectFromResponse(resourcesResponse, SearchResults.class);
        } catch (JsonProcessingException e){
            logger.error("Unable to serialize search query");
            //TODO Write custom exception
            throw new ProtocolException("Unable to serialize search query", e);
        }
    }

    public QueryStatus query(String rsURL, QueryRequest dataQueryRequest){
        logger.debug("Calling ResourceWebClient query()");
        try {
            if (rsURL == null){
                throw new ApplicationException(ApplicationException.MISSING_RESOURCE_PATH);
            }
            if (dataQueryRequest == null){
                throw new ProtocolException(ProtocolException.MISSING_DATA);
            }
            if (dataQueryRequest.getResourceCredentials() == null){
                throw new NotAuthorizedException("Missing credentials");
            }
            String pathName = "/query";
            String body = json.writeValueAsString(dataQueryRequest);
            HttpResponse resourcesResponse = retrievePostResponse(composeURL(rsURL, pathName), createHeaders(dataQueryRequest.getResourceCredentials()), body);
            if (resourcesResponse.getStatusLine().getStatusCode() != 200) {
                logger.error("ResourceRS did not return a 200");
                throwResponseError(resourcesResponse, rsURL);
            }
            return readObjectFromResponse(resourcesResponse, QueryStatus.class);
        } catch (JsonProcessingException e){
            logger.error("Unable to encode data query");
            throw new ProtocolException("Unable to encode data query", e);
        }
    }

    public QueryStatus queryStatus(String rsURL, String queryId, QueryRequest queryRequest){
        logger.debug("Calling ResourceWebClient query()");
        try {
            if (queryRequest == null){
                throw new ProtocolException(ProtocolException.MISSING_DATA);
            }
            if (queryRequest.getResourceCredentials() == null){
                throw new NotAuthorizedException("Missing credentials");
            }
            if (rsURL == null){
                throw new ApplicationException(ApplicationException.MISSING_RESOURCE_PATH);
            }
            if (queryId == null){
                throw new ProtocolException("Missing query id");
            }
            String pathName = "/query/" + queryId + "/status";
            String body = json.writeValueAsString(queryRequest);
            logger.debug(composeURL(rsURL, pathName));
            logger.debug(body);
            HttpResponse resourcesResponse = retrievePostResponse(composeURL(rsURL, pathName), createHeaders(queryRequest.getResourceCredentials()), body);
            if (resourcesResponse.getStatusLine().getStatusCode() != 200) {
                logger.error("ResourceRS did not return a 200");
                throwResponseError(resourcesResponse, rsURL);
            }
            return readObjectFromResponse(resourcesResponse, QueryStatus.class);
        } catch (JsonProcessingException e){
            logger.error("Unable to encode resource credentials");
            throw new ProtocolException("Unable to encode resource credentials", e);
        }
    }

    public Response queryResult(String rsURL, String queryId, QueryRequest queryRequest){
        logger.debug("Calling ResourceWebClient query()");
        try {
            if (queryRequest == null){
                throw new ProtocolException(ProtocolException.MISSING_DATA);
            }
            if (queryRequest.getResourceCredentials() == null){
                throw new NotAuthorizedException("Missing credentials");
            }
            if (rsURL == null){
                throw new ApplicationException(ApplicationException.MISSING_RESOURCE_PATH);
            }
            if (queryId == null){
                throw new ProtocolException(ProtocolException.MISSING_QUERY_ID);
            }
            String pathName = "/query/" + queryId + "/result";
            String body = json.writeValueAsString(queryRequest);
            HttpResponse resourcesResponse = retrievePostResponse(composeURL(rsURL, pathName), createHeaders(queryRequest.getResourceCredentials()), body);
            if (resourcesResponse.getStatusLine().getStatusCode() != 200) {
                logger.error("ResourceRS did not return a 200");
                throwResponseError(resourcesResponse, rsURL);
            }
            return Response.ok(resourcesResponse.getEntity().getContent()).build();
        } catch (JsonProcessingException e){
            logger.error("Unable to encode resource credentials");
            throw new NotAuthorizedException("Unable to encode resource credentials", e);
        } catch (IOException e){
            throw new ResourceInterfaceException("Error getting results", e);
        }
    }
    

    public Response queryFormat(String rsURL, QueryRequest queryRequest){
        logger.debug("Calling ResourceWebClient queryFormat()");
        try {
            if (queryRequest == null){
                throw new ProtocolException(ProtocolException.MISSING_DATA);
            }
            if (queryRequest.getResourceCredentials() == null){
                throw new NotAuthorizedException("Missing credentials");
            }
            if (rsURL == null){
                throw new ApplicationException(ApplicationException.MISSING_RESOURCE_PATH);
            }
            String pathName = "/query/format";
            String body = json.writeValueAsString(queryRequest);
            HttpResponse resourcesResponse = retrievePostResponse(composeURL(rsURL, pathName), createHeaders(queryRequest.getResourceCredentials()), body);
            int status = resourcesResponse.getStatusLine().getStatusCode();
            if (status != 200) {
                logger.error("Query format request did not return a 200:  " + resourcesResponse.getStatusLine().getStatusCode());
                return Response.status(status).entity(resourcesResponse.getEntity().getContent()).build();
            }
            return Response.ok(resourcesResponse.getEntity().getContent()).build();
        } catch (JsonProcessingException e){
            logger.error("Unable to encode resource credentials");
            throw new NotAuthorizedException("Unable to encode resource credentials", e);
        } catch (IOException e){
            throw new ResourceInterfaceException("Error getting results", e);
        }
    }

	public Response querySync(String rsURL, QueryRequest queryRequest, String requestSource) {
		logger.debug("Calling ResourceWebClient querySync()");
		try {
			if (queryRequest == null) {
				throw new ProtocolException("Missing query data");
			}
			if (queryRequest.getResourceCredentials() == null) {
				throw new NotAuthorizedException("Missing credentials");
			}
			if (rsURL == null) {
				throw new ApplicationException("Missing resource URL");
			}

			String pathName = "/query/sync";
			String body = json.writeValueAsString(queryRequest);


            Header[] headers = createHeaders(queryRequest.getResourceCredentials());
            if (requestSource != null) {
                Header sourceHeader = new BasicHeader("request-source", requestSource);

                // Add the source header to the headers array.
                Header[] newHeaders = new Header[headers.length + 1];
                System.arraycopy(headers, 0, newHeaders, 0, headers.length);
                newHeaders[headers.length] = sourceHeader;
                headers = newHeaders;
            }

            HttpResponse resourcesResponse = retrievePostResponse(composeURL(rsURL, pathName), headers, body);
			if (resourcesResponse.getStatusLine().getStatusCode() != 200) {
				throwError(resourcesResponse, rsURL);
			}

			if (resourcesResponse.containsHeader(QUERY_METADATA_FIELD)) {
				Header metadataHeader = ((Header[]) resourcesResponse.getHeaders(QUERY_METADATA_FIELD))[0];
				return Response.ok(resourcesResponse.getEntity().getContent())
						.header(QUERY_METADATA_FIELD, metadataHeader.getValue()).build();
			}
			return Response.ok(resourcesResponse.getEntity().getContent()).build();
		} catch (JsonProcessingException e) {
			logger.error("Unable to encode resource credentials");
			throw new NotAuthorizedException("Unable to encode resource credentials", e);
		} catch (IOException e) {
			throw new ResourceInterfaceException("Error getting results", e);
		}
	}

    /**
     * This method is used to call the /bin/continuous endpoint on the ResourceRS. The /bin/continuous endpoint is used
     * to retrieve binned continuous data from the visualization resource.
     *
     * @param rsURL The URL of the ResourceRS
     * @param queryRequest The query request object
     * @param requestSource The request source
     * @return The response from the ResourceRS
     */
    public Response queryContinuous(String rsURL, QueryRequest queryRequest, String requestSource) {
        logger.debug("Calling ResourceWebClient queryContinuous()");
        try {
            if (queryRequest == null) {
                throw new ProtocolException("Missing query data");
            }
            if (queryRequest.getResourceCredentials() == null) {
                throw new NotAuthorizedException("Missing credentials");
            }
            if (rsURL == null) {
                throw new ApplicationException("Missing resource URL");
            }

            String pathName = "/bin/continuous";
            String body = json.writeValueAsString(queryRequest);

            Header[] headers = createHeaders(queryRequest.getResourceCredentials());
            if (requestSource != null) {
                Header sourceHeader = new BasicHeader("request-source", requestSource);

                // Add the source header to the headers array.
                Header[] newHeaders = new Header[headers.length + 1];
                System.arraycopy(headers, 0, newHeaders, 0, headers.length);
                newHeaders[headers.length] = sourceHeader;
                headers = newHeaders;
            }

            logger.debug("Calling ResourceWebClient queryContinuous() with body: " + body + " and headers: " + queryRequest);
            HttpResponse resourcesResponse = retrievePostResponse(composeURL(rsURL, pathName), headers, body);
            if (resourcesResponse.getStatusLine().getStatusCode() != 200) {
                throwError(resourcesResponse, rsURL);
            }

        return Response.ok(resourcesResponse.getEntity().getContent()).build();
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new ResourceInterfaceException("Error getting results", e);
        }
    }

    private void throwError(HttpResponse response, String baseURL){
        logger.error("ResourceRS did not return a 200");
        String errorMessage = baseURL + " " + response.getStatusLine().getStatusCode() + " " + response.getStatusLine().getReasonPhrase();
        try {
            JsonNode responseNode = json.readTree(response.getEntity().getContent());
            if (responseNode != null && responseNode.has("message")){
                errorMessage += "/n" + responseNode.get("message").asText();
            }
        } catch (IOException e ){
        }
        if (response.getStatusLine().getStatusCode() == 401) {
            throw new NotAuthorizedException(errorMessage);
        }
        throw new ResourceInterfaceException(errorMessage);

    }

    private Header[] createHeaders(Map<String, String> resourceCredentials){
        Header authorizationHeader = new BasicHeader(HttpHeaders.AUTHORIZATION, BEARER_STRING + resourceCredentials.get(BEARER_TOKEN_KEY));
        Header contentTypeHeader = new BasicHeader(HttpHeaders.CONTENT_TYPE, "application/json");
        Header[] headers = {authorizationHeader, contentTypeHeader};
        return headers;
    }

}
