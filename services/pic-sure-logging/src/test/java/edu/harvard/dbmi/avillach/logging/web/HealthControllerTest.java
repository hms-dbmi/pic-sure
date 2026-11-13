package edu.harvard.dbmi.avillach.logging.web;

import edu.harvard.dbmi.avillach.logging.service.ReadinessState;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HealthControllerTest {

    @Test
    void returns200HealthyWhenReady() {
        ReadinessState state = new ReadinessState();
        state.markReady();

        ResponseEntity<Map<String, String>> response = new HealthController(state).health();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("status", "healthy");
    }

    @Test
    void returns503StartingWhenNotReady() {
        ResponseEntity<Map<String, String>> response = new HealthController(new ReadinessState()).health();

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).containsEntry("status", "starting");
    }

    @Test
    void returns503AfterMarkNotReady() {
        ReadinessState state = new ReadinessState();
        state.markReady();
        state.markNotReady();

        assertThat(new HealthController(state).health().getStatusCode().value()).isEqualTo(503);
    }
}
