package edu.harvard.dbmi.avillach.visualization.controller;

import edu.harvard.dbmi.avillach.visualization.logging.AuditLoggingInterceptor;
import edu.harvard.dbmi.avillach.visualization.model.DistributionRequest;
import edu.harvard.dbmi.avillach.visualization.model.HpdsAccessContext;
import edu.harvard.dbmi.avillach.visualization.model.VisualizationResponse;
import edu.harvard.dbmi.avillach.visualization.service.HpdsAccessResolver;
import edu.harvard.dbmi.avillach.visualization.service.VisualizationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DistributionController {

    private final VisualizationService visualizationService;
    private final HpdsAccessResolver hpdsAccessResolver;

    public DistributionController(VisualizationService visualizationService, HpdsAccessResolver hpdsAccessResolver) {
        this.visualizationService = visualizationService;
        this.hpdsAccessResolver = hpdsAccessResolver;
    }

    @PostMapping("/distributions")
    public ResponseEntity<VisualizationResponse> distributions(
        @Valid @RequestBody DistributionRequest request, @RequestHeader(value = "Authorization", required = false) String authorization,
        HttpServletRequest servletRequest
    ) {
        HpdsAccessContext accessContext = hpdsAccessResolver.resolve(request.hpdsResourceUUID());
        servletRequest.setAttribute(AuditLoggingInterceptor.ACCESS_TYPE_ATTR, accessContext.accessType().getValue());
        return ResponseEntity.ok(visualizationService.generateDistributions(request.query(), accessContext, authorization));
    }
}
