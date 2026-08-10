package edu.harvard.dbmi.avillach.logging.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(webEnvironment = RANDOM_PORT, properties = "picsure.logging.api-key=test-key")
class InfoControllerTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void infoReturns200WithoutAuth() {
        ResponseEntity<String> response = rest.postForEntity("/info", "{}", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("Logging Service");
        assertThat(response.getBody()).contains("queryFormats");
    }

    @Test
    void infoIdIsStableAcrossCalls() {
        String first = rest.postForEntity("/info", "{}", String.class).getBody();
        String second = rest.postForEntity("/info", "{}", String.class).getBody();

        assertThat(first).isEqualTo(second);
    }

    @Test
    void healthReturns200WithoutAuthOnceStarted() {
        ResponseEntity<String> response = rest.getForEntity("/health", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("healthy");
    }

    @Test
    void actuatorIs404ByDefault() {
        // PICSURE_ACTUATOR_EXPOSURE defaults to the 'none' sentinel.
        assertThat(rest.getForEntity("/actuator/health", String.class).getStatusCode().value()).isEqualTo(404);
        assertThat(rest.getForEntity("/actuator/prometheus", String.class).getStatusCode().value()).isEqualTo(404);
    }
}
