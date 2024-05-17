package edu.harvard.dbmi.avillach.dictionary.concept;

import edu.harvard.dbmi.avillach.dictionary.concept.model.Concept;
import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@Controller
public class ConceptController {

    private final ConceptService conceptService;

    @Value("${concept.tree.max_depth:5}")
    private Integer MAX_DEPTH;


    public ConceptController(
        @Autowired ConceptService conceptService
    ) {
        this.conceptService = conceptService;
    }


    @PostMapping(path = "/concepts/")
    public ResponseEntity<List<Concept>> listConcepts(@RequestBody Filter filter) {
        return ResponseEntity.ok(conceptService.listConcepts(filter));
    }

    @GetMapping(path = "/concepts/detail/{dataset}/{conceptPath}")
    public ResponseEntity<Concept> conceptDetail(
        @PathVariable(name = "dataset") String dataset,
        @PathVariable(name = "conceptPath") String conceptPath
    ) {
        return conceptService.conceptDetail(dataset, conceptPath)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping(path = "/concepts/tree/{dataset}/{conceptPath}")
    public ResponseEntity<Concept> conceptTree(
        @PathVariable(name = "dataset") String dataset,
        @PathVariable(name = "conceptPath") String conceptPath,
        @RequestParam(name = "depth", required = false, defaultValue = "2") Integer depth
    ) {
        if (depth < 0 || depth > MAX_DEPTH) {
            return ResponseEntity.badRequest().build();
        }
        return conceptService.conceptTree(dataset, conceptPath, depth)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }
}
