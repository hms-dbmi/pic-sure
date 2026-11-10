package edu.harvard.hms.dbmi.avillach.query;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
 * Reproduces every 403, 405, 409, 502, and 504 the OpenAPI document declares, one case per endpoint, through the real controllers,
 * services, and HTTP clients. A single WireMock server stands in for HPDS ({@code /PIC-SURE}), the aggregate open backend ({@code /OPEN}),
 * PSAMA's consent lookup, and operations-service's internal query store, so each status comes from the production throw site rather than a
 * mocked bean. The 503 cases need an unconfigured backend and live in {@link UnconfiguredBackendResponsesTest}, and the 503 for a stored
 * query another request keeps busy is reproduced in {@code QueryServiceOwnedReadConcurrencyTest}.
 *
 * <p>The metadata read declares no 403 and no consent failure. Its cases here send the requests that would draw those answers from the
 * other reads and expect 200, with no call to the consent lookup.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class DocumentedErrorResponsesTest {

    static final String USER = "auth0|alice";
    static final String CALLER_TOKEN = "Bearer caller-token";
    static final String QUERIES = "/operations/internal/queries";
    static final String CONSENTS = "/auth/user/me/consents";
    static final String CONSENT_PATH = "\\_consents\\";
    static final String GRANTED_CONSENT = "phs000001.c1";
    static final String QUERY_BODY = "{\"query\":{\"expectedResultType\":\"COUNT\"}}";
    static final int SLOWER_THAN_READ_TIMEOUT_MS = 1_500;
    static final String OWNER = "5f0c1f0e-6f0a-4a57-9d6e-0d1c6a3b9f11";
    static final String ANOTHER_USER = "0b6d6a0e-3c56-4c0b-9a38-0b1f5c1f3a77";
    static final String OWNED_RUN = "7c1b1f0e-2a3b-4c4d-8e5f-0a1b2c3d4e5f";
    static final String NOT_READY_MESSAGE =
        "The result is not ready. Poll the status of the query and ask again once it reports AVAILABLE.";

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

    private final UUID ownedId = UUID.randomUUID();

    @BeforeEach
    void healthyDownstream() throws JsonProcessingException {
        downstream.resetAll();
        grantConsents(Map.of(CONSENT_PATH, List.of(GRANTED_CONSENT)));
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

    /** Endpoints whose consent check returns 403 when the caller's current consents do not cover the query. */
    static Stream<Arguments> consentChecked() {
        return Stream.of(
            endpoint("submit", id -> authorized(post("/hpds/auth/query")).content(QUERY_BODY)),
            endpoint("sync", id -> authorized(post("/hpds/auth/query/sync")).content(QUERY_BODY)),
            endpoint("result", id -> authorized(get("/hpds/auth/query/{id}/result", id))),
            endpoint("signed-url", id -> authorized(get("/hpds/auth/query/{id}/signed-url", id)))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("consentChecked")
    void consentThatNoLongerCoversTheQueryIs403(String name, RequestFor request) throws Exception {
        grantConsents(Map.of("\\_topmed_consents\\", List.of(GRANTED_CONSENT)));

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

    @Test
    void metadataOfAnOwnedQueryAnswersAnotherUserWhoHoldsNoneOfItsConsents() throws Exception {
        storeOwnedQuery(Map.of(CONSENT_PATH, List.of(GRANTED_CONSENT)));
        grantConsents(Map.of("\\_topmed_consents\\", List.of("phs000009.c9")));

        String body = mockMvc.perform(calledBy(get("/hpds/auth/query/{id}/metadata", ownedId), ANOTHER_USER)).andExpect(status().isOk())
            .andExpect(jsonPath("$.picsureResultId").value(ownedId.toString()))
            .andExpect(jsonPath("$.resultMetadata.queryJson.query.expectedResultType").value("DATAFRAME"))
            .andExpect(jsonPath("$.resultMetadata.queryJson.query.authorizationFilters").isEmpty()).andReturn().getResponse()
            .getContentAsString();

        assertThat(body).doesNotContain(OWNER).doesNotContain(GRANTED_CONSENT).doesNotContain(OWNED_RUN).doesNotContain("userUuid")
            .doesNotContain("runId").doesNotContain("consents");
        verifyMetadataAskedOnlyForTheStoredQuery();
    }

    @Test
    void metadataOfAQueryStoredWithoutAnOwnerComesBackWithoutItsAuthorizationFiltersWhileConsentLookupIsDown() throws Exception {
        downstream.stubFor(WireMock.get(urlPathEqualTo(CONSENTS)).willReturn(aResponse().withStatus(500)));

        String body = mockMvc.perform(identified(get("/hpds/auth/query/{id}/metadata", storedId))).andExpect(status().isOk())
            .andExpect(jsonPath("$.picsureResultId").value(storedId.toString()))
            .andExpect(jsonPath("$.resultMetadata.queryJson.query.expectedResultType").value("COUNT"))
            .andExpect(jsonPath("$.resultMetadata.queryJson.query.authorizationFilters").isArray())
            .andExpect(jsonPath("$.resultMetadata.queryJson.query.authorizationFilters").isEmpty()).andReturn().getResponse()
            .getContentAsString();

        assertThat(body).doesNotContain(GRANTED_CONSENT).doesNotContain("_consents");
        verifyMetadataAskedOnlyForTheStoredQuery();
    }

    @Test
    void metadataWithoutAGatewayIdentityIsRefusedAndLoadsNothing() throws Exception {
        mockMvc.perform(get("/hpds/auth/query/{id}/metadata", storedId).header(HttpHeaders.AUTHORIZATION, CALLER_TOKEN))
            .andExpect(status().isForbidden());

        downstream.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()));
    }

    private static void verifyMetadataAskedOnlyForTheStoredQuery() {
        downstream.verify(1, WireMock.anyRequestedFor(WireMock.anyUrl()));
        downstream.verify(1, WireMock.getRequestedFor(urlPathMatching(QUERIES + "/.*")));
        downstream.verify(0, WireMock.getRequestedFor(urlPathEqualTo(CONSENTS)));
    }

    /** Endpoints that call HPDS through {@code ResourceWebClient}, where an HPDS 5xx becomes 502. */
    static Stream<Arguments> hpdsBacked() {
        return Stream.of(
            endpoint("submit", id -> authorized(post("/hpds/auth/query")).content(QUERY_BODY)),
            endpoint("sync", id -> authorized(post("/hpds/auth/query/sync")).content(QUERY_BODY)),
            endpoint("status", id -> authorized(get("/hpds/auth/query/{id}/status", id))),
            endpoint("result", id -> authorized(get("/hpds/auth/query/{id}/result", id))),
            endpoint("signed-url", id -> authorized(get("/hpds/auth/query/{id}/signed-url", id))),
            endpoint("search", id -> authorized(post("/hpds/auth/search")).content("{\"query\":\"age\"}")),
            endpoint("search values", id -> identified(get("/hpds/auth/search/values")).param("genomicConceptPath", "\\gene\\")),
            endpoint("open submit", id -> authorized(post("/hpds/open/query")).content(QUERY_BODY))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("hpdsBacked")
    void hpdsServerErrorIs502(String name, RequestFor request) throws Exception {
        downstream.stubFor(WireMock.any(urlPathMatching("/PIC-SURE/.*")).willReturn(aResponse().withStatus(500)));

        mockMvc.perform(request.build(storedId)).andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.errorType").value("upstream_unavailable"));
    }

    /** Endpoints that ask HPDS for the result of a stored query, where HPDS answers 409 while the job is still queued or running. */
    static Stream<Arguments> resultReads() {
        return Stream.of(
            endpoint("result", id -> authorized(get("/hpds/auth/query/{id}/result", id))),
            endpoint("signed-url", id -> authorized(get("/hpds/auth/query/{id}/signed-url", id)))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("resultReads")
    void hpdsResultNotReadyIs409(String name, RequestFor request) throws Exception {
        downstream.stubFor(
            WireMock.post(urlPathMatching("/PIC-SURE/.*query/rr-1/(result|signed-url)"))
                .willReturn(aResponse().withStatus(409).withHeader("Content-Type", "text/plain").withBody("Status : RUNNING"))
        );

        mockMvc.perform(request.build(storedId)).andExpect(status().isConflict())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.errorType").value("result_not_ready")).andExpect(jsonPath("$.message").value(NOT_READY_MESSAGE));
    }

    /** The three reads of a stored query that has an owner, sent by the caller the test names. */
    static Stream<Arguments> ownedReads() {
        return Stream.of(
            endpoint("status", id -> get("/hpds/auth/query/{id}/status", id)),
            endpoint("result", id -> get("/hpds/auth/query/{id}/result", id)),
            endpoint("signed-url", id -> get("/hpds/auth/query/{id}/signed-url", id))
        );
    }

    static Stream<Arguments> ownedResultReads() {
        return Stream.of(
            endpoint("result", id -> get("/hpds/auth/query/{id}/result", id)),
            endpoint("signed-url", id -> get("/hpds/auth/query/{id}/signed-url", id))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ownedReads")
    void ownedQueryReadByAnotherUserIs403BeforeAnyConsentLookupOrHpdsCall(String name, RequestFor request) throws Exception {
        storeOwnedQuery(Map.of(CONSENT_PATH, List.of(GRANTED_CONSENT)));

        String body = mockMvc.perform(calledBy(request.build(ownedId), ANOTHER_USER)).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorType").value("forbidden"))
            .andExpect(jsonPath("$.message").value("Only the user who submitted this query can read it")).andReturn().getResponse()
            .getContentAsString();

        assertThat(body).doesNotContain(OWNER).doesNotContain(GRANTED_CONSENT);
        downstream.verify(0, WireMock.getRequestedFor(urlPathEqualTo(CONSENTS)));
        downstream.verify(0, WireMock.anyRequestedFor(urlPathMatching("/PIC-SURE/.*")));
        downstream.verify(0, WireMock.postRequestedFor(urlPathEqualTo(QUERIES)));
        downstream.verify(0, WireMock.patchRequestedFor(urlPathMatching(QUERIES + "/.*")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ownedReads")
    void ownedQueryReadByAnOwnerWithNoConsentsIs403(String name, RequestFor request) throws Exception {
        storeOwnedQuery(Map.of(CONSENT_PATH, List.of(GRANTED_CONSENT)));
        grantConsents(Map.of());

        mockMvc.perform(calledBy(request.build(ownedId), OWNER)).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorType").value("consent_denied"));

        downstream.verify(0, WireMock.anyRequestedFor(urlPathMatching("/PIC-SURE/.*")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ownedResultReads")
    void ownedResultRunAgainAfterAConsentChangeIs409AndServesNothing(String name, RequestFor request) throws Exception {
        storeOwnedQuery(Map.of(CONSENT_PATH, List.of(GRANTED_CONSENT, "phs000002.c1")));
        hpdsAcceptsTheSubmit();

        String body = mockMvc.perform(calledBy(request.build(ownedId), OWNER)).andExpect(status().isConflict())
            .andExpect(jsonPath("$.errorType").value("result_not_ready")).andExpect(jsonPath("$.message").value(NOT_READY_MESSAGE))
            .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(OWNER).doesNotContain("phs00000");
        downstream.verify(
            1,
            WireMock.postRequestedFor(urlPathEqualTo("/PIC-SURE/v3/query"))
                .withRequestBody(WireMock.matchingJsonPath("$.query.picsureId", WireMock.equalTo(ownedId.toString())))
                .withRequestBody(WireMock.matchingJsonPath("$.query.authorizationFilters[0].values[0]", WireMock.equalTo(GRANTED_CONSENT)))
        );
        downstream.verify(
            1,
            WireMock.postRequestedFor(urlPathEqualTo(QUERIES))
                .withRequestBody(WireMock.matchingJsonPath("$.trigger", WireMock.equalTo("CONSENT_CHANGE")))
                .withRequestBody(WireMock.matchingJsonPath("$.userUuid", WireMock.equalTo(OWNER)))
                .withRequestBody(WireMock.containing(GRANTED_CONSENT)).withRequestBody(WireMock.notContaining("phs000002.c1"))
        );
        downstream.verify(0, WireMock.postRequestedFor(urlPathMatching("/PIC-SURE/.*/(result|signed-url)")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ownedResultReads")
    void ownedResultThatHpdsNoLongerHoldsIsRunAgainAndIs409(String name, RequestFor request) throws Exception {
        storeOwnedQuery(Map.of(CONSENT_PATH, List.of(GRANTED_CONSENT)));
        hpdsAcceptsTheSubmit();
        downstream
            .stubFor(WireMock.post(urlPathEqualTo("/PIC-SURE/v3/query/" + ownedId + "/status")).willReturn(aResponse().withStatus(404)));

        mockMvc.perform(calledBy(request.build(ownedId), OWNER)).andExpect(status().isConflict())
            .andExpect(jsonPath("$.errorType").value("result_not_ready"));

        downstream.verify(1, WireMock.postRequestedFor(urlPathEqualTo("/PIC-SURE/v3/query")));
        downstream.verify(
            1,
            WireMock.postRequestedFor(urlPathEqualTo(QUERIES))
                .withRequestBody(WireMock.matchingJsonPath("$.trigger", WireMock.equalTo("LOST_RESULT")))
        );
        downstream.verify(0, WireMock.postRequestedFor(urlPathMatching("/PIC-SURE/.*/(result|signed-url)")));
    }

    @Test
    void ownedStatusOfAResultHpdsNoLongerHoldsRunsItAgainAndReportsTheNewRun() throws Exception {
        storeOwnedQuery(Map.of(CONSENT_PATH, List.of(GRANTED_CONSENT)));
        hpdsAcceptsTheSubmit();
        downstream
            .stubFor(WireMock.post(urlPathEqualTo("/PIC-SURE/v3/query/" + ownedId + "/status")).willReturn(aResponse().withStatus(404)));

        String body = mockMvc.perform(calledBy(get("/hpds/auth/query/{id}/status", ownedId), OWNER)).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PENDING")).andExpect(jsonPath("$.picsureResultId").value(ownedId.toString())).andReturn()
            .getResponse().getContentAsString();

        assertThat(body).doesNotContain(OWNER).doesNotContain(GRANTED_CONSENT).doesNotContain("consents");
        downstream.verify(
            1,
            WireMock.postRequestedFor(urlPathEqualTo(QUERIES))
                .withRequestBody(WireMock.matchingJsonPath("$.trigger", WireMock.equalTo("LOST_RESULT")))
        );
    }

    private void hpdsHoldsNoJobForTheOwnedQuery() {
        downstream
            .stubFor(WireMock.post(urlPathEqualTo("/PIC-SURE/v3/query/" + ownedId + "/status")).willReturn(aResponse().withStatus(404)));
    }

    private void verifyNothingWasRunSavedOrServed() {
        downstream.verify(0, WireMock.postRequestedFor(urlPathEqualTo("/PIC-SURE/v3/query")));
        downstream.verify(0, WireMock.postRequestedFor(urlPathEqualTo(QUERIES)));
        downstream.verify(0, WireMock.patchRequestedFor(urlPathMatching(QUERIES + "/.*")));
        downstream.verify(0, WireMock.postRequestedFor(urlPathMatching("/PIC-SURE/.*/(result|signed-url)")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ownedResultReads")
    void ownedResultOfAQueryStoredAsFailedIs400AndRunsNothing(String name, RequestFor request) throws Exception {
        storeOwnedQuery(Map.of(CONSENT_PATH, List.of(GRANTED_CONSENT)), "ERROR");
        hpdsAcceptsTheSubmit();
        hpdsHoldsNoJobForTheOwnedQuery();

        String body = mockMvc.perform(calledBy(request.build(ownedId), OWNER)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorType").value("bad_request")).andExpect(jsonPath("$.message").value("Status : ERROR")).andReturn()
            .getResponse().getContentAsString();

        assertThat(body).doesNotContain(OWNER).doesNotContain(GRANTED_CONSENT);
        verifyNothingWasRunSavedOrServed();
    }

    @Test
    void ownedStatusOfAQueryStoredAsFailedReportsTheFailureAndRunsNothing() throws Exception {
        storeOwnedQuery(Map.of(CONSENT_PATH, List.of(GRANTED_CONSENT)), "ERROR");
        hpdsAcceptsTheSubmit();
        hpdsHoldsNoJobForTheOwnedQuery();

        String body = mockMvc.perform(calledBy(get("/hpds/auth/query/{id}/status", ownedId), OWNER)).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ERROR")).andExpect(jsonPath("$.picsureResultId").value(ownedId.toString()))
            .andExpect(jsonPath("$.resourceResultId").value(ownedId.toString())).andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(OWNER).doesNotContain(GRANTED_CONSENT).doesNotContain("consents");
        verifyNothingWasRunSavedOrServed();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ownedResultReads")
    void ownedResultRunAgainThatHpdsAnswersAsFailedIs400(String name, RequestFor request) throws Exception {
        storeOwnedQuery(Map.of(CONSENT_PATH, List.of(GRANTED_CONSENT, "phs000002.c1")));
        downstream.stubFor(
            WireMock.post(urlPathEqualTo("/PIC-SURE/v3/query"))
                .willReturn(okJson("{\"resourceResultId\":\"" + ownedId + "\",\"resourceStatus\":\"ERROR\",\"status\":\"ERROR\"}"))
        );

        mockMvc.perform(calledBy(request.build(ownedId), OWNER)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorType").value("bad_request")).andExpect(jsonPath("$.message").value("Status : ERROR"));

        downstream.verify(1, WireMock.postRequestedFor(urlPathEqualTo("/PIC-SURE/v3/query")));
        downstream.verify(
            1,
            WireMock.postRequestedFor(urlPathEqualTo(QUERIES))
                .withRequestBody(WireMock.matchingJsonPath("$.trigger", WireMock.equalTo("CONSENT_CHANGE")))
                .withRequestBody(WireMock.matchingJsonPath("$.status", WireMock.equalTo("ERROR")))
        );
        downstream.verify(0, WireMock.postRequestedFor(urlPathMatching("/PIC-SURE/.*/(result|signed-url)")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ownedReads")
    void ownedReadWhoseStatusRequestHpdsRefusesIs502AndRunsNothing(String name, RequestFor request) throws Exception {
        storeOwnedQuery(Map.of(CONSENT_PATH, List.of(GRANTED_CONSENT)));
        hpdsAcceptsTheSubmit();
        downstream.stubFor(
            WireMock.post(urlPathEqualTo("/PIC-SURE/v3/query/" + ownedId + "/status"))
                .willReturn(aResponse().withStatus(403).withBody("what HPDS said"))
        );

        String body = mockMvc.perform(calledBy(request.build(ownedId), OWNER)).andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.errorType").value("upstream_unavailable"))
            .andExpect(jsonPath("$.message").value("HPDS refused to report the status of the query")).andReturn().getResponse()
            .getContentAsString();

        assertThat(body).doesNotContain(OWNER).doesNotContain(GRANTED_CONSENT).doesNotContain("what HPDS said");
        verifyNothingWasRunSavedOrServed();
    }

    @Test
    void ownedStatusWriteRefusedForAnOlderRunIsStillA200() throws Exception {
        storeOwnedQuery(Map.of(CONSENT_PATH, List.of(GRANTED_CONSENT)));
        downstream.stubFor(
            WireMock.post(urlPathEqualTo("/PIC-SURE/v3/query/" + ownedId + "/status"))
                .willReturn(okJson("{\"resourceResultId\":\"" + ownedId + "\",\"status\":\"AVAILABLE\"}"))
        );
        downstream.stubFor(
            WireMock.patch(urlPathEqualTo(QUERIES + "/" + ownedId)).willReturn(
                aResponse().withStatus(409).withHeader("Content-Type", "application/json")
                    .withBody("{\"errorType\":\"conflict\",\"message\":\"stale\",\"requestId\":null}")
            )
        );

        mockMvc.perform(calledBy(get("/hpds/auth/query/{id}/status", ownedId), OWNER)).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("AVAILABLE"));

        downstream.verify(
            1,
            WireMock.patchRequestedFor(urlPathEqualTo(QUERIES + "/" + ownedId))
                .withRequestBody(WireMock.matchingJsonPath("$.runId", WireMock.equalTo(OWNED_RUN)))
        );
    }

    /** The obfuscated sync endpoints call the open backend through {@code AggregateBackendClient}, not {@code ResourceWebClient}. */
    static Stream<Arguments> aggregateSync() {
        return Stream.of(endpoint("open sync", id -> authorized(post("/hpds/open/query/sync")).content(QUERY_BODY)));
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
            endpoint("status", id -> authorized(get("/hpds/auth/query/{id}/status", id))),
            endpoint("result", id -> authorized(get("/hpds/auth/query/{id}/result", id))),
            endpoint("signed-url", id -> authorized(get("/hpds/auth/query/{id}/signed-url", id))),
            endpoint("metadata", id -> authorized(get("/hpds/auth/query/{id}/metadata", id)))
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

        mockMvc.perform(authorized(get("/hpds/auth/query/{id}/status", storedId))).andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.errorType").value("bad_gateway"));
    }

    /** Endpoints that persist a new query through operations-service after HPDS accepts it. */
    static Stream<Arguments> persistingSubmits() {
        return Stream.of(
            endpoint("submit", id -> authorized(post("/hpds/auth/query")).content(QUERY_BODY)),
            endpoint("sync", id -> authorized(post("/hpds/auth/query/sync")).content(QUERY_BODY)),
            endpoint("open submit", id -> authorized(post("/hpds/open/query")).content(QUERY_BODY))
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

    /** Stored-query reads that are GET only, so a POST to them is answered with 405 and an {@code Allow} header. */
    static Stream<Arguments> getOnlyStoredQueryReads() {
        return Stream.of(
            endpoint("status", id -> authorized(post("/hpds/auth/query/{id}/status", id))),
            endpoint("result", id -> authorized(post("/hpds/auth/query/{id}/result", id))),
            endpoint("signed-url", id -> authorized(post("/hpds/auth/query/{id}/signed-url", id))),
            endpoint("metadata", id -> authorized(post("/hpds/auth/query/{id}/metadata", id)))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("getOnlyStoredQueryReads")
    void postToAGetOnlyStoredQueryReadIs405WithAnAllowHeader(String name, RequestFor request) throws Exception {
        mockMvc.perform(request.build(storedId)).andExpect(status().isMethodNotAllowed())
            .andExpect(header().string(HttpHeaders.ALLOW, containsString("GET")))
            .andExpect(jsonPath("$.errorType").value("method_not_allowed"));
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

    /** Sends the request as the given gateway user, with the caller's bearer token for the consent lookup. */
    static MockHttpServletRequestBuilder calledBy(MockHttpServletRequestBuilder request, String userId) {
        return request.header(GatewayUserResolver.HEADER_USER_ID, userId).header(HttpHeaders.AUTHORIZATION, CALLER_TOKEN);
    }

    /**
     * Stubs the operations-service row of a query {@link #OWNER} submitted, as an owned submit stores it: a body with no authorization
     * filters, the row id as the result id, the consents it ran under and a latest run.
     */
    private void storeOwnedQuery(Map<String, List<String>> consents) throws JsonProcessingException {
        storeOwnedQuery(consents, "AVAILABLE");
    }

    /** Stubs the row of {@link #storeOwnedQuery(Map)} with the given stored status. */
    private void storeOwnedQuery(Map<String, List<String>> consents, String status) throws JsonProcessingException {
        Map<String, Object> request =
            Map.of("query", Map.of("expectedResultType", "DATAFRAME", "picsureId", ownedId.toString(), "authorizationFilters", List.of()));
        Map<String, Object> row = Map.of(
            "picsureId", ownedId.toString(), "query", MAPPER.writeValueAsString(request), "resourceResultId", ownedId.toString(), "status",
            status, "version", "3", "userUuid", OWNER, "consents", consents, "runId", OWNED_RUN
        );
        downstream.stubFor(WireMock.get(urlPathEqualTo(QUERIES + "/" + ownedId)).willReturn(okJson(MAPPER.writeValueAsString(row))));
    }

    /** HPDS accepts a submit under the id it is given and answers without naming another one. */
    private static void hpdsAcceptsTheSubmit() {
        downstream.stubFor(WireMock.post(urlPathEqualTo("/PIC-SURE/v3/query")).willReturn(okJson("{\"status\":\"PENDING\"}")));
    }

    private static void grantConsents(Map<String, List<String>> consents) throws JsonProcessingException {
        downstream.stubFor(WireMock.get(urlPathEqualTo(CONSENTS)).willReturn(okJson(consentsJson(consents))));
    }

    /** PSAMA's {@code /user/me/consents} body granting {@code consents}, keyed by consent concept path. */
    static String consentsJson(Map<String, List<String>> consents) throws JsonProcessingException {
        return MAPPER.writeValueAsString(Map.of("consents", consents));
    }

    /**
     * The operations-service row for a v3 query that was scoped to {@code consentValues} under {@code \_consents\} when it was submitted.
     */
    static String storedQueryJson(UUID id, List<String> consentValues) throws JsonProcessingException {
        Map<String, Object> filter = Map.of("conceptPath", CONSENT_PATH, "values", consentValues);
        Map<String, Object> request = Map.of("query", Map.of("expectedResultType", "COUNT", "authorizationFilters", List.of(filter)));
        Map<String, Object> row = Map.of(
            "picsureId", id.toString(), "query", MAPPER.writeValueAsString(request), "resourceResultId", "rr-1", "status", "PENDING",
            "version", "3"
        );
        return MAPPER.writeValueAsString(row);
    }
}
