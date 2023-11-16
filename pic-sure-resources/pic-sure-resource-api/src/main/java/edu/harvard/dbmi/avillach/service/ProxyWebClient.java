package edu.harvard.dbmi.avillach.service;

import edu.harvard.dbmi.avillach.util.HttpClientUtil;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.StringEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

@ApplicationScoped
public class ProxyWebClient {
    private static final Logger LOG = LoggerFactory.getLogger(ProxyWebClient.class);
    HttpClient client;

    public ProxyWebClient() {
        client = HttpClientUtil.getConfiguredHttpClient();
    }

    public Response postProxy(String containerId, String path, String body) {
        try {
            URI uri = new URIBuilder()
                .setScheme("http")
                .setHost(containerId)
                .setPath(path)
                .build();
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

    public Response getProxy(String containerId, String path) {
        try {
            URI uri = new URIBuilder()
                .setScheme("http")
                .setHost(containerId)
                .setPath(path)
                .build();
            HttpGet request = new HttpGet(uri);
            return getResponse(request);
        } catch (URISyntaxException e) {
            LOG.warn("Failed to construct URI. Container: {} Path: {}", containerId, path);
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Response getResponse(HttpRequestBase request) throws IOException {
        HttpResponse response = client.execute(request);
        return Response.ok(response.getEntity().getContent()).build();
    }
}
