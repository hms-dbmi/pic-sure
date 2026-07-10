package edu.harvard.hms.dbmi.avillach.gateway.auth;

import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * HTTP client for the PSAMA authentication microapp's token introspection and open-access validation endpoints.
 */
public class PsamaClient {

    private final RestClient http;
    private final String introspectionUrl;
    private final String openAccessValidateUrl;
    private final String serviceToken;

    public PsamaClient(RestClient http, String introspectionUrl, String openAccessValidateUrl, String serviceToken) {
        this.http = http;
        this.introspectionUrl = introspectionUrl;
        this.openAccessValidateUrl = openAccessValidateUrl;
        this.serviceToken = serviceToken;
    }

    public IntrospectionResponse introspect(String userToken, Map<String, Object> requestMeta) {
        IntrospectionRequest body = new IntrospectionRequest(userToken, requestMeta);
        return http.post().uri(introspectionUrl).header("Authorization", "Bearer " + serviceToken).contentType(MediaType.APPLICATION_JSON)
            .body(body).retrieve().body(IntrospectionResponse.class);
    }

    public boolean validateOpenAccess(Map<String, Object> requestBody) {
        JsonNode resp = http.post().uri(openAccessValidateUrl).header("Authorization", "Bearer " + serviceToken)
            .contentType(MediaType.APPLICATION_JSON).body(requestBody).retrieve().body(JsonNode.class);
        return resp != null && resp.isBoolean() && resp.asBoolean();
    }
}
