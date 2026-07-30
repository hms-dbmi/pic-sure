package edu.harvard.hms.dbmi.avillach.query.hpds;

import java.net.URI;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import edu.harvard.dbmi.avillach.contracts.query.v3.PaginatedResponse;
import edu.harvard.dbmi.avillach.contracts.query.v3.QueryStatusResponse;
import edu.harvard.dbmi.avillach.contracts.query.v3.SearchRequest;
import edu.harvard.dbmi.avillach.contracts.query.v3.SignedUrlResponse;
import edu.harvard.hms.dbmi.avillach.query.search.SearchResults;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsBackendSelector.HpdsTarget;

/**
 * RestClient port of the legacy {@code ResourceWebClient} (pic-sure-resource-api), retyped: nothing on this hop is an untyped envelope any
 * more.
 *
 * <p><b>The wire.</b> Submissions ({@link #query}, {@link #querySync}) post the BARE v3 {@link Query} -- the same shape the gateway
 * forwards after consent mutation -- and the reads carry no query at all, because HPDS already holds it behind the
 * {@code resourceResultId}: {@link #queryStatus} is a GET, and {@link #queryResult}/{@link #queryResultSignedUrl} are bodyless POSTs (POST
 * rather than GET because they are audited data-access events, not cacheable reads). Responses parse into the shared contract records
 * ({@link QueryStatusResponse}, {@link SignedUrlResponse}, {@link PaginatedResponse}). These shapes are pinned by
 * {@code ResourceWebClientTest} on this side and by {@code PicSureV3ServiceWebTest} on HPDS's.
 *
 * <p><b>Tokens.</b> Query-lifecycle calls inject {@code Authorization: Bearer <backend service token>} -- parity with the WAR's
 * {@code createHeaders(...BEARER_TOKEN...)} after {@code PicsureQueryService} put {@code resource.getToken()} into the credentials.
 * {@link #search} and {@link #searchConceptValues} inject NO service token -- parity with {@code PicsureSearchService}, which never set
 * {@code BEARER_TOKEN}; they take a plain base URL rather than an {@link HpdsTarget} so there is no token in scope to leak.
 *
 * <p>HPDS non-2xx, IO errors, and unreadable bodies all surface as {@link HpdsCommunicationException} (mapped to 502 upstream).
 */
@Component
public class ResourceWebClient {

    /** Verified field name on the legacy ResourceWebClient (ResourceWebClient.java:43). */
    public static final String QUERY_METADATA_FIELD = "queryMetadata";

    private static final ParameterizedTypeReference<PaginatedResponse<String>> CONCEPT_VALUE_PAGE = new ParameterizedTypeReference<>() {};

    private final RestClient http;

    public ResourceWebClient(@Qualifier("hpdsClient") RestClient hpdsClient) {
        this.http = hpdsClient;
    }

    public record QuerySyncResult(byte[] body, String queryMetadata) {
    }

    // --- query lifecycle: inject the per-backend service token (parity with the WAR's resource.getToken()) ---

    /** Submits the bare v3 {@link Query}; HPDS answers with the query's initial status. */
    public QueryStatusResponse query(HpdsTarget target, Query query) {
        return postJson(target, target.baseUrl() + "/query", query);
    }

    /** Bodyless GET: the {@code resourceResultId} in the path is the whole request. */
    public QueryStatusResponse queryStatus(HpdsTarget target, String resourceResultId) {
        String uri = target.baseUrl() + "/query/" + resourceResultId + "/status";
        try {
            return http.get().uri(uri).headers(h -> authorize(h, target)).retrieve().body(QueryStatusResponse.class);
        } catch (RestClientException e) {
            throw new HpdsCommunicationException("HPDS status call failed: " + uri, e);
        }
    }

    /** Bodyless POST; octet-stream, FULLY BUFFERED -- parity with ResourceWebClient.queryResult/readBytesFromResponse. */
    public ResponseEntity<byte[]> queryResult(HpdsTarget target, String resourceResultId) {
        try {
            return http.post().uri(target.baseUrl() + "/query/" + resourceResultId + "/result").headers(h -> authorize(h, target))
                .retrieve().toEntity(byte[].class);
        } catch (RestClientException e) {
            throw new HpdsCommunicationException("HPDS result call failed: " + target.baseUrl(), e);
        }
    }

    /** Bodyless POST; the answer is HPDS's {@code {"signedUrl": "..."}} parsed into the shared contract record. */
    public SignedUrlResponse queryResultSignedUrl(HpdsTarget target, String resourceResultId) {
        try {
            return http.post().uri(target.baseUrl() + "/query/" + resourceResultId + "/signed-url").headers(h -> authorize(h, target))
                .retrieve().body(SignedUrlResponse.class);
        } catch (RestClientException e) {
            throw new HpdsCommunicationException("HPDS signed-url call failed: " + target.baseUrl(), e);
        }
    }

    /** Body bytes + optional queryMetadata response header + optional request-source request header. */
    public QuerySyncResult querySync(HpdsTarget target, Query query, String requestSource) {
        try {
            ResponseEntity<byte[]> down =
                http.post().uri(target.baseUrl() + "/query/sync").contentType(MediaType.APPLICATION_JSON).headers(h -> {
                    authorize(h, target);
                    if (requestSource != null) {
                        h.add("request-source", requestSource);
                    }
                }).body(query).retrieve().toEntity(byte[].class);
            return new QuerySyncResult(down.getBody(), down.getHeaders().getFirst(QUERY_METADATA_FIELD));
        } catch (RestClientException e) {
            throw new HpdsCommunicationException("HPDS sync call failed: " + target.baseUrl(), e);
        }
    }

    // --- search: NO service token (parity with PicsureSearchService, which never set BEARER_TOKEN) ---

    public SearchResults search(String base, SearchRequest req) {
        try {
            return http.post().uri(base + "/search").contentType(MediaType.APPLICATION_JSON).body(req).retrieve().body(SearchResults.class);
        } catch (RestClientException e) {
            throw new HpdsCommunicationException("HPDS search call failed: " + base, e);
        }
    }

    public PaginatedResponse<String> searchConceptValues(String base, String conceptPath, String query, Integer page, Integer size) {
        try {
            URI uri = UriComponentsBuilder.fromUriString(base + "/search/values/").queryParam("genomicConceptPath", conceptPath)
                .queryParam("query", query).queryParamIfPresent("page", Optional.ofNullable(page))
                .queryParamIfPresent("size", Optional.ofNullable(size)).encode().build().toUri();
            return http.get().uri(uri).retrieve().body(CONCEPT_VALUE_PAGE);
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

    private QueryStatusResponse postJson(HpdsTarget target, String uri, Object body) {
        try {
            return http.post().uri(uri).headers(h -> authorize(h, target)).contentType(MediaType.APPLICATION_JSON).body(body).retrieve()
                .body(QueryStatusResponse.class);
        } catch (RestClientException e) {
            throw new HpdsCommunicationException("HPDS call failed: " + uri, e);
        }
    }
}
