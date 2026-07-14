package edu.harvard.dbmi.avillach.visualization.controller;

import edu.harvard.dbmi.avillach.visualization.logging.AuditLoggingContext;
import edu.harvard.dbmi.avillach.visualization.model.ContinuousBinningRequest;
import edu.harvard.dbmi.avillach.visualization.service.VisualizationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class BinningController {

    private final VisualizationService visualizationService;

    public BinningController(VisualizationService visualizationService) {
        this.visualizationService = visualizationService;
    }

    @PostMapping({"/bin/continuous", "/v3/bin/continuous"})
    public ResponseEntity<Map<String, Map<String, Integer>>> binContinuous(
        @Valid @RequestBody ContinuousBinningRequest request, HttpServletRequest servletRequest
    ) {
        AuditLoggingContext.addBinningRequestMetadata(servletRequest, request.query());
        Map<String, Map<String, Integer>> response = visualizationService.binContinuousData(request.query());
        AuditLoggingContext.addBinningResponseMetadata(servletRequest, response);
        return ResponseEntity.ok(response);
    }
}
