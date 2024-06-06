package edu.harvard.dbmi.avillach.dictionary.facet;

import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
public class FacetController {

    private final FacetService facetService;


    @Autowired
    public FacetController(FacetService facetService) {
        this.facetService = facetService;
    }

    @PostMapping(path = "/facets")
    public ResponseEntity<List<FacetCategory>> getFacets(@RequestBody Filter filter) {
        return ResponseEntity.ok(facetService.getFacets(filter));
    }

    @GetMapping(path = "/facets/{facetCategory}/{facet}")
    public ResponseEntity<Facet> facetDetails(
        @PathVariable String facetCategory,
        @PathVariable String facet
    ) {
        return facetService.facetDetails(facetCategory, facet)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }
}
