package edu.harvard.hms.dbmi.avillach.query.search;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import edu.harvard.dbmi.avillach.domain.PaginatedSearchResult;
import edu.harvard.dbmi.avillach.domain.SearchResults;
import edu.harvard.dbmi.avillach.logging.AuditEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Exposes search for each {@code {backend}} at {@code /hpds/{backend}/search} and {@code /hpds/{backend}/search/values}, routing through
 * {@link SearchService} to the API base URL ({@code HPDS_API_PATH} appended) of that backend's own HPDS instance.
 */
@RestController
@Tag(name = "Search", description = "Concept search and value lookup on an HPDS backend.")
public class HpdsSearchController {

    private final SearchService service;

    public HpdsSearchController(SearchService service) {
        this.service = service;
    }

    @AuditEvent(type = "SEARCH", action = "search.execute")
    @PostMapping("/hpds/{backend}/search")
    @Operation(summary = "Search concepts on a backend")
    @ApiResponses(
        {@ApiResponse(responseCode = "200", description = "The concepts and variant annotations that match the search term."),
            @ApiResponse(responseCode = "400", description = "Unknown backend, or a body that is not a search request."),
            @ApiResponse(responseCode = "502", description = "HPDS backend call failed."),
            @ApiResponse(responseCode = "503", description = "Backend not configured.")}
    )
    public SearchResults search(@PathVariable("backend") String backend, @RequestBody SearchRequest req) {
        return service.search(backend, req);
    }

    @AuditEvent(type = "SEARCH", action = "search.values")
    @GetMapping("/hpds/{backend}/search/values")
    @Operation(summary = "Page through the values of a concept")
    @ApiResponses(
        {@ApiResponse(responseCode = "200", description = "One page of the values of the concept."),
            @ApiResponse(responseCode = "400", description = "Unknown backend."),
            @ApiResponse(responseCode = "502", description = "HPDS backend call failed."),
            @ApiResponse(responseCode = "503", description = "Backend not configured.")}
    )
    public PaginatedSearchResult<String> values(
        @PathVariable("backend") String backend, @RequestParam(name = "genomicConceptPath", required = false) String conceptPath,
        @RequestParam(name = "query", required = false) String query, @RequestParam(name = "page", required = false) Integer page,
        @RequestParam(name = "size", required = false) Integer size
    ) {
        return service.searchConceptValues(backend, conceptPath, query, page, size);
    }
}
