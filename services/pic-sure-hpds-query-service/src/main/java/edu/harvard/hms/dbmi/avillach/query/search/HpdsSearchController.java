package edu.harvard.hms.dbmi.avillach.query.search;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import edu.harvard.dbmi.avillach.contracts.query.v3.PaginatedResponse;
import edu.harvard.dbmi.avillach.contracts.query.v3.SearchRequest;
import edu.harvard.dbmi.avillach.domain.SearchResults;

/**
 * Ports the legacy WAR's {@code PicsureRS}/{@code PicsureRSv3} search endpoints, v3-only: {@code /hpds/{backend}/v3/search} and
 * {@code /hpds/{backend}/v3/search/values}. The legacy WAR's {@code {resourceId}} path segment is GONE (it was a registry-era placeholder
 * that clients filled with a nil UUID once the registry was removed), and so are the unversioned v1 aliases.
 *
 * <p>Both endpoints are typed and minimal: {@code /search} binds a {@link SearchRequest} (the search term, nothing else -- strict
 * deserialization rejects leftover envelope fields), and {@code /search/values} is a pure query-parameter GET returning a
 * {@link PaginatedResponse} of concept values. It previously declared a {@code @RequestBody} on a GET, which no HTTP client sends and no
 * downstream call read.
 *
 * <p>Search is never versioned downstream (see {@link SearchService}): the versioned ingress path still resolves to the backend's
 * non-versioned HPDS base.
 */
@RestController
public class HpdsSearchController {

    private final SearchService service;

    public HpdsSearchController(SearchService service) {
        this.service = service;
    }

    @PostMapping("/hpds/{backend}/v3/search")
    public SearchResults search(@PathVariable("backend") String backend, @RequestBody SearchRequest req) {
        return service.search(backend, req);
    }

    @GetMapping("/hpds/{backend}/v3/search/values")
    public PaginatedResponse<String> values(
        @PathVariable("backend") String backend, @RequestParam(name = "genomicConceptPath", required = false) String conceptPath,
        @RequestParam(name = "query", required = false) String query, @RequestParam(name = "page", required = false) Integer page,
        @RequestParam(name = "size", required = false) Integer size
    ) {
        return service.searchConceptValues(backend, conceptPath, query, page, size);
    }
}
