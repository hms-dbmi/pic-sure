package edu.harvard.dbmi.avillach.service;

import edu.harvard.dbmi.avillach.data.repository.ResourceRepository;
import edu.harvard.dbmi.avillach.util.HttpClientUtil;
import edu.harvard.dbmi.avillach.util.Utilities;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
public class ProxyWebClient {
    private static final Logger LOG = LoggerFactory.getLogger(ProxyWebClient.class);
    HttpClient client;

    @Inject
    ResourceRepository resourceRepository;

    public ProxyWebClient() {
        PoolingHttpClientConnectionManager connectionManager;

        connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(100); // Maximum total connections
        connectionManager.setDefaultMaxPerRoute(50); // Maximum connections per route
        connectionManager.setValidateAfterInactivity(5000); // Validate idle connections before reuse

        client = HttpClientUtil.getConfiguredHttpClient(connectionManager);
    }

    public Response postProxy(
        String containerId, String path, String body, MultivaluedMap<String, String> queryParams, HttpHeaders headers
    ) {
        if (containerIsNOTAResource(containerId)) {
            return Response.status(400, "container name not trustworthy").build();
        }

        String requestSource = Utilities.getRequestSourceFromHeader(headers);
        LOG.info(
            "path={}, requestSource={}, containerId={}, body={}, queryParams={}, ", path, requestSource, containerId, body, queryParams
        );

        try {
            URI uri =
                new URIBuilder().setScheme("http").setHost(containerId).setPath(path).setParameters(processParams(queryParams)).build();
            HttpPost request = new HttpPost(uri);
            request.setEntity(new StringEntity(body));
            request.addHeader("Content-Type", "application/json");
            forwardHeaders(headers, request);
            return getResponse(request);
        } catch (URISyntaxException e) {
            LOG.warn("Failed to construct URI. Container: {} Path: {}", containerId, path);
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public Response getProxy(String containerId, String path, MultivaluedMap<String, String> queryParams, HttpHeaders headers) {
        if (containerIsNOTAResource(containerId)) {
            return Response.status(400, "container name not trustworthy").build();
        }

        String requestSource = Utilities.getRequestSourceFromHeader(headers);
        LOG.info("path={}, requestSource={}, containerId={}, queryParams={}", path, requestSource, containerId, queryParams);

        try {
            URI uri =
                new URIBuilder().setScheme("http").setHost(containerId).setPath(path).setParameters(processParams(queryParams)).build();
            HttpGet request = new HttpGet(uri);
            forwardHeaders(headers, request);
            return getResponse(request);
        } catch (URISyntaxException e) {
            LOG.warn("Failed to construct URI. Container: {} Path: {}", containerId, path);
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private NameValuePair[] processParams(MultivaluedMap<String, String> params) {
        return params.entrySet().stream().flatMap(e -> e.getValue().stream().map(v -> new BasicNameValuePair(e.getKey(), v)))
            .toArray(NameValuePair[]::new);
    }

    private static final String DEFAULT_FORWARDED_HEADERS = "authorization,x-api-key,x-request-id";

    private static final Set<String> FORWARDED_HEADERS = initForwardedHeaders();

    private static Set<String> initForwardedHeaders() {
        String configured = System.getenv("PROXY_FORWARDED_HEADERS");
        String headerList = configured != null ? configured : DEFAULT_FORWARDED_HEADERS;
        return Collections.unmodifiableSet(
            Arrays.stream(headerList.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(String::toLowerCase)
                .collect(Collectors.toSet())
        );
    }

    private void forwardHeaders(HttpHeaders headers, HttpRequestBase request) {
        if (headers == null) {
            return;
        }
        for (String headerName : FORWARDED_HEADERS) {
            List<String> values = headers.getRequestHeader(headerName);
            if (values != null && !values.isEmpty()) {
                request.addHeader(headerName, values.get(0));
            }
        }
    }

    private boolean containerIsNOTAResource(String container) {
        return resourceRepository.getByColumn("name", container).isEmpty();
    }

    // Responses larger than 10MB are streamed instead of buffered to avoid OOM
    private static final long MAX_BUFFERED_BYTES = 10 * 1024 * 1024;

    private Response getResponse(HttpRequestBase request) throws IOException {
        HttpResponse response = client.execute(request);
        int status = response.getStatusLine().getStatusCode();

        if (status >= 500) {
            LOG.warn("Upstream server error: status={}, host={}, path={}", status, request.getURI().getHost(), request.getURI().getPath());
        } else if (status >= 400) {
            LOG.warn("Upstream client error: status={}, host={}, path={}", status, request.getURI().getHost(), request.getURI().getPath());
        }

        Header contentTypeHeader = response.getEntity().getContentType();
        String contentType = contentTypeHeader != null ? contentTypeHeader.getValue() : MediaType.APPLICATION_OCTET_STREAM;

        long contentLength = response.getEntity().getContentLength();
        if (contentLength > MAX_BUFFERED_BYTES || contentLength == -1) {
            // Large or unknown-size response: stream via StreamingOutput so RESTEasy
            // flushes chunks instead of buffering the entire body in heap.
            // The connection stays checked out until the client finishes reading.
            String sizeDesc = contentLength == -1 ? "unknown" : (contentLength / (1024 * 1024)) + "MB";
            LOG.info("Large upstream response ({}), streaming instead of buffering", sizeDesc);
            StreamingOutput stream = outputStream -> {
                try (InputStream in = response.getEntity().getContent()) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                        outputStream.flush();
                    }
                } finally {
                    request.releaseConnection();
                }
            };
            return Response.status(status).entity(stream).type(contentType).build();
        }

        // Normal case: buffer the response so the connection is released immediately
        try {
            byte[] body = EntityUtils.toByteArray(response.getEntity());
            return Response.status(status).entity(body).type(contentType).build();
        } catch (IOException e) {
            // Ensure the connection is released back to the pool on read failure
            request.releaseConnection();
            throw e;
        }
    }
}
