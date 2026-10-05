package edu.harvard.dbmi.avillach.dictionary.concept;

import edu.harvard.dbmi.avillach.dictionary.AuditAttributes;
import edu.harvard.dbmi.avillach.dictionary.concept.model.Concept;
import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import edu.harvard.dbmi.avillach.logging.AuditEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
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
@Tag(name = "Concepts", description = "Search, detail, and tree views of dictionary concepts")
public class ConceptController {

    private final ConceptService conceptService;

    @Autowired
    private HttpServletRequest httpRequest;

    @Value("${concept.tree.max_depth:5}")
    private Integer MAX_DEPTH;


    public ConceptController(@Autowired ConceptService conceptService) {
        this.conceptService = conceptService;
    }


    @Operation(summary = "Search concepts with a filter, paginated")
    @ApiResponse(responseCode = "200", description = "A page of matching concepts")
    @ApiResponse(responseCode = "500", description = "Invalid paging parameters, or the concept count or list query failed")
    @AuditEvent(type = "SEARCH", action = "concept.search")
    @PostMapping(path = "/concepts")
    public ResponseEntity<Page<Concept>> listConcepts(
        @RequestBody Filter filter, @RequestParam(name = "page_number", defaultValue = "0", required = false) int page,
        @RequestParam(name = "page_size", defaultValue = "10", required = false) int size
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

        return ResponseEntity.ok(pageResp);
    }

    @Operation(summary = "Page through every concept without a filter")
    @ApiResponse(responseCode = "200", description = "A page of concepts")
    @AuditEvent(type = "DATA_ACCESS", action = "concept.dump")
    @GetMapping(path = "/concepts/dump")
    public ResponseEntity<Page<Concept>> dumpConcepts(
        @RequestParam(name = "page_number", defaultValue = "0", required = false) int page,
        @RequestParam(name = "page_size", defaultValue = "10", required = false) int size
    ) {
        PageRequest pagination = PageRequest.of(page, size);
        PageImpl<Concept> pageResp = new PageImpl<>(
            conceptService.listDetailedConcepts(new Filter(List.of(), "", List.of()), pagination), pagination,
            conceptService.countConcepts(new Filter(List.of(), "", List.of()))
        );

        return ResponseEntity.ok(pageResp);
    }

    @Operation(summary = "Detail for one concept path in a dataset")
    @ApiResponse(responseCode = "200", description = "Detail for the concept path")
    @ApiResponse(responseCode = "404", description = "No concept at that path")
    @AuditEvent(type = "SEARCH", action = "concept.detail")
    @PostMapping(path = "/concepts/detail/{dataset}")
    public ResponseEntity<Concept> conceptDetail(@PathVariable(name = "dataset") String dataset, @RequestBody() String conceptPath) {
        return conceptService.conceptDetail(dataset, conceptPath).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Detail for several concept paths")
    @ApiResponse(responseCode = "200", description = "Detail for each requested concept path")
    @AuditEvent(type = "SEARCH", action = "concept.detail")
    @PostMapping(path = "/concepts/detail")
    public ResponseEntity<List<Concept>> conceptsDetail(@RequestBody() List<String> conceptPaths) {
        return ResponseEntity.ok(conceptService.conceptsWithDetail(conceptPaths));
    }

    @Operation(summary = "Subtree under a concept path to a given depth")
    @ApiResponses(
        {@ApiResponse(responseCode = "200", description = "The subtree under the concept path"),
            @ApiResponse(responseCode = "400", description = "Depth outside 0 to the configured maximum"),
            @ApiResponse(responseCode = "404", description = "No concept at that path")}
    )
    @AuditEvent(type = "SEARCH", action = "concept.tree")
    @PostMapping(path = "/concepts/tree/{dataset}")
    public ResponseEntity<Concept> conceptTree(
        @PathVariable(name = "dataset") String dataset, @RequestBody() String conceptPath,
        @RequestParam(name = "depth", required = false, defaultValue = "2") Integer depth
    ) {
        if (depth < 0 || depth > MAX_DEPTH) {
            return ResponseEntity.badRequest().build();
        }
        return conceptService.conceptTree(dataset, conceptPath, depth).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Ancestors of a concept path")
    @ApiResponse(responseCode = "200", description = "The concept path's ancestors")
    @ApiResponse(responseCode = "404", description = "No concept at that path")
    @AuditEvent(type = "SEARCH", action = "concept.hierarchy")
    @PostMapping(path = "/concepts/hierarchy/{dataset}")
    public ResponseEntity<List<Concept>> conceptHierarchy(
        @PathVariable(name = "dataset") String dataset, @RequestBody() String conceptPath
    ) {
        List<Concept> body = conceptService.conceptHierarchy(dataset, conceptPath);
        if (body.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "Every dataset's concept tree to a given depth")
    @ApiResponse(responseCode = "200", description = "Every dataset's concept tree")
    @ApiResponse(responseCode = "400", description = "Depth outside 0 to the configured maximum")
    @AuditEvent(type = "SEARCH", action = "concept.tree")
    @GetMapping(path = "/concepts/tree")
    public ResponseEntity<List<Concept>> allConceptTrees(
        @RequestParam(name = "depth", required = false, defaultValue = "2") Integer depth
    ) {
        if (depth < 0 || depth > MAX_DEPTH) {
            return ResponseEntity.badRequest().build();
        }

        return ResponseEntity.ok(conceptService.allConceptTrees(depth));
    }
}
