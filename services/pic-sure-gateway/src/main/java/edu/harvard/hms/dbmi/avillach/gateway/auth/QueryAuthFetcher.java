package edu.harvard.hms.dbmi.avillach.gateway.auth;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;

/**
 * Fetches the stored query JSON for (/v3)?/query/{id}/(result|signed-url) paths from operations-service's gateway-only
 * {@code /internal/queries/{id}/dispatch} endpoint, so PSAMA can authorize these bodyless reads using the original query shape — NO
 * database access from the gateway itself. Sends the mandatory internal dispatch token (X-PIC-SURE-INTERNAL-TOKEN). Fail-closed: ANY error
 * (incl. 403) denies the request.
 */
public class QueryAuthFetcher {

    static final String INTERNAL_TOKEN_HEADER = "X-PIC-SURE-INTERNAL-TOKEN";

    private static final Pattern RESULT_OR_SIGNED_URL = Pattern.compile(".*/query/([^/]+)/(?:result|signed-url)/?$");

    private final RestClient http;
    private final String queryServiceBaseUrl; // HPDS_QUERY_SERVICE_URL
    private final String internalToken; // QUERY_SERVICE_INTERNAL_TOKEN

    public QueryAuthFetcher(RestClient http, String queryServiceBaseUrl, String internalToken) {
        this.http = http;
        this.queryServiceBaseUrl = queryServiceBaseUrl;
        this.internalToken = internalToken;
    }

    public Optional<String> queryJsonForPath(String path) {
        if (path == null) {
            return Optional.empty();
        }
        Matcher m = RESULT_OR_SIGNED_URL.matcher(path);
        if (!m.matches()) {
            return Optional.empty();
        }
        return Optional.of(fetchDispatch(m.group(1)));
    }

    private String fetchDispatch(String picsureId) {
        DispatchResponse resp;
        try {
            resp = http.get().uri(queryServiceBaseUrl + "/internal/queries/{id}/dispatch", picsureId)
                .header(INTERNAL_TOKEN_HEADER, internalToken).retrieve().body(DispatchResponse.class);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            // honest status + fail-closed:
            if (status == 404) {
                throw new PicsureException(HttpStatus.NOT_FOUND, "query_not_found", "No stored query for id " + picsureId);
            }
            if (status == 403) {
                // missing/wrong internal token: a config error must NEVER serve a result.
                throw new PicsureException(HttpStatus.BAD_GATEWAY, "dispatch_forbidden", "Query dispatch rejected the internal token");
            }
            throw new PicsureException(HttpStatus.BAD_GATEWAY, "dispatch_failed", "Query dispatch returned " + e.getStatusCode());
        } catch (ResourceAccessException e) {
            throw new PicsureException(HttpStatus.GATEWAY_TIMEOUT, "dispatch_unreachable", "Query service unreachable for dispatch");
        } catch (RestClientException e) {
            // malformed/undeserializable response — fail closed
            throw new PicsureException(HttpStatus.BAD_GATEWAY, "dispatch_malformed", "Query dispatch returned an unreadable response");
        }
        if (resp == null || resp.queryJson() == null || resp.queryJson().isBlank()) {
            throw new PicsureException(HttpStatus.BAD_GATEWAY, "dispatch_empty", "Dispatch returned no query for id " + picsureId);
        }
        return resp.queryJson();
    }

    record DispatchResponse(String queryJson) {
    }
}
