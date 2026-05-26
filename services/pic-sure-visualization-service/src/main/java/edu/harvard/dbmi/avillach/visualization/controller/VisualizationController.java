package edu.harvard.dbmi.avillach.visualization.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.visualization.error.VisualizationException;
import edu.harvard.dbmi.avillach.visualization.model.AccessType;
import edu.harvard.dbmi.avillach.visualization.model.VisualizationQueryRequest;
import edu.harvard.dbmi.avillach.visualization.model.VisualizationResponse;
import edu.harvard.dbmi.avillach.visualization.service.VisualizationService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/visualization")
public class VisualizationController {

    private static final Logger logger = LoggerFactory.getLogger(VisualizationController.class);

    private final VisualizationService visualizationService;
    private final ObjectMapper objectMapper;

    public VisualizationController(VisualizationService visualizationService, ObjectMapper objectMapper) {
        this.visualizationService = visualizationService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/v3/query/sync")
    public ResponseEntity<VisualizationResponse> querySync(
        @Valid @RequestBody VisualizationQueryRequest request,
        @RequestHeader(value = "request-source", required = false) String requestSource,
        @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        AccessType accessType = AccessType.fromHeader(requestSource);
        logger.info("Visualization query/sync request-source={}", accessType.getValue());

        VisualizationResponse response = visualizationService.handleQuerySync(request.query(), accessType, authorization);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/info")
    public ResponseEntity<Map<String, Object>> info() {
        return ResponseEntity.ok(
            Map.of(
                "name", "PIC-SURE Visualization Service", "queryFormats",
                Map.of(
                    "name", "PIC-SURE v3 Query Format", "specification",
                    Map.of(
                        "phenotypicClause", "A recursive AND/OR/NOT filter tree of PhenotypicFilter and PhenotypicSubquery nodes", "select",
                        "A list of concept paths to include in the visualization", "expectedResultType",
                        "CATEGORICAL_CROSS_COUNT or CONTINUOUS_CROSS_COUNT"
                    )
                )
            )
        );
    }

    /**
     * Standalone binning endpoint. The aggregate-data-sharing resource calls this with a QueryRequest (GeneralQueryRequest) where the
     * "query" field contains a Map<String, Map<String, Integer>> of continuous cross-count data to be binned.
     */
    @PostMapping("/v3/bin/continuous")
    public ResponseEntity<Map<String, Map<String, Integer>>> binContinuous(@RequestBody Map<String, Object> requestBody) {
        // The aggregate resource sends a GeneralQueryRequest with the data in the "query" field.
        // Accept both {"query": data} and raw data (for direct calls).
        Object payload = requestBody.containsKey("query") ? requestBody.get("query") : requestBody;
        if (payload == null) {
            throw new VisualizationException("Request must contain data to bin");
        }

        Map<String, Map<String, Integer>> continuousData;
        try {
            continuousData = objectMapper.convertValue(payload, new TypeReference<>() {});
        } catch (Exception e) {
            throw new VisualizationException("Could not parse continuous data: " + e.getMessage());
        }

        return ResponseEntity.ok(visualizationService.binContinuousData(continuousData));
    }
}
