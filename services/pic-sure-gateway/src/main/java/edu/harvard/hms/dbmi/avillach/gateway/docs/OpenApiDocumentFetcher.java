package edu.harvard.hms.dbmi.avillach.gateway.docs;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Fetches a service's live OpenAPI document with a plain unauthenticated GET. Every failure mode collapses to {@link UpstreamUnavailable}
 * so the route can answer 502 with one shape.
 */
public class OpenApiDocumentFetcher {

    private static final Logger log = LoggerFactory.getLogger(OpenApiDocumentFetcher.class);

    private final RestClient http;
    private final ObjectMapper json;

    public OpenApiDocumentFetcher(RestClient http, ObjectMapper json) {
        this.http = http;
        this.json = json;
    }

    /** A fetcher whose client bounds connect and read time, the same bounds the health probes use. */
    public static OpenApiDocumentFetcher withTimeouts(long connectTimeoutMs, long readTimeoutMs, ObjectMapper json) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
            .withConnectTimeout(Duration.ofMillis(connectTimeoutMs)).withReadTimeout(Duration.ofMillis(readTimeoutMs));
        RestClient http = RestClient.builder().requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings)).build();
        return new OpenApiDocumentFetcher(http, json);
    }

    /**
     * GETs the service's document.
     *
     * @return the parsed document
     * @throws UpstreamUnavailable on any transport error, non-2xx status, or a body that is not a JSON object
     */
    public ObjectNode fetch(DocumentedService service) {
        String body;
        try {
            body = http.get().uri(URI.create(service.documentUrl())).accept(MediaType.APPLICATION_JSON).retrieve().body(String.class);
        } catch (RestClientException | IllegalArgumentException ex) {
            log.warn("OpenAPI document fetch failed for {} at {}: {}", service.name(), service.documentUrl(), ex.getMessage());
            throw new UpstreamUnavailable(service.name(), ex);
        }
        JsonNode node;
        try {
            node = body == null ? null : json.readTree(body);
        } catch (IOException ex) {
            log.warn(
                "OpenAPI document fetch failed for {} at {}: response body is not valid JSON: {}", service.name(), service.documentUrl(),
                ex.getMessage()
            );
            throw new UpstreamUnavailable(service.name(), ex);
        }
        if (node == null || !node.isObject()) {
            log.warn(
                "OpenAPI document fetch failed for {} at {}: upstream body is not a JSON object", service.name(), service.documentUrl()
            );
            throw new UpstreamUnavailable(service.name(), "upstream body is not a JSON object");
        }
        return (ObjectNode) node;
    }
}
