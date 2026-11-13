package edu.harvard.dbmi.avillach.dictionary.concept;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import edu.harvard.dbmi.avillach.dictionary.concept.model.ConceptExportMetadata;
import edu.harvard.dbmi.avillach.dictionary.concept.model.ConceptExportRequest;
import edu.harvard.dbmi.avillach.logging.AuditEvent;
import io.swagger.v3.oas.annotations.Hidden;

/**
 * Internal-only concept lookups for trusted services inside the network. Every path under {@code /internal} is gated by
 * {@link edu.harvard.hms.dbmi.avillach.commons.security.InternalTokenFilter}, which requires the {@code X-PIC-SURE-INTERNAL-TOKEN} header,
 * and is not part of the public API or the OpenAPI document.
 */
@Controller
@Hidden
public class InternalConceptController {

    private final ConceptService conceptService;

    /**
     * Creates the controller.
     *
     * @param conceptService the service that validates and runs the lookup
     */
    public InternalConceptController(ConceptService conceptService) {
        this.conceptService = conceptService;
    }

    /**
     * Returns what an export writes about each requested concept, with metadata keys as stored and without the no-show list applied. HPDS
     * calls it to write the data dictionary table of a PFB export.
     *
     * @param request the concept paths and the raw metadata keys to return
     * @return one row per concept found; a path that matches no concept is left out
     * @throws InvalidConceptExportRequestException answered as 400, if a list is missing, there are more than
     *         {@value ConceptService#MAX_EXPORT_CONCEPT_PATHS} paths, or a metadata key is not allowed for export
     */
    @AuditEvent(type = "SEARCH", action = "concept.export_metadata")
    @PostMapping(path = "/internal/concepts/export-metadata")
    public ResponseEntity<List<ConceptExportMetadata>> exportMetadata(@RequestBody ConceptExportRequest request) {
        return ResponseEntity.ok(conceptService.exportMetadata(request));
    }
}
