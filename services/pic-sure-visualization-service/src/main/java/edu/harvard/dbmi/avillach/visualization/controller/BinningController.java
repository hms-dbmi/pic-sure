package edu.harvard.dbmi.avillach.visualization.controller;

import edu.harvard.dbmi.avillach.domain.ContinuousBinningResponse;
import edu.harvard.dbmi.avillach.logging.AuditEvent;
import edu.harvard.dbmi.avillach.visualization.logging.AuditLoggingContext;
import edu.harvard.dbmi.avillach.visualization.model.ContinuousBinningRequest;
import edu.harvard.dbmi.avillach.visualization.service.VisualizationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Binning", description = "Bin continuous values for charting")
public class BinningController {

    private final VisualizationService visualizationService;

    public BinningController(VisualizationService visualizationService) {
        this.visualizationService = visualizationService;
    }

    /**
     * Groups each concept's raw value counts into chart bins and answers with the bins wrapped in a {@link ContinuousBinningResponse}. The
     * query service's open aggregate path is the only caller, and it reads the same record.
     *
     * @param request the raw counts to bin, keyed by concept path and then by numeric value
     * @param servletRequest the current request, which collects the audit metadata for this call
     * @return the binned counts for every concept in the request, in request order
     */
    @Operation(summary = "Bin continuous values into chart buckets")
    @ApiResponses(
        {@ApiResponse(responseCode = "200", description = "Binned counts for each concept"),
            @ApiResponse(responseCode = "400", description = "Malformed request")}
    )
    @AuditEvent(type = "QUERY", action = "visualization.bin_continuous")
    @PostMapping({"/bin/continuous", "/v3/bin/continuous"})
    public ResponseEntity<ContinuousBinningResponse> binContinuous(
        @Valid @RequestBody ContinuousBinningRequest request, HttpServletRequest servletRequest
    ) {
        AuditLoggingContext.addBinningRequestMetadata(servletRequest, request.query());
        ContinuousBinningResponse response = new ContinuousBinningResponse(visualizationService.binContinuousData(request.query()));
        AuditLoggingContext.addBinningResponseMetadata(servletRequest, response.bins());
        return ResponseEntity.ok(response);
    }
}
