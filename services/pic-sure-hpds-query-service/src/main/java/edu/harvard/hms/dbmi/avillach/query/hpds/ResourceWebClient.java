package edu.harvard.hms.dbmi.avillach.query.hpds;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import edu.harvard.dbmi.avillach.domain.PaginatedSearchResult;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.dbmi.avillach.domain.QueryStatus;
import edu.harvard.dbmi.avillach.domain.SearchResults;
import edu.harvard.dbmi.avillach.domain.SignedUrlResponse;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsBackendSelector.HpdsTarget;

/**
 * HTTP client for HPDS. Query-lifecycle calls ({@link #query}, {@link #queryStatus}, {@link #queryResult}, {@link #queryResultSignedUrl},
 * and {@link #querySync}) inject {@code Authorization: Bearer <backend service token>}. {@link #search} and {@link #searchConceptValues}
 * send no service token. An HPDS 4xx surfaces as a {@link PicsureException} carrying HPDS's status and body, so the caller learns why HPDS
 * refused the request. HPDS 5xx and I/O errors surface as {@link HpdsCommunicationException}, which maps to 502 upstream.
 */
@Component
public class ResourceWebClient {

    /** HPDS response header containing query metadata. */
    public static final String QUERY_METADATA_FIELD = "queryMetadata";
    private static final ParameterizedTypeReference<PaginatedSearchResult<String>> VALUES_PAGE = new ParameterizedTypeReference<>() {};

    private final RestClient http;

    public ResourceWebClient(@Qualifier("hpdsClient") RestClient hpdsClient) {
        this.http = hpdsClient.mutate().defaultStatusHandler(HttpStatusCode::is4xxClientError, ResourceWebClient::rejectedByHpds).build();
    }

    /**
     * Rethrows an HPDS 4xx as a {@link PicsureException} with HPDS's status, an error type derived from that status (for example
     * {@code bad_request}), and HPDS's response body as the message, falling back to the status reason phrase when the body is empty.
     *
     * @throws PicsureException always
     */
    private static void rejectedByHpds(HttpRequest request, ClientHttpResponse response) throws IOException {
        HttpStatus status = Optional.ofNullable(HttpStatus.resolve(response.getStatusCode().value())).orElse(HttpStatus.BAD_REQUEST);
        String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8).trim();
        throw new PicsureException(status, status.name().toLowerCase(Locale.ENGLISH), body.isEmpty() ? status.getReasonPhrase() : body);
    }

    public record QuerySyncResult(byte[] body, String queryMetadata) {
    }

    // --- query lifecycle: inject the per-backend service token ---

    public QueryStatus query(HpdsTarget target, QueryRequest req) {
        return postJson(target, target.baseUrl() + "/query", req, QueryStatus.class);
    }

    public QueryStatus queryStatus(HpdsTarget target, String resourceResultId, QueryRequest req) {
        return postJson(target, target.baseUrl() + "/query/" + resourceResultId + "/status", req, QueryStatus.class);
    }

    /** Returns a fully buffered octet-stream response. */
    public ResponseEntity<byte[]> queryResult(HpdsTarget target, String resourceResultId, QueryRequest req) {
        try {
            return http.post().uri(target.baseUrl() + "/query/" + resourceResultId + "/result").headers(h -> authorize(h, target))
                .contentType(MediaType.APPLICATION_JSON).body(req).retrieve().toEntity(byte[].class);
        } catch (RestClientException e) {
            throw new HpdsCommunicationException("HPDS result call failed: " + target.baseUrl(), e);
        }
    }

    /**
     * Asks HPDS for a signed URL to a completed result.
     *
     * @param target the backend's API base URL and service token
     * @param resourceResultId the id HPDS assigned to the result
     * @param req the envelope HPDS requires as a body and does not read
     * @return the signed URL HPDS answered with, or null when HPDS answered with no body
     * @throws HpdsCommunicationException when HPDS answers 5xx, answers a body that is not a signed URL object, or cannot be reached
     */
    public SignedUrlResponse queryResultSignedUrl(HpdsTarget target, String resourceResultId, QueryRequest req) {
        try {
            return http.post().uri(target.baseUrl() + "/query/" + resourceResultId + "/signed-url").headers(h -> authorize(h, target))
                .contentType(MediaType.APPLICATION_JSON).body(req).retrieve().body(SignedUrlResponse.class);
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

    // --- search: no service token ---

    public SearchResults search(String base, QueryRequest req) {
        try {
            return http.post().uri(base + "/search").contentType(MediaType.APPLICATION_JSON).body(req).retrieve().body(SearchResults.class);
        } catch (RestClientException e) {
            throw new HpdsCommunicationException("HPDS search call failed: " + base, e);
        }
    }

    /**
     * Pages through the values of a genomic concept on HPDS.
     *
     * @param base the backend's API base URL
     * @param conceptPath the genomic concept whose values are listed
     * @param query text the values must contain
     * @param page the one-based page number, or null to leave it off the downstream call
     * @param size the page size, or null to leave it off the downstream call
     * @return one page of matching values, or null when HPDS answered with no body
     * @throws HpdsCommunicationException when HPDS answers 5xx or cannot be reached
     */
    public PaginatedSearchResult<String> searchConceptValues(String base, String conceptPath, String query, Integer page, Integer size) {
        try {
            URI uri = UriComponentsBuilder.fromUriString(base + "/search/values/").queryParam("genomicConceptPath", conceptPath)
                .queryParam("query", query).queryParamIfPresent("page", Optional.ofNullable(page))
                .queryParamIfPresent("size", Optional.ofNullable(size)).encode().build().toUri();
            return http.get().uri(uri).retrieve().body(VALUES_PAGE);
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
