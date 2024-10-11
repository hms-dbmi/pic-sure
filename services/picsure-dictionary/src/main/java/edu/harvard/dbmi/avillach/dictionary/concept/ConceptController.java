package edu.harvard.dbmi.avillach.dictionary.concept;

import edu.harvard.dbmi.avillach.dictionary.concept.model.Concept;
import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
public class ConceptController {

    private final ConceptService conceptService;

    @Value("${concept.tree.max_depth:5}")
    private Integer MAX_DEPTH;


    public ConceptController(@Autowired ConceptService conceptService) {
        this.conceptService = conceptService;
    }


    @PostMapping(path = "/concepts")
    public ResponseEntity<Page<Concept>> listConcepts(
        @RequestBody Filter filter, @RequestParam(name = "page_number", defaultValue = "0", required = false) int page,
        @RequestParam(name = "page_size", defaultValue = "10", required = false) int size
    ) {
        PageRequest pagination = PageRequest.of(page, size);
        PageImpl<Concept> pageResp =
            new PageImpl<>(conceptService.listConcepts(filter, pagination), pagination, conceptService.countConcepts(filter));

        return ResponseEntity.ok(pageResp);
    }

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

    @PostMapping(path = "/concepts/detail/{dataset}")
    public ResponseEntity<Concept> conceptDetail(@PathVariable(name = "dataset") String dataset, @RequestBody() String conceptPath) {
        return conceptService.conceptDetail(dataset, conceptPath).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

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
}
