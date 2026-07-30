package edu.harvard.hms.dbmi.avillach.query.search;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import edu.harvard.dbmi.avillach.contracts.query.v3.PaginatedResponse;
import edu.harvard.dbmi.avillach.contracts.query.v3.SearchRequest;
import edu.harvard.dbmi.avillach.domain.GeneralQueryRequest;
import edu.harvard.dbmi.avillach.domain.PaginatedSearchResult;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.dbmi.avillach.domain.SearchResults;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsBackendSelector;
import edu.harvard.hms.dbmi.avillach.query.hpds.ResourceWebClient;

/**
 * Ports the legacy WAR's {@code PicsureSearchService.search}/{@code searchGenomicConceptValues} (:46-109) minus Resource + AuditContext.
 * Backend (auth/open) comes from the ingress {@code {backend}} path segment via {@link HpdsBackendSelector}, mirroring
 * {@link edu.harvard.hms.dbmi.avillach.query.query.QueryService}.
 *
 * <p>The ingress contract is typed: a {@link SearchRequest} (just the search term) in, and a {@link PaginatedResponse} of concept values
 * out of {@link #searchConceptValues}. The {@code QueryRequest} envelope the WAR accepted from callers is now built HERE, purely for the
 * downstream hop.
 *
 * <p><b>Search is NOT versioned downstream</b>: unlike the query-lifecycle calls, the legacy WAR's {@code PicsureSearchService} always hit
 * HPDS at the backend's base path with no {@code /v3} suffix, even though the ingress path is versioned. This class always resolves the
 * backend with {@code v3=false} to preserve that (search endpoints on HPDS are not versioned).
 *
 * <p>Also preserves that search/values calls carry NO service token: {@link ResourceWebClient#search} and
 * {@link ResourceWebClient#searchConceptValues} take a plain base URL string (not an {@code HpdsTarget}), so the per-backend service token
 * resolved by {@link HpdsBackendSelector} is never attached -- parity with {@code PicsureSearchService}, which never set
 * {@code BEARER_TOKEN}.
 */
@Service
public class SearchService {

    private final ResourceWebClient hpds;
    private final HpdsBackendSelector selector;

    public SearchService(ResourceWebClient hpds, HpdsBackendSelector selector) {
        this.hpds = hpds;
        this.selector = selector;
    }

    public SearchResults search(String backend, SearchRequest req) {
        if (req == null) {
            throw new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", "Missing search data");
        }
        // Non-versioned downstream, no service token: only the resolved base URL is used.
        return hpds.search(selector.select(backend, false).baseUrl(), downstreamRequest(req));
    }

    public PaginatedResponse<String> searchConceptValues(String backend, String conceptPath, String query, Integer page, Integer size) {
        // The downstream call is a GET whose inputs are entirely in the query string; the envelope argument is vestigial.
        PaginatedSearchResult<?> down =
            hpds.searchConceptValues(selector.select(backend, false).baseUrl(), null, conceptPath, query, page, size);
        return toPage(down);
    }

    /**
     * Wraps the typed search term in the envelope HPDS still expects on the wire ({@code {"query": "<term>"}}).
     *
     * TODO(well-defined-contracts): Task 7 retypes {@link ResourceWebClient}; this adapter dies with it.
     */
    private static QueryRequest downstreamRequest(SearchRequest req) {
        return new GeneralQueryRequest().setQuery(req.query());
    }

    private static PaginatedResponse<String> toPage(PaginatedSearchResult<?> down) {
        if (down == null) {
            return new PaginatedResponse<>(List.of(), 0, 0);
        }
        List<String> values =
            down.getResults() == null ? List.of() : down.getResults().stream().map(v -> v == null ? null : v.toString()).toList();
        return new PaginatedResponse<>(values, down.getPage(), down.getTotal());
    }
}
