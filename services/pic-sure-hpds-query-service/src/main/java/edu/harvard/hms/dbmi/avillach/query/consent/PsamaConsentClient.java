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

    /**
     * Asks PSAMA for the consents of the caller who holds the given token.
     *
     * <p>An answer whose consents are absent or empty is returned as an empty map: the lookup worked and the caller holds nothing. Whoever
     * reads the map decides what a caller without consents may do.
     *
     * @param authorizationHeader the caller's {@code Authorization} header, forwarded to PSAMA as it is
     * @return the caller's consents as consent concept path to consent values, empty when the caller holds none
     * @throws PicsureException 502 {@code consent_lookup_failed} when PSAMA cannot be reached, answers with an error status, or answers
     *         with no body or one that cannot be read
     */
    public Map<String, Set<String>> fetch(String authorizationHeader) {
        try {
            UserConsentsResponse response = http.get().uri("/auth/user/me/consents").header(HttpHeaders.AUTHORIZATION, authorizationHeader)
                .header(CLIENT_TYPE_HEADER, CLIENT_TYPE_SERVICE).retrieve().body(UserConsentsResponse.class);
            if (response == null) {
                throw lookupFailed();
            }
            return response.consents() == null ? Map.of() : response.consents();
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
