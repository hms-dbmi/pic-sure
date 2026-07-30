package edu.harvard.dbmi.avillach.dictionary.concept;

import edu.harvard.dbmi.avillach.contracts.query.v3.PaginatedResponse;
import edu.harvard.dbmi.avillach.dictionary.AuditAttributes;
import edu.harvard.dbmi.avillach.dictionary.concept.model.Concept;
import edu.harvard.dbmi.avillach.dictionary.concept.model.ConceptPathRequest;
import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import edu.harvard.dbmi.avillach.logging.AuditEvent;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Controller
public class ConceptController {

    private final ConceptService conceptService;

    @Autowired
    private HttpServletRequest httpRequest;

    @Value("${concept.tree.max_depth:5}")
    private Integer MAX_DEPTH;


    public ConceptController(@Autowired ConceptService conceptService) {
        this.conceptService = conceptService;
    }


    @AuditEvent(type = "SEARCH", action = "concept.search")
    @PostMapping(path = "/concepts")
    public ResponseEntity<PaginatedResponse<Concept>> listConcepts(
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

        AuditAttributes.putMetadata(httpRequest, "search_term", filter.search() != null ? filter.search() : "");
        AuditAttributes.putMetadata(httpRequest, "result_count", String.valueOf(count));

        return ResponseEntity.ok(new PaginatedResponse<>(concepts, pagination.getPageNumber(), (int) count));
    }

    @AuditEvent(type = "DATA_ACCESS", action = "concept.dump")
    @GetMapping(path = "/concepts/dump")
    public ResponseEntity<PaginatedResponse<Concept>> dumpConcepts(
        @RequestParam(name = "page_number", defaultValue = "0", required = false) int page,
        @RequestParam(name = "page_size", defaultValue = "10", required = false) int size
    ) {
        PageRequest pagination = PageRequest.of(page, size);
        List<Concept> concepts = conceptService.listDetailedConcepts(new Filter(List.of(), "", List.of()), pagination);
        long count = conceptService.countConcepts(new Filter(List.of(), "", List.of()));

        return ResponseEntity.ok(new PaginatedResponse<>(concepts, pagination.getPageNumber(), (int) count));
    }

    @AuditEvent(type = "SEARCH", action = "concept.detail")
    @PostMapping(path = "/concepts/detail/{dataset}")
    public ResponseEntity<Concept> conceptDetail(@PathVariable(name = "dataset") String dataset, @RequestBody ConceptPathRequest request) {
        return conceptService.conceptDetail(dataset, request.conceptPath()).map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @AuditEvent(type = "SEARCH", action = "concept.detail")
    @PostMapping(path = "/concepts/detail")
    public ResponseEntity<List<Concept>> conceptsDetail(@RequestBody() List<String> conceptPaths) {
        return ResponseEntity.ok(conceptService.conceptsWithDetail(conceptPaths));
    }

    @AuditEvent(type = "SEARCH", action = "concept.tree")
    @PostMapping(path = "/concepts/tree/{dataset}")
    public ResponseEntity<Concept> conceptTree(
        @PathVariable(name = "dataset") String dataset, @RequestBody ConceptPathRequest request,
        @RequestParam(name = "depth", required = false, defaultValue = "2") Integer depth
    ) {
        if (depth < 0 || depth > MAX_DEPTH) {
            return ResponseEntity.badRequest().build();
        }
        return conceptService.conceptTree(dataset, request.conceptPath(), depth).map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @AuditEvent(type = "SEARCH", action = "concept.hierarchy")
    @PostMapping(path = "/concepts/hierarchy/{dataset}")
    public ResponseEntity<List<Concept>> conceptHierarchy(
        @PathVariable(name = "dataset") String dataset, @RequestBody ConceptPathRequest request
    ) {
        List<Concept> body = conceptService.conceptHierarchy(dataset, request.conceptPath());
        if (body.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(body);
    }

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
