package edu.harvard.hms.dbmi.avillach.query.consent;

import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;

@Component
public class PsamaConsentClient {

    static final String CLIENT_TYPE_HEADER = "X-Client-Type";
    static final String CLIENT_TYPE_SERVICE = "service";

    private final RestClient http;

    public PsamaConsentClient(@Qualifier("psamaConsentRestClient") RestClient http) {
        this.http = http;
    }

    public Map<String, Set<String>> fetch(String authorizationHeader) {
        try {
            UserConsentsResponse response = http.get().uri("/auth/user/me/consents").header(HttpHeaders.AUTHORIZATION, authorizationHeader)
                .header(CLIENT_TYPE_HEADER, CLIENT_TYPE_SERVICE).retrieve().body(UserConsentsResponse.class);
            if (response == null || response.consents() == null || response.consents().isEmpty()) {
                throw lookupFailed();
            }
            return response.consents();
        } catch (RestClientException error) {
            PicsureException failure = lookupFailed();
            failure.initCause(error);
            throw failure;
        }
    }

    private static PicsureException lookupFailed() {
        return new PicsureException(HttpStatus.BAD_GATEWAY, "consent_lookup_failed", "Unable to verify the caller's consents");
    }

    record UserConsentsResponse(Map<String, Set<String>> consents) {
    }
}
