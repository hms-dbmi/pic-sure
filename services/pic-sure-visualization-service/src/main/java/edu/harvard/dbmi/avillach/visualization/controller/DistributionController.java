package edu.harvard.dbmi.avillach.visualization.controller;

import edu.harvard.dbmi.avillach.visualization.logging.AuditLoggingContext;
import edu.harvard.dbmi.avillach.visualization.model.DistributionRequest;
import edu.harvard.dbmi.avillach.visualization.model.HpdsAccessContext;
import edu.harvard.dbmi.avillach.visualization.model.VisualizationResponse;
import edu.harvard.dbmi.avillach.visualization.service.AccessTypeResolver;
import edu.harvard.dbmi.avillach.visualization.service.VisualizationService;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUserResolver;
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
    private final AccessTypeResolver accessTypeResolver;

    public DistributionController(VisualizationService visualizationService, AccessTypeResolver accessTypeResolver) {
        this.visualizationService = visualizationService;
        this.accessTypeResolver = accessTypeResolver;
    }

    @PostMapping("/distributions")
    public ResponseEntity<VisualizationResponse> distributions(
        @Valid @RequestBody DistributionRequest request, @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestHeader(value = GatewayUserResolver.HEADER_ACCESS_TYPE, required = false) String accessTypeHeader,
        HttpServletRequest servletRequest
    ) {
        HpdsAccessContext accessContext = accessTypeResolver.resolve(accessTypeHeader);
        AuditLoggingContext.addDistributionRequestMetadata(
            servletRequest, accessContext.resourceUUID(), accessContext.accessType().getValue(), request.query(),
            visualizationService.subQueryCount(request.query())
        );
        VisualizationResponse response = visualizationService
            .generateDistributions(request.query(), accessContext, authorization, AuditLoggingContext.requestId(servletRequest));
        AuditLoggingContext.addDistributionResponseMetadata(servletRequest, response);
        return ResponseEntity.ok(response);
    }
}
