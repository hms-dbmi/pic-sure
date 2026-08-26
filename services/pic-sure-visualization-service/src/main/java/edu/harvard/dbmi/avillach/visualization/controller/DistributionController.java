package edu.harvard.dbmi.avillach.visualization.controller;

import edu.harvard.dbmi.avillach.visualization.logging.AuditLoggingContext;
import edu.harvard.dbmi.avillach.visualization.model.AccessType;
import edu.harvard.dbmi.avillach.visualization.model.DistributionRequest;
import edu.harvard.dbmi.avillach.visualization.model.VisualizationResponse;
import edu.harvard.dbmi.avillach.visualization.service.AccessTypeResolver;
import edu.harvard.dbmi.avillach.visualization.service.QueryServiceClient;
import edu.harvard.dbmi.avillach.visualization.service.VisualizationService;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DistributionController {

    private final VisualizationService visualizationService;
    private final AccessTypeResolver accessTypeResolver;

    public DistributionController(VisualizationService visualizationService, AccessTypeResolver accessTypeResolver) {
        this.visualizationService = visualizationService;
        this.accessTypeResolver = accessTypeResolver;
    }

    @PostMapping("/{backend}/distributions")
    public ResponseEntity<VisualizationResponse> distributions(
        @PathVariable String backend, @Valid @RequestBody DistributionRequest request,
        @RequestHeader(value = GatewayUserResolver.HEADER_ACCESS_TYPE, required = false) String accessTypeHeader,
        HttpServletRequest servletRequest
    ) {
        AccessType accessType = accessTypeResolver.resolve(backend);
        AuditLoggingContext.addDistributionRequestMetadata(
            servletRequest, backend, accessTypeHeader, request.query(), visualizationService.subQueryCount(request.query())
        );
        VisualizationResponse response = visualizationService.generateDistributions(
            request.query(), accessType, gatewayIdentity(servletRequest), AuditLoggingContext.requestId(servletRequest)
        );
        AuditLoggingContext.addDistributionResponseMetadata(servletRequest, response);
        return ResponseEntity.ok(response);
    }

    /**
     * Forwards the gateway's resolved identity downstream. query-service gates {@code /hpds/**} behind {@code .authenticated()}, satisfied
     * only when {@code X-User-Id} is present -- open-access requests carry the {@code OPEN_ACCESS:<host>} marker, which qualifies. The
     * gateway strips any client-supplied value of these headers, so reading them straight off the request is safe.
     */
    private static QueryServiceClient.GatewayIdentity gatewayIdentity(HttpServletRequest request) {
        return new QueryServiceClient.GatewayIdentity(
            request.getHeader(GatewayUserResolver.HEADER_USER_ID), request.getHeader(GatewayUserResolver.HEADER_USER_SUBJECT),
            request.getHeader(GatewayUserResolver.HEADER_USER_EMAIL), request.getHeader(GatewayUserResolver.HEADER_USER_ROLES),
            request.getHeader(GatewayUserResolver.HEADER_USER_PRIVILEGES)
        );
    }
}
