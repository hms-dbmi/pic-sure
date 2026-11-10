package edu.harvard.dbmi.avillach.dictionary.facet;

import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import edu.harvard.dbmi.avillach.dictionary.filter.FilterExamples;
import edu.harvard.dbmi.avillach.logging.AuditEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
@Tag(
    name = "Facets",
    description = "Facets group concepts, for example by dataset or by variable type. Count them for a filter, then send "
        + "the ones a user picks back in the filter of POST /concepts to narrow the search."
)
public class FacetController {

    private static final String FACETS_DESCRIPTION = """
        Counts how many concepts carry each facet, for the same filter you send to `POST /concepts`. Use it to build a facet \
        sidebar next to a search: show each category's facets with their `count`, and send the ones the user picks back in \
        the next filter's `facets`.

        """ + FilterExamples.FILTER_GUIDE + """

        **Counts**: a facet's `count` is the number of concepts that would match if it were added. Counts in a category you \
        have selected from ignore that category's own selection, so they show what picking another facet in it would add; \
        counts in every other category reflect the selection. A facet with a `count` of 0 matches nothing, and the PIC-SURE \
        frontend hides it.

        The categories differ between deployments (`dataset_id` and `data_type` on the all-in-one, \
        `study_ids_dataset_ids` on BioData Catalyst), so read them from this response instead of hard-coding them.

            curl -X POST "https://<host>/picsure/dictionary/facets" \\
              -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \\
              -d '{"search": "age"}'
        """;

    private static final String FACET_DESCRIPTION = """
        Returns one facet with its `meta`, the extra metadata the dictionary holds for it, such as `full_name`. `count` is \
        null here. No PIC-SURE client calls it; the facet lists from `POST /facets` already carry each facet's names and \
        description.

            curl "https://<host>/picsure/dictionary/facets/dataset_id/Synthea" -H "Authorization: Bearer $TOKEN"
        """;

    private final FacetService facetService;


    @Autowired
    public FacetController(FacetService facetService) {
        this.facetService = facetService;
    }

    @Operation(summary = "Facet categories and counts for a filter", description = FACETS_DESCRIPTION)
    @ApiResponse(responseCode = "200", description = "Facet categories with their counts")
    @AuditEvent(type = "SEARCH", action = "facet.search")
    @PostMapping(path = "/facets")
    public ResponseEntity<List<FacetCategory>> getFacets(
        @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(
                mediaType = "application/json", schema = @Schema(implementation = Filter.class),
                examples = {@ExampleObject(name = "Everything", summary = "Counts over every concept", value = FilterExamples.EVERYTHING),
                    @ExampleObject(name = "Search", summary = "Counts for concepts matching \"age\"", value = FilterExamples.SEARCH),
                    @ExampleObject(
                        name = "Search one dataset", summary = "Counts with the Synthea dataset selected",
                        value = FilterExamples.SEARCH_IN_DATASET
                    ),
                    @ExampleObject(
                        name = "With consents", summary = "Counts within a user's consents", value = FilterExamples.SEARCH_WITH_CONSENTS
                    )}
            )
        ) @RequestBody Filter filter
    ) {
        return ResponseEntity.ok(facetService.getFacets(filter));
    }

    @Operation(summary = "One facet within a category", description = FACET_DESCRIPTION)
    @ApiResponse(responseCode = "200", description = "The facet")
    @ApiResponse(responseCode = "404", description = "No facet with that name in the category")
    @AuditEvent(type = "SEARCH", action = "facet.detail")
    @GetMapping(path = "/facets/{facetCategory}/{facet}")
    public ResponseEntity<Facet> facetDetails(
        @Parameter(
            description = "The facet's `category`, as `POST /facets` returns it.", example = "dataset_id"
        ) @PathVariable String facetCategory,
        @Parameter(description = "The facet's `name` within that category.", example = "Synthea") @PathVariable String facet
    ) {
        return facetService.facetDetails(facetCategory, facet).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }
}
