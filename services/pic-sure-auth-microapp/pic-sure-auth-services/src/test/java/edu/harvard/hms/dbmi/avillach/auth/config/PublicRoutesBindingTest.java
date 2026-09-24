package edu.harvard.hms.dbmi.avillach.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Boots PSAMA on the shipped {@code application.properties} and sends each request with no token through the real security filter chain.
 * Every public route must stay reachable without a token, and every protected request next to one must stay refused. Deleting, adding, or
 * loosening a public route fails here. The cache inspection controller is switched on, so a {@code /cache} refusal comes from the chain and
 * not from a missing handler.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {"spring.datasource.url=jdbc:h2:mem:psama-public-routes;MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER,VALUE,KEY",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect", "spring.jpa.hibernate.ddl-auto=create-drop",
        "APPLICATION_CLIENT_SECRET=public-routes-test-placeholder-secret", "management.endpoints.web.exposure.include=health,info",
        "app.cache.inspect.enabled=true"}
)
@AutoConfigureMockMvc
class PublicRoutesBindingTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PublicRoutes publicRoutes;

    @Test
    void shippedRoutesAreExactlyTheReviewedList() {
        assertThat(publicRoutes.shipped()).containsExactly(
            anyMethod("/actuator/health"), anyMethod("/actuator/info"), anyMethod("/authentication"), anyMethod("/authentication/**"),
            anyMethod("/v3/api-docs/**"), anyMethod("/tos/latest"), anyMethod("/open/validate"), anyMethod("/logout")
        );
        assertThat(publicRoutes.additional()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"GET, /actuator/health", "GET, /v3/api-docs", "GET, /tos/latest"})
    void probeAndDocumentRoutesAnswerOkWithoutAToken(String method, String path) {
        assertThat(statusWithoutToken(method, path)).isBetween(200, 299);
    }

    @ParameterizedTest
    @CsvSource(
        {"GET, /actuator/health", "GET, /actuator/info", "POST, /authentication", "POST, /authentication/okta", "GET, /authentication/x/y",
            "GET, /v3/api-docs", "GET, /v3/api-docs/swagger-config", "GET, /tos/latest", "POST, /open/validate", "GET, /open/validate",
            "POST, /logout"}
    )
    void publicRouteIsReachableWithoutAToken(String method, String path) {
        assertThat(statusWithoutToken(method, path)).isNotIn(401, 403);
    }

    @ParameterizedTest
    @CsvSource(
        {"GET, /user", "GET, /user/me", "GET, /user/me/consents", "POST, /user", "POST, /role", "GET, /privilege", "GET, /application",
            "DELETE, /application/abc", "GET, /accessRule", "GET, /connection", "GET, /mapping", "GET, /tos", "POST, /tos/update",
            "POST, /tos/accept", "GET, /tos/latest/x", "POST, /token/inspect", "GET, /token/refresh", "GET, /authenticationx",
            "GET, /open/validate/x", "GET, /open", "GET, /actuator/env", "GET, /actuator", "GET, /v3/api-docsx", "GET, /cache",
            "GET, /cache/mergedRulesCache"}
    )
    void protectedRequestIsRefusedWithoutAToken(String method, String path) {
        assertThat(statusWithoutToken(method, path)).isEqualTo(403);
    }

    private static PublicRoute anyMethod(String pattern) {
        return new PublicRoute(pattern, null);
    }

    /**
     * Sends a request with no {@code Authorization} header. The security chain answers a refusal itself and never throws, so a handler or
     * filter exception counts as the 500 the servlet container would send.
     */
    private int statusWithoutToken(String method, String path) {
        try {
            return mockMvc.perform(request(HttpMethod.valueOf(method), path)).andReturn().getResponse().getStatus();
        } catch (Exception handlerFailure) {
            return 500;
        }
    }
}
