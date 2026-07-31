package edu.harvard.hms.dbmi.avillach.query.search;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import edu.harvard.dbmi.avillach.contracts.query.v3.PaginatedResponse;
import edu.harvard.dbmi.avillach.contracts.query.v3.SearchRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;

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
 * <p>Search is versioned downstream too (see {@link SearchService}): HPDS serves {@code /search} and {@code /search/values/} only under
 * {@code PIC-SURE/v3} now that its v1 controller is gone, so this ingress resolves the backend's {@code /v3} base. The legacy WAR's
 * {@code PicsureSearchService} hit the unversioned base instead -- that path no longer exists.
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

    /**
     * Straight passthrough of HPDS's {@code /search/values/}, including its <b>1-based</b> paging: this service neither rebases the request
     * nor rewrites the {@code page} HPDS echoes back. The dictionary's {@code /concepts} endpoints page from 0; the shared
     * {@code PaginatedResponse} record deliberately does not fix a base.
     */
    @Operation(
        summary = "Search genomic concept values",
        description = "Passthrough to HPDS. Paging is 1-based here: page 1 is the first page, and HPDS rejects page < 1."
    )
    @GetMapping("/hpds/{backend}/v3/search/values")
    public PaginatedResponse<String> values(
        @PathVariable("backend") String backend, @RequestParam(name = "genomicConceptPath", required = false) String conceptPath,
        @RequestParam(name = "query", required = false) String query,
        @Parameter(
            description = "ONE-BASED page index, passed through to HPDS unchanged. The first page is 1 and HPDS rejects anything "
                + "below 1. Omit to let HPDS apply its own default.",
            schema = @Schema(type = "integer", format = "int32", minimum = "1", example = "1")
        ) @RequestParam(name = "page", required = false) Integer page, @RequestParam(name = "size", required = false) Integer size
    ) {
        return service.searchConceptValues(backend, conceptPath, query, page, size);
    }
}
