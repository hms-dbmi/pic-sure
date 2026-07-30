package edu.harvard.hms.dbmi.avillach.gateway.auth;

import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;

import edu.harvard.dbmi.avillach.contracts.auth.IntrospectionRequest;
import edu.harvard.dbmi.avillach.contracts.auth.IntrospectionResponse;
import edu.harvard.dbmi.avillach.contracts.auth.TargetedRequest;

/**
 * HTTP client for the PSAMA authentication microapp's token introspection and open-access validation endpoints.
 *
 * <p>Introspection speaks the shared {@code contracts.auth} records in both directions, so the gateway and PSAMA bind ONE declaration of
 * this wire. SECURITY: the serialized {@link TargetedRequest} is the node deployed FISMA access rules are evaluated against;
 * {@code PsamaClientTest} asserts the bytes this client puts on the wire still resolve those rules.
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

    public IntrospectionResponse introspect(String userToken, TargetedRequest request) {
        IntrospectionRequest body = new IntrospectionRequest(userToken, request);
        return http.post().uri(introspectionUrl).header("Authorization", "Bearer " + serviceToken).contentType(MediaType.APPLICATION_JSON)
            .body(body).retrieve().body(IntrospectionResponse.class);
    }

    public boolean validateOpenAccess(Map<String, Object> requestBody) {
        JsonNode resp = http.post().uri(openAccessValidateUrl).header("Authorization", "Bearer " + serviceToken)
            .contentType(MediaType.APPLICATION_JSON).body(requestBody).retrieve().body(JsonNode.class);
        return resp != null && resp.isBoolean() && resp.asBoolean();
    }
}
