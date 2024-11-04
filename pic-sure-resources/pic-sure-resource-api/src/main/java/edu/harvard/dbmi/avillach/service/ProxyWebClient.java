package edu.harvard.dbmi.avillach.service;

import edu.harvard.dbmi.avillach.data.repository.ResourceRepository;
import edu.harvard.dbmi.avillach.util.HttpClientUtil;
import edu.harvard.dbmi.avillach.util.Utilities;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

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
        connectionManager.setDefaultMaxPerRoute(20); // Maximum connections per route

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

    private boolean containerIsNOTAResource(String container) {
        return resourceRepository.getByColumn("name", container).isEmpty();
    }

    private Response getResponse(HttpRequestBase request) throws IOException {
        HttpResponse response = client.execute(request);
        return Response.ok(response.getEntity().getContent()).build();
    }
}
