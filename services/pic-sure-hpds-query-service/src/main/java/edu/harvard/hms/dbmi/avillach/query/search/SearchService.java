package edu.harvard.hms.dbmi.avillach.query.search;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

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
 * <p><b>Search is NOT versioned downstream</b>: unlike the query-lifecycle calls, the legacy WAR's {@code PicsureSearchService} always hit
 * HPDS at the backend's base path with no {@code /v3} suffix, even though the controller layer accepts both v1 and v3 ingress paths. This
 * class always resolves the backend with {@code v3=false} to preserve that (search endpoints on HPDS are not versioned).
 *
 * <p>Also preserves that search/values calls carry NO service token: {@link ResourceWebClient#search} and
 * {@link ResourceWebClient#searchConceptValues} take a plain base URL string (not an {@code HpdsTarget}), so the per-backend service token
 * resolved by {@link HpdsBackendSelector} is never attached -- parity with {@code PicsureSearchService}, which never set
 * {@code BEARER_TOKEN} on these calls.
 */
@Service
public class SearchService {

    private final ResourceWebClient hpds;
    private final HpdsBackendSelector selector;

    public SearchService(ResourceWebClient hpds, HpdsBackendSelector selector) {
        this.hpds = hpds;
        this.selector = selector;
    }

    public SearchResults search(String backend, QueryRequest req) {
        if (req == null) {
            throw new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", "Missing search data");
        }
        // Non-versioned downstream, no service token: only the resolved base URL is used.
        return hpds.search(selector.select(backend, false).baseUrl(), req);
    }

    public PaginatedSearchResult<?> searchConceptValues(
        String backend, QueryRequest req, String conceptPath, String query, Integer page, Integer size
    ) {
        return hpds.searchConceptValues(selector.select(backend, false).baseUrl(), req, conceptPath, query, page, size);
    }
}
