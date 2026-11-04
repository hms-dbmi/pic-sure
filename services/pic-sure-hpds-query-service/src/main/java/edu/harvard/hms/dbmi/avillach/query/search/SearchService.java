package edu.harvard.hms.dbmi.avillach.query.search;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import edu.harvard.dbmi.avillach.domain.PaginatedSearchResult;
import edu.harvard.dbmi.avillach.domain.SearchResults;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsBackendSelector;
import edu.harvard.hms.dbmi.avillach.query.hpds.ResourceWebClient;

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
 */
@Service
public class SearchService {

    private final ResourceWebClient hpds;
    private final HpdsBackendSelector selector;

    public SearchService(ResourceWebClient hpds, HpdsBackendSelector selector) {
        this.hpds = hpds;
        this.selector = selector;
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
        return hpds.search(selector.select(backend).baseUrl(), req.toOutbound());
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
        return hpds.searchConceptValues(selector.select(backend).baseUrl(), conceptPath, query, page, size);
    }
}
