package edu.harvard.hms.dbmi.avillach.query.query;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import edu.harvard.hms.dbmi.avillach.query.consent.ConsentAuthorizationService;
import edu.harvard.hms.dbmi.avillach.query.operations.OperationsClient;
import edu.harvard.hms.dbmi.avillach.query.operations.SaveQueryRequest;
import edu.harvard.hms.dbmi.avillach.query.operations.StoredQuery;
import edu.harvard.hms.dbmi.avillach.query.operations.UpdateQueryRequest;

/**
 * Full-context MockMvc coverage of the sole query lifecycle ingress at {@code /hpds/{backend}/query/**}. It exercises
 * {@link HpdsQueryController} against a real {@link edu.harvard.hms.dbmi.avillach.query.hpds.HpdsBackendSelector}/
 * {@link edu.harvard.hms.dbmi.avillach.query.hpds.ResourceWebClient} pair, WireMock standing in for HPDS, and a Mockito
 * {@link OperationsClient} standing in for pic-sure-operations-service (this module is DB-free -- there is no embedded DB to run against).
 * The real {@code WebSecurityConfig} filter chain is exercised too (no mocked security), so the auth-required assertion is a genuine
 * end-to-end check.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class HpdsQueryControllerTest {

    private static final String USER = "auth0|alice";

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

    @MockitoBean
    private ConsentAuthorizationService consentAuthorization;

    @BeforeEach
    void resetStubs() {
        hpds.resetAll();
    }

    // --- create: stamps version "3" and hits the /v3 HPDS base ---

    @Test
    void queryPathNoLongerServesTheV3IngressPrefix() throws Exception {
        mockMvc.perform(
            post("/hpds/auth/v3/query").header(GatewayUserResolver.HEADER_USER_ID, USER).contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer caller-token").content("{\"query\":\"q\"}")
        ).andExpect(status().isNotFound());
    }

    @Test
    void unversionedQueryCreatesWithVersion3AndHitsV3Base() throws Exception {
        UUID picsureId = UUID.randomUUID();
        hpds.stubFor(
            WireMock.post(urlEqualTo("/PIC-SURE/v3/query")).willReturn(okJson("{\"resourceResultId\":\"rr-3\",\"status\":\"PENDING\"}"))
        );
        when(operationsClient.save(any())).thenReturn(picsureId);

        mockMvc.perform(
            post("/hpds/auth/query").header(GatewayUserResolver.HEADER_USER_ID, USER).contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer caller-token").content("{\"query\":\"q\"}")
        ).andExpect(status().isOk()).andExpect(jsonPath("$.resourceResultId").value("rr-3"));

        verify(consentAuthorization).scopeQuery(eq("auth"), any(), eq("Bearer caller-token"));
        verify(operationsClient).save(argThat((SaveQueryRequest r) -> "3".equals(r.version())));
        hpds.verify(postRequestedFor(urlEqualTo("/PIC-SURE/v3/query")));
    }

    @Test
    void unversionedSyncQueryForwardsCallerAuthorizationForConsentScoping() throws Exception {
        hpds.stubFor(
            WireMock.post(urlEqualTo("/PIC-SURE/v3/query/sync")).willReturn(aResponse().withStatus(200).withBody("{\"count\":1}"))
        );
        when(operationsClient.save(any())).thenReturn(UUID.randomUUID());

        mockMvc.perform(
            post("/hpds/auth/query/sync").header(GatewayUserResolver.HEADER_USER_ID, USER).contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer caller-token").content("{\"query\":\"q\"}")
        ).andExpect(status().isOk());

        verify(consentAuthorization).scopeQuery(eq("auth"), any(), eq("Bearer caller-token"));
    }

    private static final String V1_BODY = "{\"query\":{\"expectedResultType\":\"COUNT\",\"categoryFilters\":{\"\\\\sex\\\\\":[\"M\"]}}}";

    private static boolean isUpgradedRow(StoredQuery row, String resourceResultId) {
        return "3".equals(row.version()) && resourceResultId.equals(row.resourceResultId()) && row.query().contains("phenotypicClause");
    }

    private static boolean isUpgradePatch(UpdateQueryRequest patch, String resourceResultId) {
        return "3".equals(patch.version()) && resourceResultId.equals(patch.resourceResultId()) && patch.query() != null
            && patch.query().contains("phenotypicClause") && !patch.query().contains("categoryFilters");
    }

    private void stubV3Submit(String resourceResultId) {
        hpds.stubFor(
            WireMock.post(urlEqualTo("/PIC-SURE/v3/query"))
                .willReturn(okJson("{\"resourceResultId\":\"" + resourceResultId + "\",\"status\":\"PENDING\"}"))
        );
    }

    @Test
    void statusOfALegacyRowOnTheUnversionedPathUpgradesItAndPollsTheNewV3Query() throws Exception {
        UUID id = UUID.randomUUID();
        when(operationsClient.get(id)).thenReturn(new StoredQuery(id, V1_BODY, "rr-old", "AVAILABLE", null, null));
        stubV3Submit("rr-new");
        hpds.stubFor(
            WireMock.post(urlEqualTo("/PIC-SURE/v3/query/rr-new/status"))
                .willReturn(okJson("{\"resourceResultId\":\"rr-new\",\"status\":\"PENDING\"}"))
        );

        mockMvc
            .perform(
                post("/hpds/auth/query/{id}/status", id).header(GatewayUserResolver.HEADER_USER_ID, USER)
                    .header("Authorization", "Bearer caller-token").contentType(MediaType.APPLICATION_JSON).content("{}")
            ).andExpect(status().isOk()).andExpect(jsonPath("$.picsureResultId").value(id.toString()))
            .andExpect(jsonPath("$.resourceResultId").value("rr-new"));

        verify(consentAuthorization).scopeQuery(eq("auth"), any(), eq("Bearer caller-token"));
        verify(operationsClient).update(eq(id), argThat((UpdateQueryRequest u) -> isUpgradePatch(u, "rr-new")));
        hpds.verify(0, postRequestedFor(urlEqualTo("/PIC-SURE/v3/query/rr-old/status")));
    }

    @Test
    void resultOfALegacyRowOnTheUnversionedPathUpgradesItBeforeTheReadCheckAndServesTheNewResultId() throws Exception {
        UUID id = UUID.randomUUID();
        when(operationsClient.get(id)).thenReturn(new StoredQuery(id, V1_BODY, "rr-old", "AVAILABLE", null, null));
        stubV3Submit("rr-new");
        hpds.stubFor(
            WireMock.post(urlEqualTo("/PIC-SURE/v3/query/rr-new/result")).willReturn(aResponse().withStatus(200).withBody(new byte[] {9}))
        );

        mockMvc.perform(
            post("/hpds/auth/query/{id}/result", id).header(GatewayUserResolver.HEADER_USER_ID, USER)
                .header("Authorization", "Bearer caller-token").contentType(MediaType.APPLICATION_JSON).content("{}")
        ).andExpect(status().isOk());

        verify(operationsClient).update(eq(id), argThat((UpdateQueryRequest u) -> isUpgradePatch(u, "rr-new")));
        verify(consentAuthorization)
            .verifyReadAccess(eq("auth"), argThat((StoredQuery row) -> isUpgradedRow(row, "rr-new")), eq("Bearer caller-token"));
        hpds.verify(postRequestedFor(urlEqualTo("/PIC-SURE/v3/query/rr-new/result")));
    }

    @Test
    void signedUrlOfALegacyRowOnTheUnversionedPathUpgradesItBeforeTheReadCheckAndServesTheNewResultId() throws Exception {
        UUID id = UUID.randomUUID();
        when(operationsClient.get(id)).thenReturn(new StoredQuery(id, V1_BODY, "rr-old", "AVAILABLE", null, null));
        stubV3Submit("rr-new");
        hpds.stubFor(
            WireMock.post(urlEqualTo("/PIC-SURE/v3/query/rr-new/signed-url"))
                .willReturn(okJson("{\"url\":\"https://example.test/result\"}"))
        );

        mockMvc.perform(
            post("/hpds/auth/query/{id}/signed-url", id).header(GatewayUserResolver.HEADER_USER_ID, USER)
                .header("Authorization", "Bearer caller-token").contentType(MediaType.APPLICATION_JSON).content("{}")
        ).andExpect(status().isOk());

        verify(operationsClient).update(eq(id), argThat((UpdateQueryRequest u) -> isUpgradePatch(u, "rr-new")));
        verify(consentAuthorization)
            .verifyReadAccess(eq("auth"), argThat((StoredQuery row) -> isUpgradedRow(row, "rr-new")), eq("Bearer caller-token"));
    }

    @Test
    void untranslatableLegacyRowOnTheUnversionedPathIs422() throws Exception {
        UUID id = UUID.randomUUID();
        when(operationsClient.get(id)).thenReturn(new StoredQuery(id, "{\"query\":\"not an object\"}", "rr-old", "AVAILABLE", null, null));

        mockMvc.perform(
            post("/hpds/auth/query/{id}/result", id).header(GatewayUserResolver.HEADER_USER_ID, USER)
                .header("Authorization", "Bearer caller-token").contentType(MediaType.APPLICATION_JSON).content("{}")
        ).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errorType").value("untranslatable_query"));

        verify(operationsClient, never()).update(any(), any());
        hpds.verify(0, postRequestedFor(urlEqualTo("/PIC-SURE/v3/query")));
    }

    @Test
    void signedUrlOnTheUnversionedPathForwardsCallerAuthorizationForSavedConsentVerification() throws Exception {
        UUID id = UUID.randomUUID();
        StoredQuery stored = new StoredQuery(id, "{}", "rr-1", "AVAILABLE", "3", null);
        when(operationsClient.get(id)).thenReturn(stored);
        hpds.stubFor(
            WireMock.post(urlEqualTo("/PIC-SURE/v3/query/rr-1/signed-url")).willReturn(okJson("{\"url\":\"https://example.test/result\"}"))
        );

        mockMvc.perform(
            post("/hpds/auth/query/{id}/signed-url", id).header(GatewayUserResolver.HEADER_USER_ID, USER)
                .header("Authorization", "Bearer caller-token").contentType(MediaType.APPLICATION_JSON).content("{}")
        ).andExpect(status().isOk());

        verify(consentAuthorization).verifyReadAccess("auth", stored, "Bearer caller-token");
    }

    @Test
    void metadataOnTheUnversionedPathForwardsCallerAuthorizationForSavedConsentVerification() throws Exception {
        UUID id = UUID.randomUUID();
        StoredQuery stored = new StoredQuery(id, "{}", "rr-1", "AVAILABLE", "3", null);
        when(operationsClient.get(id)).thenReturn(stored);

        mockMvc.perform(
            get("/hpds/auth/query/{id}/metadata", id).header(GatewayUserResolver.HEADER_USER_ID, USER)
                .header("Authorization", "Bearer caller-token")
        ).andExpect(status().isOk());

        verify(consentAuthorization).verifyReadAccess("auth", stored, "Bearer caller-token");
    }

    @Test
    void metadataPostOnTheUnversionedPathForwardsCallerAuthorizationForSavedConsentVerification() throws Exception {
        UUID id = UUID.randomUUID();
        StoredQuery stored = new StoredQuery(id, "{}", "rr-1", "AVAILABLE", "3", null);
        when(operationsClient.get(id)).thenReturn(stored);

        mockMvc.perform(
            post("/hpds/auth/query/{id}/metadata", id).header(GatewayUserResolver.HEADER_USER_ID, USER)
                .header("Authorization", "Bearer caller-token")
        ).andExpect(status().isOk());

        verify(consentAuthorization).verifyReadAccess("auth", stored, "Bearer caller-token");
    }

    // --- upstream failures surface as 502, not 200/500 ---

    @Test
    void hpds500SurfacesAs502OnTheUnversionedPath() throws Exception {
        hpds.stubFor(WireMock.post(urlEqualTo("/PIC-SURE/v3/query")).willReturn(aResponse().withStatus(500)));

        mockMvc.perform(
            post("/hpds/auth/query").header(GatewayUserResolver.HEADER_USER_ID, USER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"q\"}")
        ).andExpect(status().isBadGateway()).andExpect(jsonPath("$.errorType").value("upstream_unavailable"));
    }

    // --- ?isInstitute=true is gone: federated/GIC queries are no longer supported ---
    //
    // The guard must stay until at least one release after removal. Jackson's defaultImpl silently
    // reinterprets a {"@type":"FederatedQueryRequest"} body as a GeneralQueryRequest, so isInstitute
    // is the ONLY remaining signal that a caller intended a federated submission. Without this guard
    // such a caller gets a 200 for a query stripped of its federation. Do not delete as dead code.

    @Test
    void isInstituteIsGoneOnTheUnversionedPath() throws Exception {
        mockMvc.perform(
            post("/hpds/auth/query").param("isInstitute", "true").header(GatewayUserResolver.HEADER_USER_ID, USER)
                .header(GatewayUserResolver.HEADER_USER_EMAIL, "alice@harvard.edu").contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"q\"}")
        ).andExpect(status().isGone()).andExpect(jsonPath("$.errorType").value("gone"));
    }

    // --- /hpds/** requires an authenticated caller ---

    @Test
    void queryWithoutGatewayIdentityIsRejected() throws Exception {
        mockMvc.perform(post("/hpds/auth/query").contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"q\"}"))
            .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(401, 403));
    }

    @Test
    void resultWithoutGatewayIdentityIsRejected() throws Exception {
        mockMvc.perform(post("/hpds/auth/query/{id}/result", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(401, 403));
    }
}
