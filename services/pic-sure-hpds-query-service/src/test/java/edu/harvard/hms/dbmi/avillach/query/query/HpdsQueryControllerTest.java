package edu.harvard.hms.dbmi.avillach.query.query;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;

import edu.harvard.dbmi.avillach.domain.QueryStatus;
import edu.harvard.dbmi.avillach.domain.SaveQueryRequest;
import edu.harvard.dbmi.avillach.domain.SignedUrlResponse;
import edu.harvard.dbmi.avillach.domain.StoredQuery;
import edu.harvard.dbmi.avillach.domain.UpdateQueryRequest;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureExceptionAdvice;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUser;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUserResolver;
import edu.harvard.hms.dbmi.avillach.query.consent.ConsentAuthorizationService;
import edu.harvard.hms.dbmi.avillach.query.operations.OperationsClient;

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
    private static final String COUNT_BODY = "{\"query\":{\"expectedResultType\":\"COUNT\"}}";

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

    @MockitoSpyBean
    private QueryService queryService;

    @BeforeEach
    void resetStubs() {
        hpds.resetAll();
    }

    // --- create: stamps version "3" and hits the /v3 HPDS base ---

    @Test
    void queryPathNoLongerServesTheV3IngressPrefix() throws Exception {
        mockMvc.perform(
            post("/hpds/auth/v3/query").header(GatewayUserResolver.HEADER_USER_ID, USER).contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer caller-token").content(COUNT_BODY)
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
                .header("Authorization", "Bearer caller-token").content(COUNT_BODY)
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
                .header("Authorization", "Bearer caller-token").content(COUNT_BODY)
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
                get("/hpds/auth/query/{id}/status", id).header(GatewayUserResolver.HEADER_USER_ID, USER)
                    .header("Authorization", "Bearer caller-token")
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
            get("/hpds/auth/query/{id}/result", id).header(GatewayUserResolver.HEADER_USER_ID, USER)
                .header("Authorization", "Bearer caller-token")
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
                .willReturn(okJson("{\"signedUrl\":\"https://example.test/result\"}"))
        );

        mockMvc.perform(
            get("/hpds/auth/query/{id}/signed-url", id).header(GatewayUserResolver.HEADER_USER_ID, USER)
                .header("Authorization", "Bearer caller-token")
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
            get("/hpds/auth/query/{id}/result", id).header(GatewayUserResolver.HEADER_USER_ID, USER)
                .header("Authorization", "Bearer caller-token")
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
            WireMock.post(urlEqualTo("/PIC-SURE/v3/query/rr-1/signed-url"))
                .willReturn(okJson("{\"signedUrl\":\"https://example.test/result\"}"))
        );

        mockMvc.perform(
            get("/hpds/auth/query/{id}/signed-url", id).header(GatewayUserResolver.HEADER_USER_ID, USER)
                .header("Authorization", "Bearer caller-token")
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
    void malformedJsonIs400() throws Exception {
        mockMvc
            .perform(
                post("/hpds/auth/query").header(GatewayUserResolver.HEADER_USER_ID, USER).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"query\":")
            ).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorType").value("bad_request"))
            .andExpect(jsonPath("$.message").value(PicsureExceptionAdvice.BODY_UNREADABLE));
    }

    @Test
    void sampleDisabledBodyCarriesErrorType() throws Exception {
        mockMvc
            .perform(
                post("/hpds/auth/query").header(GatewayUserResolver.HEADER_USER_ID, USER).contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer caller-token")
                    .content("{\"query\":{\"expectedResultType\":\"DATAFRAME\",\"sample\":true}}")
            ).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorType").value("sample_disabled"))
            .andExpect(jsonPath("$.message").value("Sample datasets are not available at this time"));

        verify(consentAuthorization, never()).scopeQuery(any(), any(), any());
        verify(operationsClient, never()).save(any());
        hpds.verify(0, postRequestedFor(urlEqualTo("/PIC-SURE/v3/query")));
    }

    @Test
    void signedUrlBodyIsByteIdenticalToWhatHpdsSent() throws Exception {
        UUID id = UUID.randomUUID();
        String fromHpds = "{\"signedUrl\":\"https://pic-sure-exports.s3.amazonaws.com/rr-1.avro"
            + "?X-Amz-Expires=3600&X-Amz-Signature=5d41402abc4b2a76b9719d911017c592\"}";
        when(operationsClient.get(id)).thenReturn(new StoredQuery(id, "{}", "rr-1", "AVAILABLE", "3", null));
        hpds.stubFor(WireMock.post(urlEqualTo("/PIC-SURE/v3/query/rr-1/signed-url")).willReturn(okJson(fromHpds)));

        mockMvc
            .perform(
                get("/hpds/auth/query/{id}/signed-url", id).header(GatewayUserResolver.HEADER_USER_ID, USER)
                    .header("Authorization", "Bearer caller-token")
            ).andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_JSON)).andExpect(content().string(fromHpds))
            .andExpect(header().doesNotExist("Matched-Stub-Id"));
    }

    @Test
    void signedUrlOfAResultThatIsNotReadyKeepsHpdsStatusAndBody() throws Exception {
        UUID id = UUID.randomUUID();
        when(operationsClient.get(id)).thenReturn(new StoredQuery(id, "{}", "rr-1", "PENDING", "3", null));
        hpds.stubFor(
            WireMock.post(urlEqualTo("/PIC-SURE/v3/query/rr-1/signed-url"))
                .willReturn(aResponse().withStatus(400).withBody("Status : RUNNING"))
        );

        mockMvc.perform(
            get("/hpds/auth/query/{id}/signed-url", id).header(GatewayUserResolver.HEADER_USER_ID, USER)
                .header("Authorization", "Bearer caller-token")
        ).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Status : RUNNING"));
    }

    // --- upstream failures surface as 502, not 200/500 ---

    @Test
    void hpds500SurfacesAs502OnTheUnversionedPath() throws Exception {
        hpds.stubFor(WireMock.post(urlEqualTo("/PIC-SURE/v3/query")).willReturn(aResponse().withStatus(500)));

        mockMvc.perform(
            post("/hpds/auth/query").header(GatewayUserResolver.HEADER_USER_ID, USER).contentType(MediaType.APPLICATION_JSON)
                .content(COUNT_BODY)
        ).andExpect(status().isBadGateway()).andExpect(jsonPath("$.errorType").value("upstream_unavailable"));
    }

    // --- ?isInstitute=true is gone: federated/GIC queries are no longer supported ---
    //
    // The guard must stay until at least one release after removal. HpdsQueryRequest ignores @type and any other
    // unknown member, so a {"@type":"FederatedQueryRequest"} body binds as an ordinary query and isInstitute is the
    // ONLY remaining signal of federated intent. Do not delete as dead code.

    @Test
    void isInstituteIsGoneOnTheUnversionedPath() throws Exception {
        mockMvc.perform(
            post("/hpds/auth/query").param("isInstitute", "true").header(GatewayUserResolver.HEADER_USER_ID, USER)
                .header(GatewayUserResolver.HEADER_USER_EMAIL, "alice@harvard.edu").contentType(MediaType.APPLICATION_JSON)
                .content(COUNT_BODY)
        ).andExpect(status().isGone()).andExpect(jsonPath("$.errorType").value("gone"));
    }

    private static GatewayUser theCaller(String userId) {
        return argThat((GatewayUser user) -> user != null && userId.equals(user.getUserId()));
    }

    @Test
    void submitSyncStatusResultAndSignedUrlPassTheGatewayUser() throws Exception {
        String caller = "5f0c1f0e-6f0a-4a57-9d6e-0d1c6a3b9f11";
        String token = "Bearer caller-token";
        UUID id = UUID.randomUUID();
        doReturn(new QueryStatus()).when(queryService).query(any(), any(), any(), any());
        doReturn(new QueryService.QuerySyncResponse("1".getBytes(), null)).when(queryService).querySync(any(), any(), any(), any(), any());
        doReturn(new QueryStatus()).when(queryService).queryStatus(any(), any(), any(), any());
        doReturn(ResponseEntity.ok(new byte[] {1})).when(queryService).queryResult(any(), any(), any(), any());
        doReturn(new SignedUrlResponse("https://s3/x")).when(queryService).queryResultSignedUrl(any(), any(), any(), any());

        mockMvc.perform(
            post("/hpds/auth/query").header(GatewayUserResolver.HEADER_USER_ID, caller).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(COUNT_BODY)
        ).andExpect(status().isOk());
        mockMvc.perform(
            post("/hpds/auth/query/sync").header(GatewayUserResolver.HEADER_USER_ID, caller).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(COUNT_BODY)
        ).andExpect(status().isOk());
        mockMvc.perform(
            get("/hpds/auth/query/{id}/status", id).header(GatewayUserResolver.HEADER_USER_ID, caller).header("Authorization", token)
        ).andExpect(status().isOk());
        mockMvc.perform(
            get("/hpds/auth/query/{id}/result", id).header(GatewayUserResolver.HEADER_USER_ID, caller).header("Authorization", token)
        ).andExpect(status().isOk());
        mockMvc.perform(
            get("/hpds/auth/query/{id}/signed-url", id).header(GatewayUserResolver.HEADER_USER_ID, caller).header("Authorization", token)
        ).andExpect(status().isOk());

        verify(queryService).query(eq("auth"), any(), eq(token), theCaller(caller));
        verify(queryService).querySync(eq("auth"), any(), isNull(), eq(token), theCaller(caller));
        verify(queryService).queryStatus(eq("auth"), eq(id), eq(token), theCaller(caller));
        verify(queryService).queryResult(eq("auth"), eq(id), eq(token), theCaller(caller));
        verify(queryService).queryResultSignedUrl(eq("auth"), eq(id), eq(token), theCaller(caller));
    }

    @Test
    void submitByACallerIdentifiedByUuidStoresARowOwnedByThatCaller() throws Exception {
        UUID caller = UUID.fromString("5f0c1f0e-6f0a-4a57-9d6e-0d1c6a3b9f11");
        UUID fromTheClient = UUID.fromString("11111111-2222-4333-8444-555555555555");
        hpds.stubFor(WireMock.post(urlEqualTo("/PIC-SURE/v3/query")).willReturn(okJson("{\"status\":\"PENDING\"}")));
        when(operationsClient.find(any())).thenReturn(Optional.empty());

        String response = mockMvc.perform(
            post("/hpds/auth/query").header(GatewayUserResolver.HEADER_USER_ID, caller.toString())
                .header("Authorization", "Bearer caller-token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":{\"expectedResultType\":\"DATAFRAME\",\"picsureId\":\"" + fromTheClient + "\"}}")
        ).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        ArgumentCaptor<SaveQueryRequest> saved = ArgumentCaptor.forClass(SaveQueryRequest.class);
        verify(operationsClient).save(saved.capture());
        UUID id = saved.getValue().picsureId();
        assertThat(id).isNotNull().isNotEqualTo(fromTheClient);
        assertThat(id.version()).isEqualTo(5);
        assertThat(saved.getValue().userUuid()).isEqualTo(caller);
        assertThat(saved.getValue().version()).isEqualTo("3");
        assertThat(response).contains("\"picsureResultId\":\"" + id + "\"").doesNotContain(fromTheClient.toString())
            .doesNotContain(caller.toString());
        hpds.verify(
            postRequestedFor(urlEqualTo("/PIC-SURE/v3/query"))
                .withRequestBody(matchingJsonPath("$.query.picsureId", equalTo(id.toString())))
        );
    }

    @Test
    void submitThatHpdsAnswersWithoutAJobIs502AndStoresNothing() throws Exception {
        String caller = "5f0c1f0e-6f0a-4a57-9d6e-0d1c6a3b9f11";
        hpds.stubFor(WireMock.post(urlEqualTo("/PIC-SURE/v3/query")).willReturn(okJson("{\"resourceStatus\":\"Resource is locked.\"}")));
        when(operationsClient.find(any())).thenReturn(Optional.empty());

        String response = mockMvc
            .perform(
                post("/hpds/auth/query").header(GatewayUserResolver.HEADER_USER_ID, caller).header("Authorization", "Bearer caller-token")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"query\":{\"expectedResultType\":\"DATAFRAME\"}}")
            ).andExpect(status().isBadGateway()).andExpect(jsonPath("$.errorType").value("upstream_unavailable"))
            .andExpect(jsonPath("$.message").value("HPDS did not accept the query")).andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain("locked").doesNotContain(caller);
        verify(operationsClient, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"status", "result", "signed-url"})
    void aCallerWhoIsNotTheOwnerIsRefusedWhateverTheQueryStringTheOtherHeadersOrTheBodyClaim(String read) throws Exception {
        String owner = "5f0c1f0e-6f0a-4a57-9d6e-0d1c6a3b9f11";
        String caller = "0b6d6a0e-3c56-4c0b-9a38-0b1f5c1f3a77";
        UUID id = UUID.randomUUID();
        when(operationsClient.get(id)).thenReturn(
            new StoredQuery(id, "{}", id.toString(), "AVAILABLE", "3", null, 1L, null, UUID.fromString(owner), Map.of(), UUID.randomUUID())
        );

        String response = mockMvc
            .perform(
                get("/hpds/auth/query/{id}/" + read, id).header(GatewayUserResolver.HEADER_USER_ID, caller)
                    .header(GatewayUserResolver.HEADER_USER_SUBJECT, owner).header(GatewayUserResolver.HEADER_USER_EMAIL, owner)
                    .header("Authorization", "Bearer caller-token").queryParam("userUuid", owner).queryParam("userId", owner)
                    .queryParam("user", owner).queryParam("owner", owner).queryParam("X-User-Id", owner)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"userUuid\":\"" + owner + "\",\"userId\":\"" + owner + "\",\"owner\":\"" + owner + "\"}")
            ).andExpect(status().isForbidden()).andExpect(jsonPath("$.errorType").value("forbidden")).andReturn().getResponse()
            .getContentAsString();

        assertThat(response).doesNotContain(owner).doesNotContain(caller).doesNotContain(id.toString());
        verifyNoInteractions(consentAuthorization);
        verify(operationsClient, never()).save(any());
        verify(operationsClient, never()).update(any(), any());
        verify(operationsClient, never()).updateLatestRun(any(), any());
        hpds.verify(0, anyRequestedFor(anyUrl()));
    }

    // --- /hpds/** requires an authenticated caller ---

    @Test
    void queryWithoutGatewayIdentityIsRejected() throws Exception {
        mockMvc.perform(post("/hpds/auth/query").contentType(MediaType.APPLICATION_JSON).content(COUNT_BODY))
            .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(401, 403));
    }

    @Test
    void resultWithoutGatewayIdentityIsRejected() throws Exception {
        mockMvc.perform(get("/hpds/auth/query/{id}/result", UUID.randomUUID()))
            .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(401, 403));
    }
}
