package edu.harvard.dbmi.avillach.logging.web;

import edu.harvard.dbmi.avillach.logging.service.ReadinessState;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {

    private final ReadinessState readinessState;

    public HealthController(ReadinessState readinessState) {
        this.readinessState = readinessState;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        if (readinessState.isReady()) {
            return ResponseEntity.ok(Map.of("status", "healthy"));
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("status", "starting"));
    }
}
