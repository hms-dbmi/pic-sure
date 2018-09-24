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
import org.apache.http.message.BasicHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.util.Map;

import static edu.harvard.dbmi.avillach.service.HttpClientUtil.*;


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
            if (queryRequest.getTargetURL() == null){
                throw new ApplicationException(ApplicationException.MISSING_TARGET_URL);
            }
            if (rsURL == null){
                throw new ApplicationException(ApplicationException.MISSING_RESOURCE_PATH);
            }
            logger.debug("Calling /info at ResourceURL: {}", rsURL);
            String pathName = "/info";
            String body = json.writeValueAsString(queryRequest);
            HttpResponse resourcesResponse = retrievePostResponse(composeURL(rsURL, pathName), createAuthorizationHeader(queryRequest.getResourceCredentials()), body);
            if (resourcesResponse.getStatusLine().getStatusCode() != 200) {
                logger.error("ResourceRS did not return a 200");
                throwResponseError(resourcesResponse, rsURL);
            }
            return readObjectFromResponse(resourcesResponse, ResourceInfo.class);
        } catch (JsonProcessingException e){
            throw new NotAuthorizedException("Unable to encode resource credentials", e);
        }
    }

    public SearchResults search(String rsURL, QueryRequest searchQueryRequest){
        logger.debug("Calling ResourceWebClient search()");
        try {
            if (searchQueryRequest == null || searchQueryRequest.getQuery() == null){
                throw new ProtocolException(ProtocolException.MISSING_DATA);
            }
            if (searchQueryRequest.getTargetURL() == null){
                throw new ApplicationException(ApplicationException.MISSING_TARGET_URL);
            }
            if (rsURL == null){
                throw new ApplicationException(ApplicationException.MISSING_RESOURCE_PATH);
            }

            if (searchQueryRequest.getResourceCredentials() == null){
                throw new NotAuthorizedException(NotAuthorizedException.MISSING_CREDENTIALS);
            }
            String pathName = "/search";
            String body = json.writeValueAsString(searchQueryRequest);

            HttpResponse resourcesResponse = retrievePostResponse(composeURL(rsURL, pathName), createAuthorizationHeader(searchQueryRequest.getResourceCredentials()), body);
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
            if (dataQueryRequest.getTargetURL() == null){
                throw new ApplicationException(ApplicationException.MISSING_TARGET_URL);
            }
            if (dataQueryRequest.getResourceCredentials() == null){
                throw new NotAuthorizedException("Missing credentials");
            }
            String pathName = "/query";
            String body = json.writeValueAsString(dataQueryRequest);
            HttpResponse resourcesResponse = retrievePostResponse(composeURL(rsURL, pathName), createAuthorizationHeader(dataQueryRequest.getResourceCredentials()), body);
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
            if (queryRequest.getTargetURL() == null){
                throw new ApplicationException(ApplicationException.MISSING_TARGET_URL);
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
            HttpResponse resourcesResponse = retrievePostResponse(composeURL(rsURL, pathName), createAuthorizationHeader(queryRequest.getResourceCredentials()), body);
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
            if (queryRequest.getTargetURL() == null){
                throw new ApplicationException(ApplicationException.MISSING_TARGET_URL);
            }
            if (rsURL == null){
                throw new ApplicationException(ApplicationException.MISSING_RESOURCE_PATH);
            }
            if (queryId == null){
                throw new ProtocolException(ProtocolException.MISSING_QUERY_ID);
            }
            String pathName = "/query/" + queryId + "/result";
            String body = json.writeValueAsString(queryRequest);
            HttpResponse resourcesResponse = retrievePostResponse(composeURL(rsURL, pathName), createAuthorizationHeader(queryRequest.getResourceCredentials()), body);
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

    public Response querySync(String rsURL, QueryRequest queryRequest){
        logger.debug("Calling ResourceWebClient querySync()");
        try {
            if (queryRequest == null){
                throw new ProtocolException("Missing query data");
            }
            if (queryRequest.getResourceCredentials() == null){
                throw new NotAuthorizedException("Missing credentials");
            }
            if (queryRequest.getTargetURL() == null){
                throw new ApplicationException("Missing target URL");
            }
            if (rsURL == null){
                throw new ApplicationException("Missing resource URL");
            }

            String pathName = "/query/sync";
            String body = json.writeValueAsString(queryRequest);
            HttpResponse resourcesResponse = retrievePostResponse(composeURL(rsURL, pathName), createAuthorizationHeader(queryRequest.getResourceCredentials()), body);
            if (resourcesResponse.getStatusLine().getStatusCode() != 200) {
                throwError(resourcesResponse, rsURL);
            }
            return Response.ok(resourcesResponse.getEntity().getContent()).build();
        } catch (JsonProcessingException e){
            logger.error("Unable to encode resource credentials");
            throw new NotAuthorizedException("Unable to encode resource credentials", e);
        } catch (IOException e){
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

    private Header[] createAuthorizationHeader(Map<String, String> resourceCredentials){
        Header authorizationHeader = new BasicHeader(HttpHeaders.AUTHORIZATION, BEARER_STRING + resourceCredentials.get(BEARER_TOKEN_KEY));
        Header[] headers = {authorizationHeader};
        return headers;
    }

}
