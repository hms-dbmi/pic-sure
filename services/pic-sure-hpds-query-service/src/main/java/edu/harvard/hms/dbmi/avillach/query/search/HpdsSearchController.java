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
import edu.harvard.hms.dbmi.avillach.query.query.QueryRequestExamples;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Exposes search for each {@code {backend}} at {@code /hpds/{backend}/search} and {@code /hpds/{backend}/search/values}, routing through
 * {@link SearchService} to the API base URL ({@code HPDS_API_PATH} appended) of that backend's own HPDS instance.
 */
@RestController
@Tag(
    name = "Search",
    description = "Search the authorized HPDS backend's own concepts and genomic annotations, and page through an annotation's values."
)
public class HpdsSearchController {

    private static final String BACKEND =
        "`auth` or `open`. `open` answers only where the environment enables open HPDS, and serves no genomic data.";

    private static final String SEARCH_DESCRIPTION = """
        Searches the authorized HPDS backend's own concepts and genomic annotations. No PIC-SURE client calls it: the PIC-SURE search \
        page and the Python and R adapters search the dictionary instead (`POST /picsure/dictionary/concepts`), which pages, \
        narrows by facets and consents, and returns more detail. Use this endpoint to see what one HPDS instance holds.

        **Body**: `{"query": "<term>"}`. The term is matched, ignoring case, against any part of a concept path, against \
        whole categorical values, and against genomic annotation names and descriptions. With no term, every concept comes \
        back and no annotations, which on a large deployment is the whole dictionary, so send a term.

        **Response**: `results.phenotypes` maps each matching concept path to its metadata (its categorical values, or its \
        `min` and `max`). `results.info` maps each matching genomic annotation key to its `description`, its `values` and \
        whether it is `continuous`; page through a long value list with `GET /hpds/auth/search/values`. `searchQuery` \
        echoes the term.

        """ + "    curl -X POST \"https://<host>/picsure/hpds/auth/search\" \\\n"
        + "      -H \"Authorization: Bearer $TOKEN\" -H \"Content-Type: application/json\" \\\n      -d '{\"query\": \""
        + QueryRequestExamples.SEARCH_TERM + "\"}'\n";

    private static final String VALUES_DESCRIPTION = """
        Pages through the values of one genomic annotation, such as the genes in `Gene_with_variant`, optionally narrowed to \
        values that contain a substring. The PIC-SURE gene filter calls it to fill its gene picker as the user types, and the \
        Python and R adapters' `searchGenomicValues` call it.

        **Parameters**: all four are required.
        - `genomicConceptPath`: the annotation key, as `results.info` of `POST /hpds/auth/search` names it. \
        `Gene_with_variant` lists genes.
        - `query`: text a value must contain. Send it empty to list every value.
        - `page`: one-based, so the first page is 1. The dictionary's `page_number` is zero-based; this one is not.
        - `size`: values per page, 1 or more.

        **Response**: `results` holds this page's values, `page` its number and `total` the number of matching values \
        across every page. Request the next `page` until a page returns fewer than `size` values.

        A missing parameter answers 400, and a `page` or `size` below 1 is rejected. A key that is not a genomic annotation \
        returns an empty body instead of a page.

        """ + "    curl \"https://<host>/picsure/hpds/auth/search/values?genomicConceptPath=Gene_with_variant&query="
        + QueryRequestExamples.GENE + "&page=1&size=20\" \\\n      -H \"Authorization: Bearer $TOKEN\"\n";

    private final SearchService service;

    public HpdsSearchController(SearchService service) {
        this.service = service;
    }

    @AuditEvent(type = "SEARCH", action = "search.execute")
    @PostMapping("/hpds/{backend}/search")
    @Operation(summary = "Search concepts and genomic annotations", description = SEARCH_DESCRIPTION)
    @ApiResponses(
        {@ApiResponse(responseCode = "200", description = "The concepts and variant annotations that match the search term."),
            @ApiResponse(responseCode = "400", description = "Unknown backend, or a body that is not a search request."),
            @ApiResponse(responseCode = "502", description = "HPDS backend call failed."),
            @ApiResponse(responseCode = "503", description = "Backend not configured.")}
    )
    public SearchResults search(
        @Parameter(description = BACKEND, example = "auth", schema = @Schema(allowableValues = {"auth", "open"})) @PathVariable(
            "backend"
        ) String backend,
        @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(
                mediaType = "application/json", schema = @Schema(implementation = SearchRequest.class),
                examples = @ExampleObject(
                    name = "Search", summary = "Concepts and annotations matching the search term",
                    value = "{\"query\":\"" + QueryRequestExamples.SEARCH_TERM + "\"}"
                )
            )
        ) @RequestBody SearchRequest req
    ) {
        return service.search(backend, req);
    }

    @AuditEvent(type = "SEARCH", action = "search.values")
    @GetMapping("/hpds/{backend}/search/values")
    @Operation(summary = "Page through the values of a genomic annotation", description = VALUES_DESCRIPTION)
    @ApiResponses(
        {@ApiResponse(responseCode = "200", description = "One page of the values of the concept."),
            @ApiResponse(responseCode = "400", description = "Unknown backend."),
            @ApiResponse(responseCode = "502", description = "HPDS backend call failed."),
            @ApiResponse(responseCode = "503", description = "Backend not configured.")}
    )
    public PaginatedSearchResult<String> values(
        @Parameter(description = BACKEND, example = "auth", schema = @Schema(allowableValues = {"auth", "open"})) @PathVariable(
            "backend"
        ) String backend,
        @Parameter(description = "The genomic annotation key whose values to list.", example = "Gene_with_variant") @RequestParam(
            name = "genomicConceptPath", required = false
        ) String conceptPath,
        @Parameter(description = "Text a value must contain. Empty lists every value.", example = QueryRequestExamples.GENE) @RequestParam(
            name = "query", required = false
        ) String query,
        @Parameter(description = "One-based page number; the first page is 1.", example = "1") @RequestParam(
            name = "page", required = false
        ) Integer page,
        @Parameter(description = "Values per page, 1 or more.", example = "20") @RequestParam(name = "size", required = false) Integer size
    ) {
        return service.searchConceptValues(backend, conceptPath, query, page, size);
    }
}
