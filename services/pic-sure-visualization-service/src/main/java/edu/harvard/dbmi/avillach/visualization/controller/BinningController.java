package edu.harvard.dbmi.avillach.visualization.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.visualization.error.VisualizationException;
import edu.harvard.dbmi.avillach.visualization.model.ContinuousBinningRequest;
import edu.harvard.dbmi.avillach.visualization.service.VisualizationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class BinningController {

    private final VisualizationService visualizationService;
    private final ObjectMapper objectMapper;

    public BinningController(VisualizationService visualizationService, ObjectMapper objectMapper) {
        this.visualizationService = visualizationService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/bin/continuous")
    public ResponseEntity<Map<String, Map<String, Integer>>> binContinuous(@Valid @RequestBody ContinuousBinningRequest request) {
        try {
            Map<String, Map<String, Integer>> continuousData = objectMapper.convertValue(request.query(), new TypeReference<>() {});
            return ResponseEntity.ok(visualizationService.binContinuousData(continuousData));
        } catch (IllegalArgumentException e) {
            throw new VisualizationException("Could not parse continuous data: " + e.getMessage());
        }
    }
}
