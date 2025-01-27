package edu.harvard.hms.dbmi.avillach.resource.passthru;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;

import edu.harvard.dbmi.avillach.util.HttpClientUtil;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.message.BasicHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.dbmi.avillach.domain.*;
import edu.harvard.dbmi.avillach.service.IResourceRS;
import edu.harvard.dbmi.avillach.util.exception.ApplicationException;
import edu.harvard.dbmi.avillach.util.exception.ProtocolException;
import static edu.harvard.dbmi.avillach.service.ResourceWebClient.QUERY_METADATA_FIELD;
import static edu.harvard.dbmi.avillach.util.HttpClientUtil.closeHttpResponse;

@Path("/passthru")
@Produces("application/json")
@Consumes("application/json")
public class PassThroughResourceRS implements IResourceRS {

    private static final String BEARER_STRING = "Bearer ";

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final Logger logger = LoggerFactory.getLogger(PassThroughResourceRS.class);

    @Inject
    private ApplicationProperties properties;

    private final HttpClientUtil httpClient;

    public PoolingHttpClientConnectionManager getConnectionManager() {
        PoolingHttpClientConnectionManager connectionManager;
        connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(100); // Maximum total connections
        connectionManager.setDefaultMaxPerRoute(20); // Maximum connections per route
        return connectionManager;
    }

    public PassThroughResourceRS() {
        this.httpClient = HttpClientUtil.getInstance(getConnectionManager());
    }

    @Inject
    public PassThroughResourceRS(ApplicationProperties applicationProperties) {
        this.properties = applicationProperties;
        this.httpClient = HttpClientUtil.getInstance(getConnectionManager());
    }

    public PassThroughResourceRS(ApplicationProperties properties, HttpClientUtil httpClient) {
        this.properties = properties;
        this.httpClient = httpClient;
    }

    @POST
    @Path("/info")
    public ResourceInfo info(QueryRequest infoRequest) {
        String pathName = "/info";

        HttpResponse response = null;
        try {
            QueryRequest chainRequest = new GeneralQueryRequest();
            if (infoRequest != null) {
                chainRequest.setQuery(infoRequest.getQuery());
                chainRequest.setResourceCredentials(infoRequest.getResourceCredentials());
            }
            chainRequest.setResourceUUID(UUID.fromString(properties.getTargetResourceId()));

            String payload = objectMapper.writeValueAsString(chainRequest);

            response = httpClient
                .retrievePostResponse(HttpClientUtil.composeURL(properties.getTargetPicsureUrl(), pathName), createAuthHeader(), payload);
            if (response.getStatusLine().getStatusCode() != 200) {
                logger.error(
                    "{}{} did not return a 200: {} {} ", properties.getTargetPicsureUrl(), pathName,
                    response.getStatusLine().getStatusCode(), response.getStatusLine().getReasonPhrase()
                );
                HttpClientUtil.throwInternalResponseError(response, properties.getTargetPicsureUrl());
            }

            ResourceInfo resourceInfo = HttpClientUtil.readObjectFromResponse(response, ResourceInfo.class);
            if (infoRequest != null && infoRequest.getResourceUUID() != null) {
                resourceInfo.setId(infoRequest.getResourceUUID());
            }
            return resourceInfo;
        } catch (IOException e) {
            throw new ApplicationException("Error encoding query for resource with id " + infoRequest.getResourceUUID());
        } catch (ClassCastException | IllegalArgumentException e) {
            logger.error(e.getMessage());
            throw new ProtocolException(ProtocolException.INCORRECTLY_FORMATTED_REQUEST);
        } finally {
            closeHttpResponse(response);
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
        HttpResponse response = null;
        try {
            QueryRequest chainRequest = queryRequest.copy();
            chainRequest.setResourceUUID(UUID.fromString(properties.getTargetResourceId()));

            String payload = objectMapper.writeValueAsString(chainRequest);
            response = httpClient
                .retrievePostResponse(httpClient.composeURL(properties.getTargetPicsureUrl(), pathName), createAuthHeader(), payload);
            if (response.getStatusLine().getStatusCode() != 200) {
                logger.error(
                    "{}{} calling resource with id {} did not return a 200: {} {} ", properties.getTargetPicsureUrl(), pathName,
                    chainRequest.getResourceUUID(), response.getStatusLine().getStatusCode(), response.getStatusLine().getReasonPhrase()
                );
                httpClient.throwInternalResponseError(response, properties.getTargetPicsureUrl());
            }
            QueryStatus queryStatus = httpClient.readObjectFromResponse(response, QueryStatus.class);
            queryStatus.setResourceID(queryRequest.getResourceUUID());
            return queryStatus;
        } catch (IOException e) {
            throw new ApplicationException("Error encoding query for resource with id " + queryRequest.getResourceUUID());
        } catch (ClassCastException | IllegalArgumentException e) {
            logger.error(e.getMessage());
            throw new ProtocolException(ProtocolException.INCORRECTLY_FORMATTED_REQUEST);
        } finally {
            closeHttpResponse(response);
        }
    }

    @POST
    @Path("/query/{resourceQueryId}/result")
    public Response queryResult(@PathParam("resourceQueryId") String queryId, QueryRequest resultRequest) {
        if (resultRequest == null) {
            throw new ProtocolException(ProtocolException.MISSING_DATA);
        }

        String pathName = "/query/" + queryId + "/result";

        HttpResponse response = null;
        try {
            QueryRequest chainRequest = new GeneralQueryRequest();
            chainRequest.setQuery(resultRequest.getQuery());
            chainRequest.setResourceCredentials(resultRequest.getResourceCredentials());
            chainRequest.setResourceUUID(UUID.fromString(properties.getTargetResourceId()));

            String payload = objectMapper.writeValueAsString(chainRequest);
            response = httpClient
                .retrievePostResponse(httpClient.composeURL(properties.getTargetPicsureUrl(), pathName), createAuthHeader(), payload);
            String content = httpClient.readObjectFromResponse(response, StandardCharsets.UTF_8);
            if (response.getStatusLine().getStatusCode() != 200) {
                logger.error(
                    "{}{} calling resource with id {} did not return a 200: {} {} ", properties.getTargetPicsureUrl(), pathName,
                    chainRequest.getResourceUUID(), response.getStatusLine().getStatusCode(), response.getStatusLine().getReasonPhrase()
                );
                httpClient.throwInternalResponseError(response, properties.getTargetPicsureUrl());
            }

            return Response.ok(content).build();
        } catch (IOException e) {
            throw new ApplicationException("Error encoding query for resource with id " + resultRequest.getResourceUUID());
        } catch (ClassCastException | IllegalArgumentException e) {
            logger.error(e.getMessage());
            throw new ProtocolException(ProtocolException.INCORRECTLY_FORMATTED_REQUEST);
        } finally {
            closeHttpResponse(response);
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
        HttpResponse response = null;
        try {
            QueryRequest chainRequest = new GeneralQueryRequest();
            chainRequest.setQuery(statusRequest.getQuery());
            chainRequest.setResourceCredentials(statusRequest.getResourceCredentials());
            chainRequest.setResourceUUID(UUID.fromString(properties.getTargetResourceId()));

            String payload = objectMapper.writeValueAsString(chainRequest);
            response = httpClient
                .retrievePostResponse(httpClient.composeURL(properties.getTargetPicsureUrl(), pathName), createAuthHeader(), payload);
            if (response.getStatusLine().getStatusCode() != 200) {
                logger.error(
                    "{}{} calling resource with id {} did not return a 200: {} {} ", properties.getTargetPicsureUrl(), pathName,
                    chainRequest.getResourceUUID(), response.getStatusLine().getStatusCode(), response.getStatusLine().getReasonPhrase()
                );
                httpClient.throwInternalResponseError(response, properties.getTargetPicsureUrl());
            }
            QueryStatus queryStatus = httpClient.readObjectFromResponse(response, QueryStatus.class);
            queryStatus.setResourceID(statusRequest.getResourceUUID());
            return queryStatus;
        } catch (IOException e) {
            throw new ApplicationException("Error encoding query for resource with id " + statusRequest.getResourceUUID());
        } catch (ClassCastException | IllegalArgumentException e) {
            logger.error(e.getMessage());
            throw new ProtocolException(ProtocolException.INCORRECTLY_FORMATTED_REQUEST);
        } finally {
            closeHttpResponse(response);
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

        HttpResponse response = null;
        try {
            QueryRequest chainRequest = new GeneralQueryRequest();
            chainRequest.setQuery(queryRequest.getQuery());
            chainRequest.setResourceCredentials(queryRequest.getResourceCredentials());
            chainRequest.setResourceUUID(UUID.fromString(properties.getTargetResourceId()));

            String payload = objectMapper.writeValueAsString(chainRequest);
            response = httpClient
                .retrievePostResponse(httpClient.composeURL(properties.getTargetPicsureUrl(), pathName), createAuthHeader(), payload);
            if (response.getStatusLine().getStatusCode() != 200) {
                logger.error(
                    "{}{} calling resource with id {} did not return a 200: {} {} ", properties.getTargetPicsureUrl(), pathName,
                    chainRequest.getResourceUUID(), response.getStatusLine().getStatusCode(), response.getStatusLine().getReasonPhrase()
                );
                httpClient.throwInternalResponseError(response, properties.getTargetPicsureUrl());
            }

            if (response.containsHeader(QUERY_METADATA_FIELD)) {
                Header metadataHeader = ((Header[]) response.getHeaders(QUERY_METADATA_FIELD))[0];
                logger.debug("Found Header[] : " + metadataHeader.getValue());
                return Response.ok(response.getEntity().getContent()).header(QUERY_METADATA_FIELD, metadataHeader.getValue()).build();
            }

            return Response.ok(response.getEntity().getContent()).build();
        } catch (IOException e) {
            throw new ApplicationException("Error encoding query for resource with id " + queryRequest.getResourceUUID());
        } catch (ClassCastException | IllegalArgumentException e) {
            logger.error(e.getMessage());
            throw new ProtocolException(ProtocolException.INCORRECTLY_FORMATTED_REQUEST);
        } finally {
            closeHttpResponse(response);
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

        HttpResponse response = null;
        String pathName = "/search/" + properties.getTargetResourceId();
        try {
            QueryRequest chainRequest = new GeneralQueryRequest();
            chainRequest.setQuery(searchRequest.getQuery());
            chainRequest.setResourceCredentials(searchRequest.getResourceCredentials());
            chainRequest.setResourceUUID(UUID.fromString(properties.getTargetResourceId()));

            String payload = objectMapper.writeValueAsString(chainRequest);
            response = httpClient
                .retrievePostResponse(httpClient.composeURL(properties.getTargetPicsureUrl(), pathName), createAuthHeader(), payload);
            if (response.getStatusLine().getStatusCode() != 200) {
                logger.error(
                    "{}{} did not return a 200: {} {} ", properties.getTargetPicsureUrl(), pathName,
                    response.getStatusLine().getStatusCode(), response.getStatusLine().getReasonPhrase()
                );
                httpClient.throwInternalResponseError(response, properties.getTargetPicsureUrl());
            }
            return httpClient.readObjectFromResponse(response, SearchResults.class);
        } catch (IOException e) {
            // Note: this shouldn't ever happen
            logger.error("Error encoding search payload", e);
            throw new ApplicationException("Error encoding search for resource with id " + searchRequest.getResourceUUID());
        } finally {
            closeHttpResponse(response);
        }
    }

    @Override
    public Response queryFormat(QueryRequest queryRequest) {
        if (queryRequest == null) {
            throw new ProtocolException(ProtocolException.MISSING_DATA);
        }
        Object search = queryRequest.getQuery();
        if (search == null) {
            throw new ProtocolException((ProtocolException.MISSING_DATA));
        }

        String pathName = "/query/format";
        HttpResponse response = null;
        try {
            QueryRequest chainRequest = new GeneralQueryRequest();
            chainRequest.setQuery(queryRequest.getQuery());
            chainRequest.setResourceCredentials(queryRequest.getResourceCredentials());
            chainRequest.setResourceUUID(UUID.fromString(properties.getTargetResourceId()));

            String payload = objectMapper.writeValueAsString(chainRequest);
            response = httpClient
                .retrievePostResponse(httpClient.composeURL(properties.getTargetPicsureUrl(), pathName), createAuthHeader(), payload);
            if (response.getStatusLine().getStatusCode() != 200) {
                logger.error(
                    "{}{} calling resource with id {} did not return a 200: {} {} ", properties.getTargetPicsureUrl(), pathName,
                    chainRequest.getResourceUUID(), response.getStatusLine().getStatusCode(), response.getStatusLine().getReasonPhrase()
                );
                httpClient.throwInternalResponseError(response, properties.getTargetPicsureUrl());
            }

            return Response.ok(response.getEntity().getContent()).build();
        } catch (IOException e) {
            throw new ApplicationException("Error encoding query for resource with id " + queryRequest.getResourceUUID());
        } catch (ClassCastException | IllegalArgumentException e) {
            logger.error(e.getMessage());
            throw new ProtocolException(ProtocolException.INCORRECTLY_FORMATTED_REQUEST);
        } finally {
            closeHttpResponse(response);
        }
    }

    private Header[] createAuthHeader() {
        return new Header[] {new BasicHeader(HttpHeaders.AUTHORIZATION, BEARER_STRING + properties.getTargetPicsureToken())};
    }
}
