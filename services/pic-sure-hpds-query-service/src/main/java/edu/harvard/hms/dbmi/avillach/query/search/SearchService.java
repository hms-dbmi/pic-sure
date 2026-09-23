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
 * Executes search and concept-value requests against the backend selected by the ingress {@code {backend}} path segment through
 * {@link HpdsBackendSelector}.
 *
 * <p><b>Search is not versioned downstream:</b> although the controller accepts v1 and v3 ingress paths, this class resolves the backend
 * with {@code v3=false} because HPDS search endpoints have no version suffix.
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
