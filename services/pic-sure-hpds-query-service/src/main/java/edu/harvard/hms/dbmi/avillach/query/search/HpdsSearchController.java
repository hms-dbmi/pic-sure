package edu.harvard.hms.dbmi.avillach.query.search;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import edu.harvard.dbmi.avillach.domain.PaginatedSearchResult;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.dbmi.avillach.domain.SearchResults;

/**
 * Ports the legacy WAR's {@code PicsureRS}/{@code PicsureRSv3} search endpoints. Mapped under BOTH the v1 and v3 ingress prefixes for a
 * given {@code {backend}} (multi-path {@code @RequestMapping} arrays) because search behaves identically either way -- unlike the query
 * lifecycle, {@link SearchService} always resolves the backend's non-versioned base (see its class javadoc): search is never versioned
 * downstream on HPDS. The {@code resourceId} path variable is retained for ingress contract parity (an HPDS-internal identifier) but is not
 * used for routing here -- it is forwarded inside the request body, not read off the path.
 */
@RestController
public class HpdsSearchController {

    private final SearchService service;

    public HpdsSearchController(SearchService service) {
        this.service = service;
    }

    @PostMapping({"/hpds/{backend}/search/{resourceId}", "/hpds/{backend}/v3/search/{resourceId}"})
    public SearchResults search(
        @PathVariable("backend") String backend, @PathVariable("resourceId") UUID resourceId, @RequestBody QueryRequest req
    ) {
        return service.search(backend, req);
    }

    @GetMapping(value = {"/hpds/{backend}/search/{resourceId}/values/", "/hpds/{backend}/v3/search/{resourceId}/values/"}, consumes = "*/*")
    public PaginatedSearchResult<?> values(
        @PathVariable("backend") String backend, @PathVariable("resourceId") UUID resourceId,
        @RequestBody(required = false) QueryRequest req, @RequestParam(name = "genomicConceptPath", required = false) String conceptPath,
        @RequestParam(name = "query", required = false) String query, @RequestParam(name = "page", required = false) Integer page,
        @RequestParam(name = "size", required = false) Integer size
    ) {
        return service.searchConceptValues(backend, req, conceptPath, query, page, size);
    }
}
