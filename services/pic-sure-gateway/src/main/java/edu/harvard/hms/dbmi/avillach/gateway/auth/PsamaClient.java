package edu.harvard.hms.dbmi.avillach.gateway.auth;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * HTTP client for the PSAMA authentication microapp's token introspection and open-access validation endpoints.
 */
public class PsamaClient {

    static final String RESPONSE_VERSION = "responseVersion";

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

    /**
     * Always asks for {@code responseVersion} 2, the object response carrying key identity and the denial reason. An older PSAMA ignores
     * the field and answers with a bare boolean, which is still accepted. Anything else is a denial.
     */
    public OpenAccessValidation validateOpenAccess(Map<String, Object> requestBody) {
        Map<String, Object> body = new HashMap<>(requestBody);
        body.put(RESPONSE_VERSION, 2);
        JsonNode resp = http.post().uri(openAccessValidateUrl).header("Authorization", "Bearer " + serviceToken)
            .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);
        if (resp != null && resp.isBoolean()) {
            return OpenAccessValidation.fromBoolean(resp.asBoolean());
        }
        if (resp == null || !resp.isObject()) {
            return OpenAccessValidation.fromBoolean(false);
        }
        JsonNode valid = resp.path("valid");
        return new OpenAccessValidation(
            valid.isBoolean() && valid.asBoolean(), text(resp, "keyType"), text(resp, "keyId"), text(resp, "displayPrefix"),
            text(resp, "denial"), text(resp, "refreshedToken")
        );
    }

    private static String text(JsonNode resp, String field) {
        JsonNode value = resp.path(field);
        return value.isTextual() ? value.asText() : null;
    }
}
