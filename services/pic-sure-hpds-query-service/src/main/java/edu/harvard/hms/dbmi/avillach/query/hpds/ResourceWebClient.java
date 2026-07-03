package edu.harvard.hms.dbmi.avillach.query.hpds;

import java.net.URI;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import edu.harvard.dbmi.avillach.domain.PaginatedSearchResult;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.dbmi.avillach.domain.QueryStatus;
import edu.harvard.dbmi.avillach.domain.SearchResults;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsBackendSelector.HpdsTarget;

/**
 * RestClient port of the legacy {@code ResourceWebClient} (pic-sure-resource-api). Query-lifecycle calls ({@link #query},
 * {@link #queryStatus}, {@link #queryResult}, {@link #queryResultSignedUrl}, {@link #querySync}) inject
 * {@code Authorization: Bearer <backend service token>} -- parity with the WAR's {@code createHeaders(...BEARER_TOKEN...)} after
 * {@code PicsureQueryService} put {@code resource.getToken()} into the credentials. {@link #search} and {@link #searchConceptValues} inject
 * NO service token -- parity with {@code PicsureSearchService}, which never set {@code BEARER_TOKEN}. HPDS non-2xx / IO errors surface as
 * {@link HpdsCommunicationException} (mapped to 502 upstream).
 */
@Component
public class ResourceWebClient {

    /** Verified field name on the legacy ResourceWebClient (ResourceWebClient.java:43). */
    public static final String QUERY_METADATA_FIELD = "queryMetadata";

    private final RestClient http;

    public ResourceWebClient(@Qualifier("hpdsClient") RestClient hpdsClient) {
        this.http = hpdsClient;
    }

    public record QuerySyncResult(byte[] body, String queryMetadata) {
    }

    // --- query lifecycle: inject the per-backend service token (parity with the WAR's resource.getToken()) ---

    public QueryStatus query(HpdsTarget target, QueryRequest req) {
        return postJson(target, target.baseUrl() + "/query", req, QueryStatus.class);
    }

    public QueryStatus queryStatus(HpdsTarget target, String resourceResultId, QueryRequest req) {
        return postJson(target, target.baseUrl() + "/query/" + resourceResultId + "/status", req, QueryStatus.class);
    }

    /** Octet-stream, FULLY BUFFERED -- parity with ResourceWebClient.queryResult/readBytesFromResponse. */
    public ResponseEntity<byte[]> queryResult(HpdsTarget target, String resourceResultId, QueryRequest req) {
        try {
            return http.post().uri(target.baseUrl() + "/query/" + resourceResultId + "/result").headers(h -> authorize(h, target))
                .contentType(MediaType.APPLICATION_JSON).body(req).retrieve().toEntity(byte[].class);
        } catch (RestClientException e) {
            throw new HpdsCommunicationException("HPDS result call failed: " + target.baseUrl(), e);
        }
    }

    /** JSON string, FULLY BUFFERED -- parity with ResourceWebClient.queryResultSignedUrl. */
    public ResponseEntity<String> queryResultSignedUrl(HpdsTarget target, String resourceResultId, QueryRequest req) {
        try {
            return http.post().uri(target.baseUrl() + "/query/" + resourceResultId + "/signed-url").headers(h -> authorize(h, target))
                .contentType(MediaType.APPLICATION_JSON).body(req).retrieve().toEntity(String.class);
        } catch (RestClientException e) {
            throw new HpdsCommunicationException("HPDS signed-url call failed: " + target.baseUrl(), e);
        }
    }

    /** Body bytes + optional queryMetadata response header + optional request-source request header. */
    public QuerySyncResult querySync(HpdsTarget target, QueryRequest req, String requestSource) {
        try {
            ResponseEntity<byte[]> down =
                http.post().uri(target.baseUrl() + "/query/sync").contentType(MediaType.APPLICATION_JSON).headers(h -> {
                    authorize(h, target);
                    if (requestSource != null) {
                        h.add("request-source", requestSource);
                    }
                }).body(req).retrieve().toEntity(byte[].class);
            return new QuerySyncResult(down.getBody(), down.getHeaders().getFirst(QUERY_METADATA_FIELD));
        } catch (RestClientException e) {
            throw new HpdsCommunicationException("HPDS sync call failed: " + target.baseUrl(), e);
        }
    }

    // --- search: NO service token (parity with PicsureSearchService, which never set BEARER_TOKEN) ---

    public SearchResults search(String base, QueryRequest req) {
        try {
            return http.post().uri(base + "/search").contentType(MediaType.APPLICATION_JSON).body(req).retrieve().body(SearchResults.class);
        } catch (RestClientException e) {
            throw new HpdsCommunicationException("HPDS search call failed: " + base, e);
        }
    }

    public PaginatedSearchResult<?> searchConceptValues(
        String base, QueryRequest req, String conceptPath, String query, Integer page, Integer size
    ) {
        try {
            URI uri = UriComponentsBuilder.fromUriString(base + "/search/values/").queryParam("genomicConceptPath", conceptPath)
                .queryParam("query", query).queryParamIfPresent("page", Optional.ofNullable(page))
                .queryParamIfPresent("size", Optional.ofNullable(size)).encode().build().toUri();
            return http.get().uri(uri).retrieve().body(PaginatedSearchResult.class);
        } catch (RestClientException e) {
            throw new HpdsCommunicationException("HPDS search/values call failed: " + base, e);
        }
    }

    /** Adds Authorization: Bearer <service token> when the backend has one configured. */
    private static void authorize(HttpHeaders h, HpdsTarget target) {
        if (target.token() != null && !target.token().isBlank()) {
            h.add(HttpHeaders.AUTHORIZATION, "Bearer " + target.token());
        }
    }

    private <T> T postJson(HpdsTarget target, String uri, QueryRequest req, Class<T> type) {
        try {
            return http.post().uri(uri).headers(h -> authorize(h, target)).contentType(MediaType.APPLICATION_JSON).body(req).retrieve()
                .body(type);
        } catch (RestClientException e) {
            throw new HpdsCommunicationException("HPDS call failed: " + uri, e);
        }
    }
}
