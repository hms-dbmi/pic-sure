package edu.harvard.hms.dbmi.avillach.query;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;

import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUserResolver;

/**
 * Reproduces every 403, 502, and 504 the OpenAPI document declares, one case per endpoint, through the real controllers, services, and HTTP
 * clients. A single WireMock server stands in for HPDS ({@code /PIC-SURE}), the aggregate open backend ({@code /OPEN}), PSAMA's consent
 * lookup, and operations-service's internal query store, so each status comes from the production throw site rather than a mocked bean. The
 * 503 cases need an unconfigured backend and live in {@link UnconfiguredBackendResponsesTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class DocumentedErrorResponsesTest {

    static final String USER = "auth0|alice";
    static final String CALLER_TOKEN = "Bearer caller-token";
    static final String QUERIES = "/operations/internal/queries";
    static final String CONSENTS = "/auth/user/me/consents";
    static final String GRANTED_CONSENT = "phs000001.c1";
    static final String UNGRANTED_CONSENT = "phs000002.c1";
    static final String QUERY_BODY = "{\"query\":{\"expectedResultType\":\"COUNT\"}}";
    static final int SLOWER_THAN_READ_TIMEOUT_MS = 1_500;

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        registry.add("hpds.auth-url", () -> downstream.baseUrl() + "/PIC-SURE");
        registry.add("hpds.open-url", () -> downstream.baseUrl() + "/PIC-SURE");
        registry.add("aggregate.hpds-open-url", () -> downstream.baseUrl() + "/OPEN");
        registry.add("psama.base-url", downstream::baseUrl);
        registry.add("consent.based.authorization.enabled", () -> "true");
        registry.add("picsure.query.operations.base-url", downstream::baseUrl);
        registry.add("picsure.query.operations.read-timeout-sec", () -> "1");
    }

    @Autowired
    private MockMvc mockMvc;

    private final UUID storedId = UUID.randomUUID();

    @BeforeEach
    void healthyDownstream() throws JsonProcessingException {
        downstream.resetAll();
        grantConsents(GRANTED_CONSENT);
        downstream.stubFor(
            WireMock.get(urlPathEqualTo(QUERIES + "/" + storedId)).willReturn(okJson(storedQueryJson(storedId, List.of(GRANTED_CONSENT))))
        );
        downstream.stubFor(
            WireMock.post(urlPathEqualTo(QUERIES)).willReturn(
                aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                    .withBody("{\"picsureId\":\"" + UUID.randomUUID() + "\"}")
            )
        );
        downstream.stubFor(WireMock.patch(urlPathMatching(QUERIES + "/.*")).willReturn(aResponse().withStatus(204)));
        downstream.stubFor(
            WireMock.post(urlPathMatching("/PIC-SURE/.*")).willReturn(
                aResponse().withStatus(200).withHeader("Content-Type", "application/json").withHeader("queryMetadata", "hpds-query-id")
                    .withBody("{\"resourceResultId\":\"rr-1\",\"status\":\"PENDING\"}")
            )
        );
    }

    /** Every endpoint that asks PSAMA for the caller's consents before doing its work. */
    static Stream<Arguments> consentChecked() {
        return Stream.of(
            endpoint("submit", id -> authorized(post("/hpds/auth/v3/query")).content(QUERY_BODY)),
            endpoint("sync", id -> authorized(post("/hpds/auth/v3/query/sync")).content(QUERY_BODY)),
            endpoint("result", id -> authorized(post("/hpds/auth/v3/query/{id}/result", id)).content("{}")),
            endpoint("signed-url", id -> authorized(post("/hpds/auth/v3/query/{id}/signed-url", id)).content("{}")),
            endpoint("metadata", id -> authorized(get("/hpds/auth/v3/query/{id}/metadata", id)))
        );
    }

    /** Endpoints that scope an incoming query to the caller's consents. */
    static Stream<Arguments> consentScoped() {
        return Stream.of(
            endpoint("submit", id -> authorized(post("/hpds/auth/v3/query")).content(QUERY_BODY)),
            endpoint("sync", id -> authorized(post("/hpds/auth/v3/query/sync")).content(QUERY_BODY))
        );
    }

    /** Endpoints that re-check the consents a stored query was scoped to before handing back its result. */
    static Stream<Arguments> consentReChecked() {
        return Stream.of(
            endpoint("result", id -> authorized(post("/hpds/auth/v3/query/{id}/result", id)).content("{}")),
            endpoint("signed-url", id -> authorized(post("/hpds/auth/v3/query/{id}/signed-url", id)).content("{}")),
            endpoint("metadata", id -> authorized(get("/hpds/auth/v3/query/{id}/metadata", id)))
        );
    }

    /**
     * A caller PSAMA reports consents for, none of which name a study, has nothing to scope a query to. An outright empty list is a 502
     * from the consent lookup rather than a 403, so the denial needs a consent that is present but unusable.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("consentScoped")
    void callerWithNoUsableConsentIs403(String name, RequestFor request) throws Exception {
        grantConsents("   ");

        mockMvc.perform(request.build(storedId)).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorType").value("consent_denied"));
    }

    /** The saved result stays behind the consents it was produced under, so losing one closes the result off. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("consentReChecked")
    void savedConsentTheCallerNoLongerHoldsIs403(String name, RequestFor request) throws Exception {
        grantConsents(UNGRANTED_CONSENT);

        mockMvc.perform(request.build(storedId)).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorType").value("consent_denied"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("consentChecked")
    void consentLookupFailureIs502(String name, RequestFor request) throws Exception {
        downstream.stubFor(WireMock.get(urlPathEqualTo(CONSENTS)).willReturn(aResponse().withStatus(500)));

        mockMvc.perform(request.build(storedId)).andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.errorType").value("consent_lookup_failed"));
    }

    /** Endpoints that call HPDS through {@code ResourceWebClient}, where an HPDS 5xx becomes 502. */
    static Stream<Arguments> hpdsBacked() {
        return Stream.of(
            endpoint("submit", id -> authorized(post("/hpds/auth/v3/query")).content(QUERY_BODY)),
            endpoint("sync", id -> authorized(post("/hpds/auth/v3/query/sync")).content(QUERY_BODY)),
            endpoint("status", id -> authorized(post("/hpds/auth/v3/query/{id}/status", id)).content("{}")),
            endpoint("result", id -> authorized(post("/hpds/auth/v3/query/{id}/result", id)).content("{}")),
            endpoint("signed-url", id -> authorized(post("/hpds/auth/v3/query/{id}/signed-url", id)).content("{}")),
            endpoint("search", id -> authorized(post("/hpds/auth/search")).content("{\"query\":\"age\"}")),
            endpoint("search values", id -> identified(get("/hpds/auth/search/values")).param("genomicConceptPath", "\\gene\\")),
            endpoint("open submit", id -> authorized(post("/hpds/open/query")).content(QUERY_BODY)),
            endpoint("open submit v3", id -> authorized(post("/hpds/open/v3/query")).content(QUERY_BODY))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("hpdsBacked")
    void hpdsServerErrorIs502(String name, RequestFor request) throws Exception {
        downstream.stubFor(WireMock.any(urlPathMatching("/PIC-SURE/.*")).willReturn(aResponse().withStatus(500)));

        mockMvc.perform(request.build(storedId)).andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.errorType").value("upstream_unavailable"));
    }

    /** The obfuscated sync endpoints call the open backend through {@code AggregateBackendClient}, not {@code ResourceWebClient}. */
    static Stream<Arguments> aggregateSync() {
        return Stream.of(
            endpoint("open sync", id -> authorized(post("/hpds/open/query/sync")).content(QUERY_BODY)),
            endpoint("open sync v3", id -> authorized(post("/hpds/open/v3/query/sync")).content(QUERY_BODY))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("aggregateSync")
    void aggregateBackendServerErrorIs502(String name, RequestFor request) throws Exception {
        downstream.stubFor(WireMock.post(urlPathMatching("/OPEN/.*")).willReturn(aResponse().withStatus(500)));

        mockMvc.perform(request.build(storedId)).andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.errorType").value("upstream_unavailable"));
    }

    /** Endpoints that load the stored query from operations-service before doing anything else. */
    static Stream<Arguments> storedQueryReads() {
        return Stream.of(
            endpoint("status", id -> authorized(post("/hpds/auth/v3/query/{id}/status", id)).content("{}")),
            endpoint("result", id -> authorized(post("/hpds/auth/v3/query/{id}/result", id)).content("{}")),
            endpoint("signed-url", id -> authorized(post("/hpds/auth/v3/query/{id}/signed-url", id)).content("{}")),
            endpoint("metadata", id -> authorized(get("/hpds/auth/v3/query/{id}/metadata", id)))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("storedQueryReads")
    void storedQueryLookupFailureIs502(String name, RequestFor request) throws Exception {
        downstream.stubFor(WireMock.get(urlPathMatching(QUERIES + "/.*")).willReturn(aResponse().withStatus(500)));

        mockMvc.perform(request.build(storedId)).andExpect(status().isBadGateway()).andExpect(jsonPath("$.errorType").value("bad_gateway"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("storedQueryReads")
    void storedQueryLookupTimeoutIs504(String name, RequestFor request) throws Exception {
        downstream.stubFor(
            WireMock.get(urlPathMatching(QUERIES + "/.*"))
                .willReturn(okJson(storedQueryJson(storedId, List.of(GRANTED_CONSENT))).withFixedDelay(SLOWER_THAN_READ_TIMEOUT_MS))
        );

        mockMvc.perform(request.build(storedId)).andExpect(status().isGatewayTimeout())
            .andExpect(jsonPath("$.errorType").value("gateway_timeout"));
    }

    @Test
    void statusUpdateFailureIs502() throws Exception {
        downstream.stubFor(WireMock.patch(urlPathMatching(QUERIES + "/.*")).willReturn(aResponse().withStatus(500)));

        mockMvc.perform(authorized(post("/hpds/auth/v3/query/{id}/status", storedId)).content("{}")).andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.errorType").value("bad_gateway"));
    }

    /** Endpoints that persist a new query through operations-service after HPDS accepts it. */
    static Stream<Arguments> persistingSubmits() {
        return Stream.of(
            endpoint("submit", id -> authorized(post("/hpds/auth/v3/query")).content(QUERY_BODY)),
            endpoint("sync", id -> authorized(post("/hpds/auth/v3/query/sync")).content(QUERY_BODY)),
            endpoint("open submit", id -> authorized(post("/hpds/open/query")).content(QUERY_BODY)),
            endpoint("open submit v3", id -> authorized(post("/hpds/open/v3/query")).content(QUERY_BODY))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("persistingSubmits")
    void querySaveFailureIs502(String name, RequestFor request) throws Exception {
        downstream.stubFor(WireMock.post(urlPathEqualTo(QUERIES)).willReturn(aResponse().withStatus(500)));

        mockMvc.perform(request.build(storedId)).andExpect(status().isBadGateway()).andExpect(jsonPath("$.errorType").value("bad_gateway"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("persistingSubmits")
    void querySaveTimeoutIs504(String name, RequestFor request) throws Exception {
        downstream.stubFor(
            WireMock.post(urlPathEqualTo(QUERIES)).willReturn(
                aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                    .withBody("{\"picsureId\":\"" + UUID.randomUUID() + "\"}").withFixedDelay(SLOWER_THAN_READ_TIMEOUT_MS)
            )
        );

        mockMvc.perform(request.build(storedId)).andExpect(status().isGatewayTimeout())
            .andExpect(jsonPath("$.errorType").value("gateway_timeout"));
    }

    /** Builds one request against the stored query id the test stubbed. */
    @FunctionalInterface
    interface RequestFor {
        MockHttpServletRequestBuilder build(UUID storedId);
    }

    static Arguments endpoint(String name, RequestFor request) {
        return Arguments.of(name, request);
    }

    /** Adds the gateway identity header every {@code /hpds/**} request needs to pass {@code WebSecurityConfig}. */
    static MockHttpServletRequestBuilder identified(MockHttpServletRequestBuilder request) {
        return request.header(GatewayUserResolver.HEADER_USER_ID, USER);
    }

    /** Adds the gateway identity, the caller's bearer token for the consent lookup, and a JSON content type. */
    static MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request) {
        return identified(request).header(HttpHeaders.AUTHORIZATION, CALLER_TOKEN).contentType(MediaType.APPLICATION_JSON);
    }

    private static void grantConsents(String... consents) throws JsonProcessingException {
        downstream.stubFor(WireMock.get(urlPathEqualTo(CONSENTS)).willReturn(okJson(consentsJson(List.of(consents)))));
    }

    /** PSAMA's {@code /user/me/consents} body granting {@code consents}. */
    static String consentsJson(List<String> consents) throws JsonProcessingException {
        return MAPPER.writeValueAsString(Map.of("consents", consents));
    }

    /** The operations-service row for a v3 query that was scoped to {@code consentValues} when it was submitted. */
    static String storedQueryJson(UUID id, List<String> consentValues) throws JsonProcessingException {
        List<Map<String, String>> userConsents = consentValues.stream().map(value -> Map.of("value", value)).toList();
        Map<String, Object> request = Map.of("query", Map.of("expectedResultType", "COUNT", "userConsents", userConsents));
        Map<String, Object> row = Map.of(
            "picsureId", id.toString(), "query", MAPPER.writeValueAsString(request), "resourceResultId", "rr-1", "status", "PENDING",
            "version", "3"
        );
        return MAPPER.writeValueAsString(row);
    }
}
