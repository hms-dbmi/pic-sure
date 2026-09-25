package edu.harvard.hms.dbmi.avillach.query;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static edu.harvard.hms.dbmi.avillach.query.DocumentedErrorResponsesTest.CONSENTS;
import static edu.harvard.hms.dbmi.avillach.query.DocumentedErrorResponsesTest.GRANTED_CONSENT;
import static edu.harvard.hms.dbmi.avillach.query.DocumentedErrorResponsesTest.QUERIES;
import static edu.harvard.hms.dbmi.avillach.query.DocumentedErrorResponsesTest.QUERY_BODY;
import static edu.harvard.hms.dbmi.avillach.query.DocumentedErrorResponsesTest.authorized;
import static edu.harvard.hms.dbmi.avillach.query.DocumentedErrorResponsesTest.consentsJson;
import static edu.harvard.hms.dbmi.avillach.query.DocumentedErrorResponsesTest.endpoint;
import static edu.harvard.hms.dbmi.avillach.query.DocumentedErrorResponsesTest.identified;
import static edu.harvard.hms.dbmi.avillach.query.DocumentedErrorResponsesTest.storedQueryJson;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;

import edu.harvard.hms.dbmi.avillach.query.DocumentedErrorResponsesTest.RequestFor;

/**
 * Reproduces the 503 the OpenAPI document declares on every endpoint that selects an HPDS backend. Both {@code hpds.auth-url} and
 * {@code hpds.open-url} are blank here, which is how a deployment without that backend presents, so {@code HpdsBackendSelector} refuses the
 * call. PSAMA and operations-service still answer, so each request gets as far as backend selection.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class UnconfiguredBackendResponsesTest {

    static WireMockServer downstream;

    @BeforeAll
    static void start() {
        downstream = new WireMockServer(options().dynamicPort().http2PlainDisabled(true));
        downstream.start();
    }

    @AfterAll
    static void stop() {
        downstream.stop();
    }

    @DynamicPropertySource
    static void downstreamProps(DynamicPropertyRegistry registry) {
        registry.add("hpds.auth-url", () -> "");
        registry.add("hpds.open-url", () -> "");
        registry.add("psama.base-url", downstream::baseUrl);
        registry.add("consent.based.authorization.enabled", () -> "true");
        registry.add("picsure.query.operations.base-url", downstream::baseUrl);
    }

    @Autowired
    private MockMvc mockMvc;

    private final UUID storedId = UUID.randomUUID();

    @BeforeEach
    void healthyDependencies() throws Exception {
        downstream.resetAll();
        downstream.stubFor(WireMock.get(urlPathEqualTo(CONSENTS)).willReturn(okJson(consentsJson(List.of(GRANTED_CONSENT)))));
        downstream.stubFor(
            WireMock.get(urlPathEqualTo(QUERIES + "/" + storedId)).willReturn(okJson(storedQueryJson(storedId, List.of(GRANTED_CONSENT))))
        );
    }

    static Stream<Arguments> backendSelecting() {
        return Stream.of(
            endpoint("submit", id -> authorized(post("/hpds/auth/v3/query")).content(QUERY_BODY)),
            endpoint("sync", id -> authorized(post("/hpds/auth/v3/query/sync")).content(QUERY_BODY)),
            endpoint("status", id -> authorized(post("/hpds/open/v3/query/{id}/status", id)).content("{}")),
            endpoint("result", id -> authorized(post("/hpds/open/v3/query/{id}/result", id)).content("{}")),
            endpoint("signed-url", id -> authorized(post("/hpds/open/v3/query/{id}/signed-url", id)).content("{}")),
            endpoint("search", id -> authorized(post("/hpds/open/search")).content("{\"query\":\"age\"}")),
            endpoint("search values", id -> identified(get("/hpds/open/search/values")).param("genomicConceptPath", "\\gene\\")),
            endpoint("open submit", id -> authorized(post("/hpds/open/query")).content(QUERY_BODY)),
            endpoint("open submit v3", id -> authorized(post("/hpds/open/v3/query")).content(QUERY_BODY))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backendSelecting")
    void unconfiguredBackendIs503(String name, RequestFor request) throws Exception {
        mockMvc.perform(request.build(storedId)).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.errorType").value("backend_not_configured"));
    }
}
