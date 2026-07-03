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
 * Regression test for the finding that {@link InternalTokenFilter} used to gate {@code /internal/**} by checking
 * {@code request.getRequestURI().startsWith("/internal/")} directly -- which silently breaks under a non-empty
 * {@code server.servlet.context-path}, because {@code getRequestURI()} includes the context path prefix (e.g.
 * {@code /ops/internal/queries/...}), so it never starts with the literal string {@code "/internal/"} even though the
 * {@code DispatcherServlet} still routes the request straight to {@link InternalQueryController}.
 *
 * <p>Boots the full application under {@code server.servlet.context-path=/ops} and proves the filter -- now registered via
 * {@code InternalTokenFilterConfig} as a {@code FilterRegistrationBean<InternalTokenFilter>} with a container-level
 * {@code addUrlPatterns("/internal/*")} (matched relative to the context path, exactly like the servlet mapping that dispatches to the
 * controller) -- still runs and still gates the request under that context path.
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
