package edu.harvard.hms.dbmi.avillach.query.search;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import edu.harvard.dbmi.avillach.domain.PaginatedSearchResult;
import edu.harvard.dbmi.avillach.logging.AuditEvent;
import edu.harvard.hms.dbmi.avillach.query.query.QueryRequestExamples;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Exposes concept value lookup for each {@code {backend}} at {@code /hpds/{backend}/search/values}, routing through {@link SearchService}
 * to the API base URL ({@code HPDS_API_PATH} appended) of that backend's own HPDS instance.
 */
@RestController
@Tag(
    name = "Search",
    description = "Page through the values of a genomic annotation in the authorized HPDS backend."
)
public class HpdsSearchController {

    private static final String BACKEND =
        "`auth` or `open`. `open` answers only where the environment enables open HPDS, and serves no genomic data.";

    private static final String VALUES_DESCRIPTION = """
        Pages through the values of one genomic annotation, such as the genes in `Gene_with_variant`, optionally narrowed to \
        values that contain a substring. The PIC-SURE gene filter calls it to fill its gene picker as the user types, and the \
        Python and R adapters' `searchGenomicValues` call it.

        **Parameters**: all four are required.
        - `genomicConceptPath`: the genomic annotation key. `Gene_with_variant` lists genes.
        - `query`: text a value must contain. Send it empty to list every value.
        - `page`: one-based, so the first page is 1. The dictionary's `page_number` is zero-based; this one is not.
        - `size`: values per page, 1 or more.

        **Response**: `results` holds this page's values, `page` its number and `total` the number of matching values \
        across every page. Request the next `page` until a page returns fewer than `size` values.

        A missing parameter answers 400, and a `page` or `size` below 1 is rejected. A key that is not a genomic annotation \
        answers 502, because the HPDS lookup fails, so send a genomic annotation key such as `Gene_with_variant`.

        """ + "    curl \"https://<host>/picsure/hpds/auth/search/values?genomicConceptPath=Gene_with_variant&query="
        + QueryRequestExamples.GENE + "&page=1&size=20\" \\\n      -H \"Authorization: Bearer $TOKEN\"\n";

    private final SearchService service;

    public HpdsSearchController(SearchService service) {
        this.service = service;
    }

    @AuditEvent(type = "SEARCH", action = "search.values")
    @GetMapping("/hpds/{backend}/search/values")
    @Operation(summary = "Page through the values of a genomic annotation", description = VALUES_DESCRIPTION)
    @ApiResponses(
        {@ApiResponse(responseCode = "200", description = "One page of the values of the concept."),
            @ApiResponse(responseCode = "400", description = "Unknown backend."),
            @ApiResponse(
                responseCode = "502",
                description = "HPDS backend call failed, including a genomicConceptPath that is not a genomic annotation."
            ), @ApiResponse(responseCode = "503", description = "Backend not configured.")}
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
