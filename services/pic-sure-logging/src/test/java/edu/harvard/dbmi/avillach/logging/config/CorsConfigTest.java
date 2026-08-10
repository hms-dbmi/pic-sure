package edu.harvard.dbmi.avillach.logging.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(
    webEnvironment = RANDOM_PORT, properties = {"picsure.logging.api-key=test-key", "picsure.logging.allowed-origin=https://example.com"}
)
class CorsConfigTest {

    @Autowired
    private TestRestTemplate rest;

    private ResponseEntity<String> preflight(String origin) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ORIGIN, origin);
        headers.set(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST");
        return rest.exchange("/audit", HttpMethod.OPTIONS, new HttpEntity<>(headers), String.class);
    }

    @Test
    void allowedOriginIsEchoedOnPreflight() {
        assertThat(preflight("https://example.com").getHeaders().getAccessControlAllowOrigin()).isEqualTo("https://example.com");
    }

    @Test
    void disallowedOriginIsRejected() {
        assertThat(preflight("https://evil.example").getHeaders().getAccessControlAllowOrigin()).isNull();
    }

    /**
     * Pins the filter ordering. CorsFilter (order 0) must answer the preflight before ApiKeyAuthFilter (order 1) sees it, exactly as
     * Javalin's CORS plugin did. If this regresses, every browser preflight against /audit gets a 401.
     */
    @Test
    void preflightDoesNotRequireAnApiKey() {
        assertThat(preflight("https://example.com").getStatusCode().value()).isNotEqualTo(401);
    }
}
