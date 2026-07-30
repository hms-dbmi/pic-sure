package edu.harvard.hms.dbmi.avillach.query.query;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;

import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUserResolver;
import edu.harvard.hms.dbmi.avillach.query.operations.OperationsClient;
import edu.harvard.hms.dbmi.avillach.query.operations.SaveQueryRequest;
import edu.harvard.hms.dbmi.avillach.query.operations.StoredQuery;

/**
 * Full-context MockMvc coverage of the {@code /hpds/{backend}/v3/query/**} ingress: {@link HpdsQueryV3Controller} (the sole query lifecycle
 * ingress) against a real {@link edu.harvard.hms.dbmi.avillach.query.hpds.HpdsBackendSelector}/
 * {@link edu.harvard.hms.dbmi.avillach.query.hpds.ResourceWebClient} pair, WireMock standing in for HPDS, and a Mockito
 * {@link OperationsClient} standing in for pic-sure-operations-service (this module is DB-free -- there is no embedded DB to run against).
 * The real {@code WebSecurityConfig} filter chain is exercised too (no mocked security), so the auth-required assertion is a genuine
 * end-to-end check.
 *
 * <p>The ingress binds the BARE v3 {@code Query} contract: no {@code QueryRequest} envelope, no {@code resourceUUID}, no
 * {@code resourceCredentials}. Read ops ({@code status}/{@code result}/{@code signed-url}/{@code metadata}) carry no request body at all --
 * the stored query is the only input they need.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class HpdsQueryControllerTest {

    private static final String USER = "auth0|alice";
    /** A bare v3 Query body: the exact shape the gateway now forwards after consent mutation. */
    private static final String BARE_V3_QUERY = "{\"select\":[\"\\\\age\\\\\"],\"expectedResultType\":\"COUNT\"}";

    static WireMockServer hpds;

    @BeforeAll
    static void start() {
        hpds = new WireMockServer(options().dynamicPort().http2PlainDisabled(true));
        hpds.start();
    }

    @AfterAll
    static void stop() {
        hpds.stop();
    }

    @DynamicPropertySource
    static void hpdsProps(DynamicPropertyRegistry registry) {
        registry.add("hpds.auth-url", () -> "http://localhost:" + hpds.port() + "/PIC-SURE");
        registry.add("hpds.open-url", () -> "http://localhost:" + hpds.port() + "/PIC-SURE");
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OperationsClient operationsClient;

    @BeforeEach
    void resetStubs() {
        hpds.resetAll();
    }

    // --- create: bare v3 Query in, QueryStatusResponse out ---

    @Test
    void v3QueryBindsABareQueryAndReturnsTheTypedStatusResponse() throws Exception {
        UUID picsureId = UUID.randomUUID();
        hpds.stubFor(
            WireMock.post(urlEqualTo("/PIC-SURE/v3/query")).willReturn(okJson("{\"resourceResultId\":\"rr-3\",\"status\":\"PENDING\"}"))
        );
        when(operationsClient.save(any())).thenReturn(picsureId);

        mockMvc
            .perform(
                post("/hpds/auth/v3/query").header(GatewayUserResolver.HEADER_USER_ID, USER).contentType(MediaType.APPLICATION_JSON)
                    .content(BARE_V3_QUERY)
            ).andExpect(status().isOk()).andExpect(jsonPath("$.resourceResultId").value("rr-3"))
            .andExpect(jsonPath("$.picsureId").value(picsureId.toString())).andExpect(jsonPath("$.status").value("PENDING"))
            // the resourceUUID echo is GONE: picsureId is the only identity in the response
            .andExpect(jsonPath("$.resourceID").doesNotExist());

        verify(operationsClient).save(argThat((SaveQueryRequest r) -> "3".equals(r.version())));
        hpds.verify(postRequestedFor(urlEqualTo("/PIC-SURE/v3/query")));
    }

    /**
     * Strict deserialization (pic-sure-spring-commons' {@code StrictWebDeserializationConfig}) is what makes the bare contract enforceable:
     * a caller still sending the old envelope's {@code resourceUUID} gets an immediate 400 rather than a 200 with the field silently
     * dropped.
     */
    @Test
    void envelopeFieldsOnTheBareQueryBodyAreRejectedWith400() throws Exception {
        mockMvc.perform(
            post("/hpds/auth/v3/query").header(GatewayUserResolver.HEADER_USER_ID, USER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"select\":[],\"resourceUUID\":\"" + UUID.randomUUID() + "\"}")
        ).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorType").value("bad_request"));

        hpds.verify(0, postRequestedFor(urlEqualTo("/PIC-SURE/v3/query"))); // rejected before any downstream call
    }

    @Test
    void syncBindsABareQueryAndForwardsItToHpds() throws Exception {
        hpds.stubFor(
            WireMock.post(urlEqualTo("/PIC-SURE/v3/query/sync"))
                .willReturn(aResponse().withStatus(200).withHeader("queryMetadata", "rr-sync").withBody("42"))
        );
        when(operationsClient.save(any())).thenReturn(UUID.randomUUID());

        mockMvc
            .perform(
                post("/hpds/auth/v3/query/sync").header(GatewayUserResolver.HEADER_USER_ID, USER).contentType(MediaType.APPLICATION_JSON)
                    .content(BARE_V3_QUERY)
            ).andExpect(status().isOk())
            .andExpect(result -> assertThat(result.getResponse().getHeader("queryMetadata")).isEqualTo("rr-sync"));

        // the bare Query is forwarded under the downstream envelope's query field (Task 7 retypes this hop)
        hpds.verify(postRequestedFor(urlEqualTo("/PIC-SURE/v3/query/sync")).withRequestBody(matchingJsonPath("$.query.select")));
    }

    // --- read ops: bodyless ---

    @Test
    void statusIsAGetWithNoBody() throws Exception {
        UUID id = UUID.randomUUID();
        when(operationsClient.get(id)).thenReturn(new StoredQuery(id, "{}", "rr-9", "PENDING", "3", null));
        hpds.stubFor(
            WireMock.post(urlEqualTo("/PIC-SURE/v3/query/rr-9/status"))
                .willReturn(okJson("{\"resourceResultId\":\"rr-9\",\"status\":\"AVAILABLE\"}"))
        );

        mockMvc.perform(get("/hpds/auth/v3/query/{id}/status", id).header(GatewayUserResolver.HEADER_USER_ID, USER))
            .andExpect(status().isOk()).andExpect(jsonPath("$.picsureId").value(id.toString()))
            .andExpect(jsonPath("$.status").value("AVAILABLE"));
    }

    /** The POST form of /status is gone (breaking, intended): only GET is mapped. */
    @Test
    void postToStatusIsNoLongerMapped() throws Exception {
        mockMvc.perform(
            post("/hpds/auth/v3/query/{id}/status", UUID.randomUUID()).header(GatewayUserResolver.HEADER_USER_ID, USER)
                .contentType(MediaType.APPLICATION_JSON).content("{}")
        ).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void resultTakesNoRequestBodyAndDispatchesOnTheStoredVersion() throws Exception {
        UUID id = UUID.randomUUID();
        when(operationsClient.get(id)).thenReturn(new StoredQuery(id, "{}", "rr-1", "PENDING", null, null)); // v1-stored, v3 ingress
        hpds.stubFor(
            WireMock.post(urlEqualTo("/PIC-SURE/query/rr-1/result")).willReturn(aResponse().withStatus(200).withBody(new byte[] {9}))
        );

        mockMvc.perform(post("/hpds/auth/v3/query/{id}/result", id).header(GatewayUserResolver.HEADER_USER_ID, USER))
            .andExpect(status().isOk());

        hpds.verify(postRequestedFor(urlEqualTo("/PIC-SURE/query/rr-1/result"))); // NOT /v3: stored version decides
    }

    @Test
    void signedUrlReturnsTheTypedSignedUrlResponse() throws Exception {
        UUID id = UUID.randomUUID();
        when(operationsClient.get(id)).thenReturn(new StoredQuery(id, "{}", "rr-5", "AVAILABLE", "3", null));
        hpds.stubFor(
            WireMock.post(urlEqualTo("/PIC-SURE/v3/query/rr-5/signed-url"))
                .willReturn(okJson("{\"signedUrl\":\"https://s3/results/rr-5?sig=abc\"}"))
        );

        mockMvc.perform(post("/hpds/auth/v3/query/{id}/signed-url", id).header(GatewayUserResolver.HEADER_USER_ID, USER))
            .andExpect(status().isOk()).andExpect(jsonPath("$.signedUrl").value("https://s3/results/rr-5?sig=abc"));
    }

    @Test
    void metadataReturnsTheTypedStatusResponseWithoutCallingHpds() throws Exception {
        UUID id = UUID.randomUUID();
        when(operationsClient.get(id))
            .thenReturn(new StoredQuery(id, "{\"query\":{\"expectedResultType\":\"COUNT\"}}", "rr-2", "AVAILABLE", "3", null));

        mockMvc.perform(get("/hpds/auth/v3/query/{id}/metadata", id).header(GatewayUserResolver.HEADER_USER_ID, USER))
            .andExpect(status().isOk()).andExpect(jsonPath("$.picsureId").value(id.toString()))
            .andExpect(jsonPath("$.status").value("AVAILABLE")).andExpect(jsonPath("$.resultMetadata.queryJson").exists());

        // metadata is DB-only: any HPDS call would have hit an unstubbed WireMock and surfaced as a 502 above.
        hpds.verify(0, anyRequestedFor(anyUrl()));
    }

    // --- upstream failures surface as 502, not 200/500 ---

    @Test
    void hpds500SurfacesAs502() throws Exception {
        hpds.stubFor(WireMock.post(urlEqualTo("/PIC-SURE/v3/query")).willReturn(aResponse().withStatus(500)));

        mockMvc.perform(
            post("/hpds/auth/v3/query").header(GatewayUserResolver.HEADER_USER_ID, USER).contentType(MediaType.APPLICATION_JSON)
                .content(BARE_V3_QUERY)
        ).andExpect(status().isBadGateway()).andExpect(jsonPath("$.errorType").value("upstream_unavailable"));
    }

    /**
     * {@code ?isInstitute} and its 410 guard are GONE. The federated envelope no longer exists at all -- there is no {@code QueryRequest}
     * subtype for Jackson to silently reinterpret, so there is nothing left to guard against. An unknown query param is simply ignored.
     */
    @Test
    void isInstituteParamIsNoLongerRejected() throws Exception {
        UUID picsureId = UUID.randomUUID();
        hpds.stubFor(
            WireMock.post(urlEqualTo("/PIC-SURE/v3/query")).willReturn(okJson("{\"resourceResultId\":\"rr-3\",\"status\":\"PENDING\"}"))
        );
        when(operationsClient.save(any())).thenReturn(picsureId);

        mockMvc.perform(
            post("/hpds/auth/v3/query").param("isInstitute", "true").header(GatewayUserResolver.HEADER_USER_ID, USER)
                .contentType(MediaType.APPLICATION_JSON).content(BARE_V3_QUERY)
        ).andExpect(status().isOk());
    }

    // --- /hpds/** requires an authenticated caller ---

    @Test
    void queryWithoutGatewayIdentityIsRejected() throws Exception {
        mockMvc.perform(post("/hpds/auth/v3/query").contentType(MediaType.APPLICATION_JSON).content(BARE_V3_QUERY))
            .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(401, 403));
    }

    @Test
    void resultWithoutGatewayIdentityIsRejected() throws Exception {
        mockMvc.perform(post("/hpds/auth/v3/query/{id}/result", UUID.randomUUID()))
            .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(401, 403));
    }

    // --- the legacy v1 ingress routes were removed ---

    @Test
    void legacyV1QueryRouteIsGone() throws Exception {
        mockMvc.perform(
            post("/hpds/auth/query").header(GatewayUserResolver.HEADER_USER_ID, USER).contentType(MediaType.APPLICATION_JSON)
                .content(BARE_V3_QUERY)
        ).andExpect(status().isNotFound());
    }
}
