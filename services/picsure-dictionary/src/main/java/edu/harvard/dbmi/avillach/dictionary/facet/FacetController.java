package edu.harvard.dbmi.avillach.dictionary.facet;

import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import edu.harvard.dbmi.avillach.logging.AuditEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
@Tag(name = "Facets", description = "Facet categories and counts for a concept filter")
public class FacetController {

    private final FacetService facetService;


    @Autowired
    public FacetController(FacetService facetService) {
        this.facetService = facetService;
    }

    @Operation(summary = "Facet categories and counts for a filter")
    @AuditEvent(type = "SEARCH", action = "facet.search")
    @PostMapping(path = "/facets")
    public ResponseEntity<List<FacetCategory>> getFacets(@RequestBody Filter filter) {
        return ResponseEntity.ok(facetService.getFacets(filter));
    }

    @Operation(summary = "One facet within a category")
    @ApiResponse(responseCode = "404", description = "No facet with that name in the category")
    @AuditEvent(type = "SEARCH", action = "facet.detail")
    @GetMapping(path = "/facets/{facetCategory}/{facet}")
    public ResponseEntity<Facet> facetDetails(@PathVariable String facetCategory, @PathVariable String facet) {
        return facetService.facetDetails(facetCategory, facet).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }
}
