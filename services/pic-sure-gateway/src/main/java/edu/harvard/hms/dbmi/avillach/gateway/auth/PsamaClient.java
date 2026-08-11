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

    /**
     * Reads BOTH shapes PSAMA's {@code /open/validate} has answered with: the bare JSON boolean it emitted historically, and the
     * {@code {"valid": true|false}} record it emits since its surface was typed. Accepting both is what lets this gateway run against
     * either PSAMA version -- and is why the gateway must be deployed BEFORE PSAMA, never after: a gateway that only understood the bare
     * boolean would read an object as "not valid" and deny every open-access request.
     *
     * <p>Anything else -- null body, a shape neither branch recognizes -- denies. This is the unauthenticated path; an answer the gateway
     * cannot read is not an answer it may treat as a grant.
     */
    public boolean validateOpenAccess(Map<String, Object> requestBody) {
        JsonNode resp = http.post().uri(openAccessValidateUrl).header("Authorization", "Bearer " + serviceToken)
            .contentType(MediaType.APPLICATION_JSON).body(requestBody).retrieve().body(JsonNode.class);
        if (resp == null) {
            return false;
        }
        if (resp.isBoolean()) {
            return resp.asBoolean();
        }
        JsonNode valid = resp.get("valid");
        return valid != null && valid.isBoolean() && valid.asBoolean();
    }
}
