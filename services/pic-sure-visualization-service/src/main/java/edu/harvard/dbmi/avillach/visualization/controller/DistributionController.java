package edu.harvard.dbmi.avillach.visualization.controller;

import edu.harvard.dbmi.avillach.visualization.error.BadVisualizationRequestException;
import edu.harvard.dbmi.avillach.visualization.logging.AuditLoggingContext;
import edu.harvard.dbmi.avillach.visualization.model.AccessType;
import edu.harvard.dbmi.avillach.visualization.model.VisualizationResponse;
import edu.harvard.dbmi.avillach.visualization.service.AccessTypeResolver;
import edu.harvard.dbmi.avillach.visualization.service.QueryServiceClient;
import edu.harvard.dbmi.avillach.visualization.service.VisualizationService;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUserResolver;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /distributions}: the request body IS a bare v3 {@link Query}, with no wrapper around it.
 *
 * <p>The wrapper had to go, not merely could. On the authorized path the gateway sends the buffered body to PSAMA for introspection and,
 * when PSAMA injects consent filters, its {@code BodyMutationFilter} replaces the WHOLE outbound body with the bare {@code Query} PSAMA
 * returned. A wrapper-shaped model can never bind that body, so every consent-filtered visualization request would 400 here -- while the
 * same request from a user PSAMA had nothing to filter would sail through. Binding the bare Query is what makes the authorized path
 * coherent end to end, and it is the same shape this service already forwards to query-service.
 *
 * <p>Strict binding, no leftovers: nothing here opts out of {@code FAIL_ON_UNKNOWN_PROPERTIES}, so a body still carrying the old
 * {@code query} wrapper -- or the retired registry selector {@code hpdsResourceUUID}, or the v1 envelope's {@code resourceCredentials} --
 * is a 400 naming the mismatch rather than a request whose fields are silently dropped. The auth/open backend is chosen by the
 * gateway-owned {@code X-Picsure-Access-Type} header (see {@code AccessTypeResolver}); query-service picks its HPDS backend from the path.
 */
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
        @RequestBody Query query, @RequestHeader(value = GatewayUserResolver.HEADER_ACCESS_TYPE, required = false) String accessTypeHeader,
        HttpServletRequest servletRequest
    ) {
        // Access type first: its absence means the request never traversed the gateway auth chain, which is a worse
        // problem than an unanswerable query and the one worth reporting.
        AccessType accessType = accessTypeResolver.resolve(accessTypeHeader);
        requireAnswerableQuery(query);
        AuditLoggingContext
            .addDistributionRequestMetadata(servletRequest, accessType.getValue(), query, visualizationService.subQueryCount(query));
        VisualizationResponse response = visualizationService
            .generateDistributions(query, accessType, gatewayIdentity(servletRequest), AuditLoggingContext.requestId(servletRequest));
        AuditLoggingContext.addDistributionResponseMetadata(servletRequest, response);
        return ResponseEntity.ok(response);
    }

    /**
     * Replaces the wrapper's {@code @NotNull query} check, which an unwrapped body cannot express: an empty body binds an all-null Query
     * rather than a null one, so emptiness has to be judged on content.
     *
     * <p>The bar is the one {@code QueryDecomposer} actually applies -- it reads {@code select} and the phenotypic filters and nothing else
     * -- plus genomic filters, which name real query content even though they produce no chart of their own. With none of the three there
     * is nothing to compute a distribution over, and the endpoint would answer an unconditionally empty 200: a success shape for a request
     * that asked for nothing. {@code expectedResultType} is deliberately NOT part of the bar; the decomposer overwrites it per sub-query,
     * so requiring it would demand a field this service ignores.
     *
     * <p>No null guard: an absent or literal-{@code null} body never reaches here, because Spring rejects a required body that binds to
     * null as an unreadable message and the handler answers 400. A guard here would be dead code implying that 400 came from this rule.
     */
    private static void requireAnswerableQuery(Query query) {
        if (query.select().isEmpty() && query.allFilters().isEmpty() && query.genomicFilters().isEmpty()) {
            throw new BadVisualizationRequestException(
                "Request body must be a v3 query carrying at least one 'select' path, phenotypic filter or genomic filter"
            );
        }
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
