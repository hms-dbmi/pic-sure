package edu.harvard.hms.dbmi.avillach.query.search;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import edu.harvard.dbmi.avillach.domain.PaginatedSearchResult;
import edu.harvard.dbmi.avillach.domain.SearchResults;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsBackendSelector;
import edu.harvard.hms.dbmi.avillach.query.hpds.ResourceWebClient;
import edu.harvard.hms.dbmi.avillach.query.routing.HpdsRouter;

/**
 * Executes search and concept-value requests against the backend selected by the ingress {@code {backend}} path segment through
 * {@link HpdsBackendSelector}.
 *
 * <p>Each call goes to the API base URL ({@code HPDS_API_PATH} appended) of the selected backend's HPDS instance. The {@code auth} and
 * {@code open} backends are separate HPDS instances, so the two backends never share a downstream endpoint.
 *
 * <p>Search and values calls carry no service token: {@link ResourceWebClient#search} and {@link ResourceWebClient#searchConceptValues}
 * take a plain base URL string (not an {@code HpdsTarget}), so the per-backend service token resolved by {@link HpdsBackendSelector} is
 * never attached.
 *
 * <p>Both calls go through {@link HpdsRouter} as the types {@value HpdsRouter#SEARCH} and {@value HpdsRouter#SEARCH_VALUES}, so on the
 * {@code auth} backend Rust HPDS may answer them or receive a mirror of them.
 */
@Service
public class SearchService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /**
     * Reads Rust's answers as leniently as {@link ResourceWebClient} reads Java's, ignoring members the model does not declare, so a field
     * the caller would never see cannot turn a Rust answer into a fallback.
     */
    private static final ObjectMapper RUST_MAPPER = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private static final TypeReference<PaginatedSearchResult<String>> VALUES_PAGE = new TypeReference<>() {};

    private final ResourceWebClient hpds;
    private final HpdsBackendSelector selector;
    private final HpdsRouter router;

    public SearchService(ResourceWebClient hpds, HpdsBackendSelector selector, HpdsRouter router) {
        this.hpds = hpds;
        this.selector = selector;
        this.router = router;
    }

    /**
     * Searches concepts on a backend. HPDS receives the term inside the outbound envelope built by {@link SearchRequest#toOutbound()}.
     *
     * @param backend the ingress {@code {backend}} segment
     * @param req the search request
     * @return the concepts and variant annotations HPDS matched
     * @throws PicsureException 400 when {@code req} is null or the backend is unknown, 503 when the backend is not configured
     */
    public SearchResults search(String backend, SearchRequest req) {
        if (req == null) {
            throw new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", "Missing search data");
        }
        String base = selector.select(backend).baseUrl();
        var outbound = req.toOutbound();
        HpdsRouter.Call<SearchResults> call = new HpdsRouter.Call<>(
            backend, HpdsRouter.SEARCH, () -> hpds.search(base, outbound),
            (client, apiBase, token, maxBody) -> client.search(apiBase, outbound, maxBody),
            answered -> RUST_MAPPER.readValue(answered.body(), SearchResults.class), SearchService::json
        );
        return router.route(call, results -> null).value();
    }

    /**
     * Pages through the values of a genomic concept on a backend.
     *
     * @param backend the ingress {@code {backend}} segment
     * @param conceptPath the genomic concept whose values are listed
     * @param query text the values must contain
     * @param page the one-based page number, or null
     * @param size the page size, or null
     * @return one page of matching values
     * @throws PicsureException 400 when the backend is unknown, 503 when it is not configured
     */
    public PaginatedSearchResult<String> searchConceptValues(String backend, String conceptPath, String query, Integer page, Integer size) {
        String base = selector.select(backend).baseUrl();
        HpdsRouter.Call<PaginatedSearchResult<String>> call = new HpdsRouter.Call<>(
            backend, HpdsRouter.SEARCH_VALUES, () -> hpds.searchConceptValues(base, conceptPath, query, page, size),
            (client, apiBase, token, maxBody) -> client.searchConceptValues(apiBase, conceptPath, query, page, size, maxBody),
            answered -> RUST_MAPPER.readValue(answered.body(), VALUES_PAGE), SearchService::json
        );
        return router.route(call, values -> null).value();
    }

    /** Serializes an answer for comparison with Rust's body. */
    private static byte[] json(Object answer) {
        try {
            return MAPPER.writeValueAsBytes(answer);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("search answer could not be serialized for comparison", e);
        }
    }
}
