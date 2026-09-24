package edu.harvard.hms.dbmi.avillach.query.search;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import edu.harvard.dbmi.avillach.domain.PaginatedSearchResult;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.dbmi.avillach.domain.SearchResults;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Exposes search under both v1 and v3 ingress prefixes for each {@code {backend}} because HPDS search itself is not versioned.
 * {@link SearchService} always resolves the backend's base URL without a version suffix. Supported paths are
 * {@code /hpds/{backend}[/v3]/search} and {@code /hpds/{backend}[/v3]/search/values}.
 */
@RestController
@Tag(name = "Search", description = "Concept search and value lookup on an HPDS backend")
public class HpdsSearchController {

    private final SearchService service;

    public HpdsSearchController(SearchService service) {
        this.service = service;
    }

    @PostMapping({"/hpds/{backend}/search", "/hpds/{backend}/v3/search"})
    @Operation(summary = "Search concepts on a backend")
    @ApiResponses(
        {@ApiResponse(responseCode = "200", description = "OK"),
            @ApiResponse(responseCode = "400", description = "Unknown backend or missing search data"),
            @ApiResponse(responseCode = "502", description = "HPDS backend call failed"),
            @ApiResponse(responseCode = "503", description = "Backend not configured")}
    )
    public SearchResults search(@PathVariable("backend") String backend, @RequestBody QueryRequest req) {
        return service.search(backend, req);
    }

    @GetMapping(value = {"/hpds/{backend}/search/values", "/hpds/{backend}/v3/search/values"}, consumes = "*/*")
    @Operation(summary = "Page through the values of a concept")
    @ApiResponses(
        {@ApiResponse(responseCode = "200", description = "OK"), @ApiResponse(responseCode = "400", description = "Unknown backend"),
            @ApiResponse(responseCode = "502", description = "HPDS backend call failed"),
            @ApiResponse(responseCode = "503", description = "Backend not configured")}
    )
    public PaginatedSearchResult<?> values(
        @PathVariable("backend") String backend, @RequestBody(required = false) QueryRequest req,
        @RequestParam(name = "genomicConceptPath", required = false) String conceptPath,
        @RequestParam(name = "query", required = false) String query, @RequestParam(name = "page", required = false) Integer page,
        @RequestParam(name = "size", required = false) Integer size
    ) {
        return service.searchConceptValues(backend, req, conceptPath, query, page, size);
    }
}
