package edu.harvard.dbmi.avillach.dictionary.concept;

import edu.harvard.dbmi.avillach.dictionary.AuditAttributes;
import edu.harvard.dbmi.avillach.dictionary.concept.model.ConceptPage;
import edu.harvard.dbmi.avillach.dictionary.concept.model.Concept;
import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import edu.harvard.dbmi.avillach.dictionary.filter.FilterExamples;
import edu.harvard.dbmi.avillach.logging.AuditEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Controller
@Tag(
    name = "Concepts",
    description = "Find the concepts (variables) a query is built from: search them, look one up, and walk the concept "
        + "tree. A concept path such as `\\Synthea\\ACT Demographics\\Age\\` names one concept, and its first segment is its dataset."
)
public class ConceptController {

    static final String RAW_CONCEPT_PATH_BODY =
        "A concept path as a raw string body. The bytes of the body are the path, read as they are whatever the "
            + "Content-Type. The frontend sends the path unquoted under application/json, so a JSON-quoted string is not unquoted and would not "
            + "match any concept.";

    static final String EXAMPLE_PATH = "\\Synthea\\ACT Demographics\\Sex\\";

    static final String EXAMPLE_PARENT_PATH = "\\Synthea\\ACT Demographics\\";

    private static final String RAW_PATH_GUIDE = """
        **Body**: the concept path itself as the raw request body, not a JSON string. Send `\\Synthea\\ACT Demographics\\Sex\\` \
        with `Content-Type: text/plain`. The PIC-SURE frontend sends the same bytes with `application/json`, and the service \
        reads the body as it is either way. A JSON-quoted path matches no concept, and a request with no Content-Type \
        answers 400.
        """;

    private static final String DATASET =
        "The concept's dataset: its `dataset` field, which is also the first segment of its path, such as "
            + "`Synthea` for `\\Synthea\\ACT Demographics\\Sex\\`.";

    private static final String PAGE_NUMBER = "Zero-based index of the page to return. Defaults to 0.";

    private static final String PAGE_SIZE = "Concepts per page, 1 or more. Defaults to 10. A size below 1 answers 500.";

    private static final String DUMP_PAGE_SIZE =
        "Concepts per page, 1 or more. Defaults to 10. Every concept comes with its full detail, so keep pages modest. A size below 1 answers 500.";

    private static final String DEPTH =
        "How many levels of children to include. Defaults to 2. Above the deployment's maximum (`concept.tree.max_depth`, 5 unless "
            + "configured) answers 400.";

    private static final String LIST_CONCEPTS_DESCRIPTION = """
        Searches the dictionary and returns one page of matching concepts. This is the main search: the PIC-SURE search page \
        and the Python and R adapters' `searchDictionary` call it.

        """ + FilterExamples.FILTER_GUIDE + """

        **Paging**: `page_number` is zero-based and `page_size` defaults to 10. `totalElements` is the number of matches across \
        every page. Request the next `page_number` until the response's `last` is true.

        Each result carries its `conceptPath`, its `dataset`, and its `values` (categorical) or `min` and `max` (continuous). \
        Use `conceptPath` in an HPDS query's `select` or `phenotypicClause`, and `values`, `min` and `max` to offer the user \
        valid filter values.

            curl -X POST "https://<host>/picsure/dictionary/concepts?page_number=0&page_size=20" \\
              -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \\
              -d '{"search": "age"}'
        """;

    private static final String DUMP_DESCRIPTION = """
        Pages through every concept in the dictionary with full detail, with no search, facet or consent restriction. No \
        PIC-SURE client calls it; use it to copy or index the whole dictionary. To search, use `POST /concepts` instead.

        **Paging**: as on `POST /concepts`. Request the next `page_number` until `last` is true.

            curl "https://<host>/picsure/dictionary/concepts/dump?page_number=0&page_size=100" \\
              -H "Authorization: Bearer $TOKEN"
        """;

    private static final String DETAIL_DESCRIPTION = """
        Returns everything the dictionary holds about one concept: its `values` (categorical) or `min` and `max` \
        (continuous), its `meta`, and the `table` concept it sits under. The PIC-SURE frontend calls it when a user opens a \
        result's details or adds a filter, to list the values a categorical filter can take.

        """ + RAW_PATH_GUIDE + """

        Answers 404 when no concept has that path in that dataset.

            curl -X POST "https://<host>/picsure/dictionary/concepts/detail/Synthea" \\
              -H "Authorization: Bearer $TOKEN" -H "Content-Type: text/plain" \\
              --data-binary '\\Synthea\\ACT Demographics\\Sex\\'
        """;

    private static final String DETAILS_DESCRIPTION = """
        Looks up several concepts at once by path, without naming their datasets. Each result has the concept's names, \
        `description`, `dataset` and `meta`, but not its `values`, `min` or `max`; call `POST /concepts/detail/{dataset}` for \
        those. HPDS calls this to write the data dictionary into a PFB export.

        **Body**: a JSON array of concept paths, with each backslash escaped as `\\\\` the way JSON requires. A path that \
        matches no concept is left out, so the response can be shorter than the request.

            curl -X POST "https://<host>/picsure/dictionary/concepts/detail" \\
              -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \\
              -d '["\\\\Synthea\\\\ACT Demographics\\\\Sex\\\\", "\\\\Synthea\\\\ACT Demographics\\\\Age\\\\"]'
        """;

    private static final String TREE_DESCRIPTION = """
        Returns part of a dataset's concept tree, for browsing the dictionary as a hierarchy. The response always starts at \
        the dataset's root concept and holds every concept on the way down to the requested path, then `depth` levels of \
        `children` below it. With `depth` 0 it returns the root alone. The PIC-SURE export page calls it to load a node's \
        children when the user expands it, and the "any record of" filter calls it to collect every concept under a node.

        """ + RAW_PATH_GUIDE + """

        Answers 404 when no concept has that path in that dataset.

            curl -X POST "https://<host>/picsure/dictionary/concepts/tree/Synthea?depth=1" \\
              -H "Authorization: Bearer $TOKEN" -H "Content-Type: text/plain" \\
              --data-binary '\\Synthea\\ACT Demographics\\'
        """;

    private static final String HIERARCHY_DESCRIPTION = """
        Returns the chain of concepts from the dataset's root down to the requested concept: the root first and the \
        requested concept last. The PIC-SURE frontend shows it as the list of ancestors a user can pick from when building an \
        "any record of" filter, then loads the chosen node's subtree with `POST /concepts/tree/{dataset}`.

        """ + RAW_PATH_GUIDE + """

        Answers 404 when no concept has that path in that dataset.

            curl -X POST "https://<host>/picsure/dictionary/concepts/hierarchy/Synthea" \\
              -H "Authorization: Bearer $TOKEN" -H "Content-Type: text/plain" \\
              --data-binary '\\Synthea\\ACT Demographics\\Sex\\'
        """;

    private static final String ALL_TREES_DESCRIPTION = """
        Returns the top of every dataset's concept tree: one root concept per dataset, each with `depth` levels of `children`. \
        The PIC-SURE export page loads this first, then expands a node with `POST /concepts/tree/{dataset}`.

            curl "https://<host>/picsure/dictionary/concepts/tree?depth=1" -H "Authorization: Bearer $TOKEN"
        """;

    private final ConceptService conceptService;

    @Autowired
    private HttpServletRequest httpRequest;

    @Value("${concept.tree.max_depth:5}")
    private Integer MAX_DEPTH;


    public ConceptController(@Autowired ConceptService conceptService) {
        this.conceptService = conceptService;
    }


    @Operation(summary = "Search concepts with a filter, paginated", description = LIST_CONCEPTS_DESCRIPTION)
    @ApiResponse(responseCode = "200", description = "A page of matching concepts.")
    @ApiResponse(responseCode = "500", description = "Invalid paging parameters, or the concept count or list query failed.")
    @AuditEvent(type = "SEARCH", action = "concept.search")
    @PostMapping(path = "/concepts")
    public ResponseEntity<ConceptPage> listConcepts(
        @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(
                mediaType = "application/json", schema = @Schema(implementation = Filter.class),
                examples = {@ExampleObject(name = "Search", summary = "Concepts matching \"age\"", value = FilterExamples.SEARCH),
                    @ExampleObject(
                        name = "Search one dataset", summary = "\"age\" in the Synthea dataset", value = FilterExamples.SEARCH_IN_DATASET
                    ),
                    @ExampleObject(
                        name = "Either dataset", summary = "Every concept in Synthea or Nhanes", value = FilterExamples.EITHER_DATASET
                    ),
                    @ExampleObject(
                        name = "Two categories", summary = "Continuous concepts in Synthea", value = FilterExamples.CONTINUOUS_IN_DATASET
                    ),
                    @ExampleObject(
                        name = "With consents", summary = "\"age\" within a user's consents", value = FilterExamples.SEARCH_WITH_CONSENTS
                    )}
            )
        ) @RequestBody Filter filter,
        @Parameter(description = PAGE_NUMBER) @RequestParam(name = "page_number", defaultValue = "0", required = false) int page,
        @Parameter(description = PAGE_SIZE) @RequestParam(name = "page_size", defaultValue = "10", required = false) int size
    ) {
        PageRequest pagination = PageRequest.of(page, size);

        // Run count and list in parallel — both are independent and cached separately
        long count;
        List<Concept> concepts;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Long> countFuture = executor.submit(() -> conceptService.countConcepts(filter));
            Future<List<Concept>> conceptsFuture = executor.submit(() -> conceptService.listConcepts(filter, pagination));
            count = countFuture.get();
            concepts = conceptsFuture.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Parallel concept query interrupted", e);
        } catch (Exception e) {
            throw new RuntimeException("Parallel concept query failed", e);
        }

        PageImpl<Concept> pageResp = new PageImpl<>(concepts, pagination, count);

        AuditAttributes.putMetadata(httpRequest, "search_term", filter.search() != null ? filter.search() : "");
        AuditAttributes.putMetadata(httpRequest, "result_count", String.valueOf(count));

        return ResponseEntity.ok(ConceptPage.from(pageResp));
    }

    @Operation(summary = "Page through every concept without a filter", description = DUMP_DESCRIPTION)
    @ApiResponse(responseCode = "200", description = "A page of concepts.")
    @AuditEvent(type = "DATA_ACCESS", action = "concept.dump")
    @GetMapping(path = "/concepts/dump")
    public ResponseEntity<ConceptPage> dumpConcepts(
        @Parameter(description = PAGE_NUMBER) @RequestParam(name = "page_number", defaultValue = "0", required = false) int page,
        @Parameter(description = DUMP_PAGE_SIZE) @RequestParam(name = "page_size", defaultValue = "10", required = false) int size
    ) {
        PageRequest pagination = PageRequest.of(page, size);
        PageImpl<Concept> pageResp = new PageImpl<>(
            conceptService.listDetailedConcepts(new Filter(List.of(), "", List.of()), pagination), pagination,
            conceptService.countConcepts(new Filter(List.of(), "", List.of()))
        );

        return ResponseEntity.ok(ConceptPage.from(pageResp));
    }

    @Operation(summary = "Detail for one concept path in a dataset", description = DETAIL_DESCRIPTION)
    @ApiResponse(responseCode = "200", description = "Detail for the concept path.")
    @ApiResponse(responseCode = "404", description = "No concept at that path.")
    @AuditEvent(type = "SEARCH", action = "concept.detail")
    @PostMapping(path = "/concepts/detail/{dataset}")
    public ResponseEntity<Concept> conceptDetail(
        @Parameter(description = DATASET, example = "Synthea") @PathVariable(name = "dataset") String dataset,
        @RawConceptPathBody(example = EXAMPLE_PATH) @RequestBody() String conceptPath
    ) {
        return conceptService.conceptDetail(dataset, conceptPath).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Detail for several concept paths", description = DETAILS_DESCRIPTION)
    @ApiResponse(responseCode = "200", description = "Detail for each requested concept path.")
    @AuditEvent(type = "SEARCH", action = "concept.detail")
    @PostMapping(path = "/concepts/detail")
    public ResponseEntity<List<Concept>> conceptsDetail(
        @ArraySchema(
            arraySchema = @Schema(
                description = "The concept paths to look up, as a JSON array of strings. A path that matches no concept is left out of the response.",
                example = "[\"\\\\Synthea\\\\ACT Demographics\\\\Sex\\\\\", \"\\\\Synthea\\\\ACT Demographics\\\\Age\\\\\"]"
            ), schema = @Schema(description = "One concept path, backslash delimited.", example = EXAMPLE_PATH)
        ) @RequestBody() List<String> conceptPaths
    ) {
        return ResponseEntity.ok(conceptService.conceptsWithDetail(conceptPaths));
    }

    @Operation(summary = "Subtree under a concept path to a given depth", description = TREE_DESCRIPTION)
    @ApiResponses(
        {@ApiResponse(responseCode = "200", description = "The subtree under the concept path."),
            @ApiResponse(responseCode = "400", description = "Depth outside 0 to the configured maximum."),
            @ApiResponse(responseCode = "404", description = "No concept at that path.")}
    )
    @AuditEvent(type = "SEARCH", action = "concept.tree")
    @PostMapping(path = "/concepts/tree/{dataset}")
    public ResponseEntity<Concept> conceptTree(
        @Parameter(description = DATASET, example = "Synthea") @PathVariable(name = "dataset") String dataset,
        @RawConceptPathBody(example = EXAMPLE_PARENT_PATH) @RequestBody() String conceptPath,
        @Parameter(description = DEPTH) @RequestParam(name = "depth", required = false, defaultValue = "2") Integer depth
    ) {
        if (depth < 0 || depth > MAX_DEPTH) {
            return ResponseEntity.badRequest().build();
        }
        return conceptService.conceptTree(dataset, conceptPath, depth).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Ancestors of a concept path", description = HIERARCHY_DESCRIPTION)
    @ApiResponse(responseCode = "200", description = "The concept path's ancestors.")
    @ApiResponse(responseCode = "404", description = "No concept at that path.")
    @AuditEvent(type = "SEARCH", action = "concept.hierarchy")
    @PostMapping(path = "/concepts/hierarchy/{dataset}")
    public ResponseEntity<List<Concept>> conceptHierarchy(
        @Parameter(description = DATASET, example = "Synthea") @PathVariable(name = "dataset") String dataset,
        @RawConceptPathBody(example = EXAMPLE_PATH) @RequestBody() String conceptPath
    ) {
        List<Concept> body = conceptService.conceptHierarchy(dataset, conceptPath);
        if (body.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "Every dataset's concept tree to a given depth", description = ALL_TREES_DESCRIPTION)
    @ApiResponse(responseCode = "200", description = "Every dataset's concept tree.")
    @ApiResponse(responseCode = "400", description = "Depth outside 0 to the configured maximum.")
    @AuditEvent(type = "SEARCH", action = "concept.tree")
    @GetMapping(path = "/concepts/tree")
    public ResponseEntity<List<Concept>> allConceptTrees(
        @Parameter(description = DEPTH) @RequestParam(name = "depth", required = false, defaultValue = "2") Integer depth
    ) {
        if (depth < 0 || depth > MAX_DEPTH) {
            return ResponseEntity.badRequest().build();
        }

        return ResponseEntity.ok(conceptService.allConceptTrees(depth));
    }
}
