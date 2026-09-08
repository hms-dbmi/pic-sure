package edu.harvard.hms.dbmi.avillach.operations.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Verifies that {@link InternalTokenFilter} gates {@code /internal/**} under a non-empty {@code server.servlet.context-path}.
 * {@code getRequestURI()} includes the context prefix, while servlet filter mappings are context-relative.
 *
 * <p>The full application runs under {@code server.servlet.context-path=/ops}; the filter is registered through
 * {@code InternalTokenFilterConfig} with a context-relative {@code addUrlPatterns("/internal/*")} mapping.
 *
 * <p>Requests below use the CONTEXT-RELATIVE path ({@code /internal/queries/...}), not the {@code /ops}-prefixed one:
 * {@link TestRestTemplate}'s root URI already incorporates {@code server.servlet.context-path} (see {@code LocalHostUriTemplateHandler}),
 * so prefixing it again here would double it up.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InternalTokenFilterContextPathTest {

    @DynamicPropertySource
    static void contextPath(DynamicPropertyRegistry registry) {
        registry.add("server.servlet.context-path", () -> "/ops");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Value("${picsure.operations.internal-token}")
    private String validToken;

    @Test
    void missingTokenIsForbiddenUnderAContextPath() {
        ResponseEntity<String> response = restTemplate.getForEntity("/internal/queries/{id}", String.class, UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("\"errorType\":\"FORBIDDEN\"");
    }

    @Test
    void validTokenReachesTheControllerUnderAContextPath() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(InternalTokenFilter.HEADER, validToken);

        ResponseEntity<String> response = restTemplate.exchange(
            "/internal/queries/{id}", org.springframework.http.HttpMethod.GET, new HttpEntity<>(headers), String.class, UUID.randomUUID()
        );

        // Token accepted -> request actually reaches InternalQueryController, which 404s on an unknown id.
        // (A 403 here would mean the filter never ran under the context path -- the exact bug being regression-tested.)
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
