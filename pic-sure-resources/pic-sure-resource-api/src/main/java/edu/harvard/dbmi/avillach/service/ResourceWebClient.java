package edu.harvard.dbmi.avillach.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.domain.*;
import edu.harvard.dbmi.avillach.util.exception.ApplicationException;
import edu.harvard.dbmi.avillach.util.exception.ProtocolException;
import edu.harvard.dbmi.avillach.util.exception.ResourceInterfaceException;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.message.BasicHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.ws.rs.NotAuthorizedException;
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

    public ResourceInfo info(String baseURL, Map<String, String> resourceCredentials){
        logger.debug("Calling ResourceWebClient info()");
        try {
            if (resourceCredentials == null){
                throw new NotAuthorizedException("Missing credentials");
            }
            if (baseURL == null){
                throw new ApplicationException("Missing resource URL");
            }
            logger.debug("Calling /info at ResourceURL: {}", baseURL);
            String pathName = "/info";
            String body = json.writeValueAsString(resourceCredentials);
            HttpResponse resourcesResponse = retrievePostResponse(baseURL + pathName, createAuthorizationHeader(resourceCredentials), body);
            if (resourcesResponse.getStatusLine().getStatusCode() != 200) {
                throwError(resourcesResponse, baseURL);
            }
            return readObjectFromResponse(resourcesResponse, ResourceInfo.class);
        } catch (JsonProcessingException e){
            throw new NotAuthorizedException("Unable to encode resource credentials", e);
        }
    }

    public SearchResults search(String baseURL, QueryRequest searchQueryRequest){
        logger.debug("Calling ResourceWebClient search()");
        try {
            if (baseURL == null){
                throw new NotAuthorizedException("Missing resource URL");
            }
            if (searchQueryRequest == null || searchQueryRequest.getQuery() == null){
                throw new ProtocolException("Missing query request info");
            }
            if (searchQueryRequest.getResourceCredentials() == null){
                throw new NotAuthorizedException("Missing credentials");
            }
            String pathName = "/search";
            String body = json.writeValueAsString(searchQueryRequest);

            HttpResponse resourcesResponse = retrievePostResponse(baseURL + pathName, createAuthorizationHeader(searchQueryRequest.getResourceCredentials()), body);
            if (resourcesResponse.getStatusLine().getStatusCode() != 200) {
                throwError(resourcesResponse, baseURL);
            }
            return readObjectFromResponse(resourcesResponse, SearchResults.class);
        } catch (JsonProcessingException e){
            logger.error("Unable to serialize search query");
            //TODO Write custom exception
            throw new ProtocolException("Unable to serialize search query", e);
        }
    }

    public QueryStatus query(String baseURL, QueryRequest dataQueryRequest){
        logger.debug("Calling ResourceWebClient query()");
        try {
            if (baseURL == null){
                throw new ApplicationException("Missing resource URL");
            }
            if (dataQueryRequest == null){
                throw new ProtocolException("Missing query request info");
            }
            if (dataQueryRequest.getResourceCredentials() == null){
                throw new NotAuthorizedException("Missing credentials");
            }
            String pathName = "/query";
            String body = json.writeValueAsString(dataQueryRequest);
            HttpResponse resourcesResponse = retrievePostResponse(baseURL + pathName, createAuthorizationHeader(dataQueryRequest.getResourceCredentials()), body);
            if (resourcesResponse.getStatusLine().getStatusCode() != 200) {
                throwError(resourcesResponse, baseURL);
            }
            return readObjectFromResponse(resourcesResponse, QueryStatus.class);
        } catch (JsonProcessingException e){
            logger.error("Unable to encode data query");
            throw new ProtocolException("Unable to encode data query", e);
        }
    }

    public QueryStatus queryStatus(String baseURL, String queryId, Map<String, String> resourceCredentials){
        logger.debug("Calling ResourceWebClient query()");
        try {
            if (baseURL == null){
                throw new ApplicationException("Missing resource URL");
            }
            if (resourceCredentials == null){
                throw new NotAuthorizedException("Missing credentials");
            }
            if (queryId == null){
                throw new ProtocolException("Missing query id");
            }
            String pathName = "/query/" + queryId + "/status";
            String body = json.writeValueAsString(resourceCredentials);
            HttpResponse resourcesResponse = retrievePostResponse(baseURL + pathName, createAuthorizationHeader(resourceCredentials), body);
            if (resourcesResponse.getStatusLine().getStatusCode() != 200) {
                throwError(resourcesResponse, baseURL);
            }
            return readObjectFromResponse(resourcesResponse, QueryStatus.class);
        } catch (JsonProcessingException e){
            logger.error("Unable to encode resource credentials");
            throw new ProtocolException("Unable to encode resource credentials", e);
        }
    }

    public Response queryResult(String baseURL, String queryId, Map<String, String> resourceCredentials){
        logger.debug("Calling ResourceWebClient query()");
        try {
            if (baseURL == null){
                throw new ApplicationException("Missing resource URL");
            }
            if (resourceCredentials == null){
                throw new NotAuthorizedException("Missing credentials");
            }
            if (queryId == null){
                throw new ApplicationException("Missing query id");
            }
            String pathName = "/query/" + queryId + "/result";
            String body = json.writeValueAsString(resourceCredentials);
            HttpResponse resourcesResponse = retrievePostResponse(baseURL + pathName, createAuthorizationHeader(resourceCredentials), body);
            if (resourcesResponse.getStatusLine().getStatusCode() != 200) {
                throwError(resourcesResponse, baseURL);
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
        logger.error("Resource did not return a 200");
        if (response.getStatusLine().getStatusCode() == 401) {
            throw new NotAuthorizedException(baseURL + " " + response.getStatusLine().getStatusCode() + " " + response.getStatusLine().getReasonPhrase());
        }
        throw new ResourceInterfaceException("Resource returned " + response.getStatusLine().getStatusCode() + ": " + response.getStatusLine().getReasonPhrase());
    }

    private Header[] createAuthorizationHeader(Map<String, String> resourceCredentials){
        Header authorizationHeader = new BasicHeader(HttpHeaders.AUTHORIZATION, BEARER_STRING + resourceCredentials.get(BEARER_TOKEN_KEY));
        Header[] headers = {authorizationHeader};
        return headers;
    }

}

