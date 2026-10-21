package edu.harvard.hms.dbmi.avillach.gateway.auth;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * HTTP client for the PSAMA authentication microapp's token introspection and open-access validation endpoints.
 */
public class PsamaClient {

    static final String RESPONSE_VERSION = "responseVersion";

    /**
     * Header PSAMA sets on a 401 when it refuses the gateway's own token, naming the reason. PSAMA's {@code JWTFilter} defines it under the
     * same name, so a rename has to change both.
     */
    static final String APPLICATION_TOKEN_REJECTED_HEADER = "X-Application-Token-Rejected";

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

    /**
     * Asks PSAMA whether {@code userToken} may make the request described by {@code requestMeta}.
     *
     * @throws IntrospectionTokenRejectedException if PSAMA says it rejected the gateway's own service token
     */
    public IntrospectionResponse introspect(String userToken, Map<String, Object> requestMeta) {
        IntrospectionRequest body = new IntrospectionRequest(userToken, requestMeta);
        return http.post().uri(introspectionUrl).header("Authorization", "Bearer " + serviceToken).contentType(MediaType.APPLICATION_JSON)
            .body(body).retrieve().onStatus(rejectedServiceToken(introspectionUrl)).body(IntrospectionResponse.class);
    }

    /**
     * Always asks for {@code responseVersion} 2, the object response carrying key identity and the denial reason. An older PSAMA ignores
     * the field and answers with a bare boolean, which is still accepted. Anything else is a denial.
     *
     * @throws IntrospectionTokenRejectedException if PSAMA says it rejected the gateway's own service token
     */
    public OpenAccessValidation validateOpenAccess(Map<String, Object> requestBody) {
        Map<String, Object> body = new HashMap<>(requestBody);
        body.put(RESPONSE_VERSION, 2);
        JsonNode resp =
            http.post().uri(openAccessValidateUrl).header("Authorization", "Bearer " + serviceToken).contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().onStatus(rejectedServiceToken(openAccessValidateUrl)).body(JsonNode.class);
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

    /**
     * Matches only a 401 that carries {@link #APPLICATION_TOKEN_REJECTED_HEADER}, which PSAMA sends when it refuses the gateway's own
     * token. Any other error response, including a bare 401, falls through to RestClient's default handling.
     */
    private static ResponseErrorHandler rejectedServiceToken(String url) {
        return new ResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) throws IOException {
                return response.getStatusCode().value() == 401 && response.getHeaders().containsKey(APPLICATION_TOKEN_REJECTED_HEADER);
            }

            @Override
            public void handleError(URI uri, HttpMethod method, ClientHttpResponse response) {
                throw new IntrospectionTokenRejectedException(url, response.getHeaders().getFirst(APPLICATION_TOKEN_REJECTED_HEADER));
            }
        };
    }

    private static String text(JsonNode resp, String field) {
        JsonNode value = resp.path(field);
        return value.isTextual() ? value.asText() : null;
    }
}
