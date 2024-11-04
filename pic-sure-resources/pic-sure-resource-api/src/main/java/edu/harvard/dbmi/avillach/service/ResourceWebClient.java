package edu.harvard.dbmi.avillach.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.domain.*;
import edu.harvard.dbmi.avillach.util.HttpClientUtil;
import edu.harvard.dbmi.avillach.util.exception.ApplicationException;
import edu.harvard.dbmi.avillach.util.exception.ProtocolException;
import edu.harvard.dbmi.avillach.util.exception.ResourceInterfaceException;
import edu.harvard.dbmi.avillach.util.exception.NotAuthorizedException;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.message.BasicHeader;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Map;


/**
 * The ResourceWebClient class implements the client side logic for the endpoints specified in IResourceRS. <p> The PicsureInfoService,
 * PicsureQueryService and PicsureSearchService would then use this class to serve calls from their methods to each configured Resource
 * target url after looking up the target url from the ResourceRepository.
 */
@ApplicationScoped
public class ResourceWebClient {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());
    private final static ObjectMapper json = new ObjectMapper();
    public static final String BEARER_STRING = "Bearer ";
    public static final String BEARER_TOKEN_KEY = "BEARER_TOKEN";
    public static final String QUERY_METADATA_FIELD = "queryMetadata";

    private final HttpClientUtil httpClientUtil;

    public ResourceWebClient() {
        PoolingHttpClientConnectionManager connectionManager;

        connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(100); // Maximum total connections
        connectionManager.setDefaultMaxPerRoute(5); // Maximum connections per route

        this.httpClientUtil = HttpClientUtil.getInstance(connectionManager);
    }

    public ResourceInfo info(String rsURL, QueryRequest queryRequest) {
        logger.debug("Calling ResourceWebClient info()");
        HttpResponse resourcesResponse = null;
        try {
            if (queryRequest == null) {
                throw new ProtocolException(ProtocolException.MISSING_DATA);
            }
            if (queryRequest.getResourceCredentials() == null) {
                throw new NotAuthorizedException(NotAuthorizedException.MISSING_CREDENTIALS);
            }
            if (rsURL == null) {
                throw new ApplicationException(ApplicationException.MISSING_RESOURCE_PATH);
            }
            logger.debug("Calling /info at ResourceURL: {}", rsURL);
            String pathName = "/info";
            String body = json.writeValueAsString(queryRequest);
            resourcesResponse = httpClientUtil.retrievePostResponse(
                httpClientUtil.composeURL(rsURL, pathName), createHeaders(queryRequest.getResourceCredentials()), body
            );
            if (resourcesResponse.getStatusLine().getStatusCode() != 200) {
                logger.error("ResourceRS did not return a 200");
                httpClientUtil.throwResponseError(resourcesResponse, rsURL);
            }
            return httpClientUtil.readObjectFromResponse(resourcesResponse, ResourceInfo.class);
        } catch (JsonProcessingException e) {
            throw new NotAuthorizedException("Unable to encode resource credentials", e);
        } finally {
            closeHttpResponse(resourcesResponse);
        }
    }

    public PaginatedSearchResult<?> searchConceptValues(
        String rsURL, QueryRequest queryRequest, String conceptPath, String query, Integer page, Integer size
    ) {
        HttpResponse resourcesResponse = null;
        try {
            logger.debug("Calling /search/values at ResourceURL: {}");
            URIBuilder uriBuilder = new URIBuilder(rsURL);
            String pathName = "search/values/";
            uriBuilder.setPath(uriBuilder.getPath() + pathName);
            uriBuilder.addParameter("genomicConceptPath", conceptPath);
            uriBuilder.addParameter("query", query);
            if (page != null) {
                uriBuilder.addParameter("page", page.toString());
            }
            if (size != null) {
                uriBuilder.addParameter("size", size.toString());
            }
            Map<String, String> resourceCredentials = queryRequest != null ? queryRequest.getResourceCredentials() : Map.of();
            resourcesResponse = httpClientUtil.retrieveGetResponse(uriBuilder.build().toString(), createHeaders(resourceCredentials));
            if (resourcesResponse.getStatusLine().getStatusCode() != 200) {
                logger.error("ResourceRS did not return a 200");
                httpClientUtil.throwResponseError(resourcesResponse, rsURL);
            }
            return httpClientUtil.readObjectFromResponse(resourcesResponse, PaginatedSearchResult.class);
        } catch (URISyntaxException e) {
            throw new ApplicationException("rsURL invalid : " + rsURL, e);
        } finally {
            closeHttpResponse(resourcesResponse);
        }
    }

    public SearchResults search(String rsURL, QueryRequest searchQueryRequest) {
        logger.debug("Calling ResourceWebClient search()");
        HttpResponse resourcesResponse = null;
        try {
            if (searchQueryRequest == null || searchQueryRequest.getQuery() == null) {
                throw new ProtocolException(ProtocolException.MISSING_DATA);
            }
            if (rsURL == null) {
                throw new ApplicationException(ApplicationException.MISSING_RESOURCE_PATH);
            }

            if (searchQueryRequest.getResourceCredentials() == null) {
                throw new NotAuthorizedException(NotAuthorizedException.MISSING_CREDENTIALS);
            }
            String pathName = "/search";
            String body = json.writeValueAsString(searchQueryRequest);

            resourcesResponse = httpClientUtil.retrievePostResponse(
                httpClientUtil.composeURL(rsURL, pathName), createHeaders(searchQueryRequest.getResourceCredentials()), body
            );
            if (resourcesResponse.getStatusLine().getStatusCode() != 200) {
                logger.error("ResourceRS did not return a 200");
                httpClientUtil.throwResponseError(resourcesResponse, rsURL);
            }
            return httpClientUtil.readObjectFromResponse(resourcesResponse, SearchResults.class);
        } catch (JsonProcessingException e) {
            logger.error("Unable to serialize search query");
            // TODO Write custom exception
            throw new ProtocolException("Unable to serialize search query", e);
        } finally {
            closeHttpResponse(resourcesResponse);
        }
    }

    public QueryStatus query(String rsURL, QueryRequest dataQueryRequest) {
        logger.debug("Calling ResourceWebClient query()");
        HttpResponse resourcesResponse = null;
        try {
            if (rsURL == null) {
                throw new ApplicationException(ApplicationException.MISSING_RESOURCE_PATH);
            }
            if (dataQueryRequest == null) {
                throw new ProtocolException(ProtocolException.MISSING_DATA);
            }
            if (dataQueryRequest.getResourceCredentials() == null) {
                throw new NotAuthorizedException("Missing credentials");
            }
            String pathName = "/query";
            String body = json.writeValueAsString(dataQueryRequest);
            resourcesResponse = httpClientUtil.retrievePostResponse(
                httpClientUtil.composeURL(rsURL, pathName), createHeaders(dataQueryRequest.getResourceCredentials()), body
            );
            if (resourcesResponse.getStatusLine().getStatusCode() != 200) {
                logger.error("ResourceRS did not return a 200");
                httpClientUtil.throwResponseError(resourcesResponse, rsURL);
            }
            return httpClientUtil.readObjectFromResponse(resourcesResponse, QueryStatus.class);
        } catch (JsonProcessingException e) {
            logger.error("Unable to encode data query");
            throw new ProtocolException("Unable to encode data query", e);
        } finally {
            closeHttpResponse(resourcesResponse);
        }
    }

    public QueryStatus queryStatus(String rsURL, String queryId, QueryRequest queryRequest) {
        logger.debug("Calling ResourceWebClient query()");
        HttpResponse resourcesResponse = null;
        try {
            if (queryRequest == null) {
                throw new ProtocolException(ProtocolException.MISSING_DATA);
            }
            if (queryRequest.getResourceCredentials() == null) {
                throw new NotAuthorizedException("Missing credentials");
            }
            if (rsURL == null) {
                throw new ApplicationException(ApplicationException.MISSING_RESOURCE_PATH);
            }
            if (queryId == null) {
                throw new ProtocolException("Missing query id");
            }
            String pathName = "/query/" + queryId + "/status";
            String body = json.writeValueAsString(queryRequest);
            logger.debug(httpClientUtil.composeURL(rsURL, pathName));
            logger.debug(body);
            resourcesResponse = httpClientUtil.retrievePostResponse(
                httpClientUtil.composeURL(rsURL, pathName), createHeaders(queryRequest.getResourceCredentials()), body
            );
            if (resourcesResponse.getStatusLine().getStatusCode() != 200) {
                logger.error("ResourceRS did not return a 200");
                httpClientUtil.throwResponseError(resourcesResponse, rsURL);
            }
            return httpClientUtil.readObjectFromResponse(resourcesResponse, QueryStatus.class);
        } catch (JsonProcessingException e) {
            logger.error("Unable to encode resource credentials");
            throw new ProtocolException("Unable to encode resource credentials", e);
        } finally {
            closeHttpResponse(resourcesResponse);
        }
    }

    private void closeHttpResponse(HttpResponse resourcesResponse) {
        if (resourcesResponse != null) {
            try {
                EntityUtils.consume(resourcesResponse.getEntity());
            } catch (IOException e) {
                logger.error("Failed to close HttpResponse entity", e);
            }
        }
    }

    public Response queryResult(String rsURL, String queryId, QueryRequest queryRequest) {
        logger.debug("Calling ResourceWebClient query()");

        HttpResponse resourcesResponse = null;
        try {
            if (queryRequest == null) {
                throw new ProtocolException(ProtocolException.MISSING_DATA);
            }
            if (queryRequest.getResourceCredentials() == null) {
                throw new NotAuthorizedException("Missing credentials");
            }
            if (rsURL == null) {
                throw new ApplicationException(ApplicationException.MISSING_RESOURCE_PATH);
            }
            if (queryId == null) {
                throw new ProtocolException(ProtocolException.MISSING_QUERY_ID);
            }
            String pathName = "/query/" + queryId + "/result";
            String body = json.writeValueAsString(queryRequest);
            resourcesResponse = httpClientUtil.retrievePostResponse(
                HttpClientUtil.composeURL(rsURL, pathName), createHeaders(queryRequest.getResourceCredentials()), body
            );

            String content = httpClientUtil.readObjectFromResponse(resourcesResponse);
            if (resourcesResponse.getStatusLine().getStatusCode() != 200) {
                logger.error("ResourceRS did not return a 200");
                HttpClientUtil.throwResponseError(resourcesResponse, rsURL);
            }
            return Response.ok(content).build();
        } catch (JsonProcessingException e) {
            logger.error("Unable to encode resource credentials");
            throw new NotAuthorizedException("Unable to encode resource credentials", e);
        } finally {
            closeHttpResponse(resourcesResponse);
        }
    }

    public Response queryResultSignedUrl(String rsURL, String queryId, QueryRequest queryRequest) {
        logger.debug("Calling ResourceWebClient querySignedUrl()");
        HttpResponse resourcesResponse = null;
        try {
            if (queryRequest == null) {
                throw new ProtocolException(ProtocolException.MISSING_DATA);
            }
            if (queryRequest.getResourceCredentials() == null) {
                throw new NotAuthorizedException("Missing credentials");
            }
            if (rsURL == null) {
                throw new ApplicationException(ApplicationException.MISSING_RESOURCE_PATH);
            }
            if (queryId == null) {
                throw new ProtocolException(ProtocolException.MISSING_QUERY_ID);
            }
            String pathName = "/query/" + queryId + "/signed-url";
            String body = json.writeValueAsString(queryRequest);
            resourcesResponse = httpClientUtil.retrievePostResponse(
                httpClientUtil.composeURL(rsURL, pathName), createHeaders(queryRequest.getResourceCredentials()), body
            );

            if (resourcesResponse.getStatusLine().getStatusCode() != 200) {
                logger.error("ResourceRS did not return a 200");
                httpClientUtil.throwResponseError(resourcesResponse, rsURL);
            }

            String content = httpClientUtil.readObjectFromResponse(resourcesResponse);
            return Response.ok(content).build();
        } catch (JsonProcessingException e) {
            logger.error("Unable to encode resource credentials");
            throw new NotAuthorizedException("Unable to encode resource credentials", e);
        } finally {
            closeHttpResponse(resourcesResponse);
        }
    }


    public Response queryFormat(String rsURL, QueryRequest queryRequest) {
        logger.debug("Calling ResourceWebClient queryFormat()");
        HttpResponse resourcesResponse = null;
        try {
            if (queryRequest == null) {
                throw new ProtocolException(ProtocolException.MISSING_DATA);
            }
            if (queryRequest.getResourceCredentials() == null) {
                throw new NotAuthorizedException("Missing credentials");
            }
            if (rsURL == null) {
                throw new ApplicationException(ApplicationException.MISSING_RESOURCE_PATH);
            }
            String pathName = "/query/format";
            String body = json.writeValueAsString(queryRequest);
            resourcesResponse = httpClientUtil.retrievePostResponse(
                httpClientUtil.composeURL(rsURL, pathName), createHeaders(queryRequest.getResourceCredentials()), body
            );

            String content = httpClientUtil.readObjectFromResponse(resourcesResponse);
            int status = resourcesResponse.getStatusLine().getStatusCode();
            if (status != 200) {
                logger.error("Query format request did not return a 200:  {}", resourcesResponse.getStatusLine().getStatusCode());
                return Response.status(status).entity(content).build();
            }
            return Response.ok(content).build();
        } catch (JsonProcessingException e) {
            logger.error("Unable to encode resource credentials");
            throw new NotAuthorizedException("Unable to encode resource credentials", e);
        } finally {
            closeHttpResponse(resourcesResponse);
        }
    }

    public Response querySync(String rsURL, QueryRequest queryRequest, String requestSource) {
        logger.debug("Calling ResourceWebClient querySync()");
        HttpResponse resourcesResponse = null;
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

            resourcesResponse = httpClientUtil.retrievePostResponse(httpClientUtil.composeURL(rsURL, pathName), headers, body);
            if (resourcesResponse.getStatusLine().getStatusCode() != 200) {
                throwError(resourcesResponse, rsURL);
            }

            String content = httpClientUtil.readObjectFromResponse(resourcesResponse);
            if (resourcesResponse.containsHeader(QUERY_METADATA_FIELD)) {
                Header metadataHeader = ((Header[]) resourcesResponse.getHeaders(QUERY_METADATA_FIELD))[0];
                return Response.ok(content).header(QUERY_METADATA_FIELD, metadataHeader.getValue()).build();
            }
            return Response.ok(content).build();
        } catch (JsonProcessingException e) {
            logger.error("Unable to encode resource credentials");
            throw new NotAuthorizedException("Unable to encode resource credentials", e);
        } finally {
            closeHttpResponse(resourcesResponse);
        }
    }

    /**
     * This method is used to call the /bin/continuous endpoint on the ResourceRS. The /bin/continuous endpoint is used to retrieve binned
     * continuous data from the visualization resource.
     *
     * @param rsURL The URL of the ResourceRS
     * @param queryRequest The query request object
     * @param requestSource The request source
     * @return The response from the ResourceRS
     */
    public Response queryContinuous(String rsURL, QueryRequest queryRequest, String requestSource) {
        logger.debug("Calling ResourceWebClient queryContinuous()");
        HttpResponse resourcesResponse = null;
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
            resourcesResponse = httpClientUtil.retrievePostResponse(HttpClientUtil.composeURL(rsURL, pathName), headers, body);
            if (resourcesResponse.getStatusLine().getStatusCode() != 200) {
                throwError(resourcesResponse, rsURL);
            }

            String content = httpClientUtil.readObjectFromResponse(resourcesResponse);
            return Response.ok(content).build();
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        } finally {
            closeHttpResponse(resourcesResponse);
        }
    }

    private void throwError(HttpResponse response, String baseURL) {
        logger.error("ResourceRS did not return a 200");
        String errorMessage = baseURL + " " + response.getStatusLine().getStatusCode() + " " + response.getStatusLine().getReasonPhrase();
        try {
            JsonNode responseNode = json.readTree(response.getEntity().getContent());
            if (responseNode != null && responseNode.has("message")) {
                errorMessage += "/n" + responseNode.get("message").asText();
            }
        } catch (IOException e) {
            logger.error(e.getMessage());
        }
        if (response.getStatusLine().getStatusCode() == 401) {
            throw new NotAuthorizedException(errorMessage);
        }
        throw new ResourceInterfaceException(errorMessage);

    }

    private Header[] createHeaders(Map<String, String> resourceCredentials) {
        Header authorizationHeader = new BasicHeader(HttpHeaders.AUTHORIZATION, BEARER_STRING + resourceCredentials.get(BEARER_TOKEN_KEY));
        Header contentTypeHeader = new BasicHeader(HttpHeaders.CONTENT_TYPE, "application/json");
        Header[] headers = {authorizationHeader, contentTypeHeader};
        return headers;
    }

}
