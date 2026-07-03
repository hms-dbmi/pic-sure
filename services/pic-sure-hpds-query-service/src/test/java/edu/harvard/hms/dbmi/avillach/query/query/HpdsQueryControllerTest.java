package edu.harvard.hms.dbmi.avillach.query.query;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
 * Full-context MockMvc coverage of the {@code /hpds/{backend}[/v3]/query/**} ingress: {@link HpdsQueryV1Controller} and
 * {@link HpdsQueryV3Controller} against a real {@link edu.harvard.hms.dbmi.avillach.query.hpds.HpdsBackendSelector}/
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

    @BeforeEach
    void resetStubs() {
        hpds.resetAll();
    }

    // --- create: v1 (no version) vs v3 (stamps version "3", hits the /v3 HPDS base) ---

    @Test
    void v1QueryCreatesWithNoVersion() throws Exception {
        UUID picsureId = UUID.randomUUID();
        hpds.stubFor(
            WireMock.post(urlEqualTo("/PIC-SURE/query")).willReturn(okJson("{\"resourceResultId\":\"rr-1\",\"status\":\"PENDING\"}"))
        );
        when(operationsClient.save(any())).thenReturn(picsureId);

        mockMvc.perform(
            post("/hpds/auth/query").header(GatewayUserResolver.HEADER_USER_ID, USER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"q\"}")
        ).andExpect(status().isOk()).andExpect(jsonPath("$.resourceResultId").value("rr-1"));

        verify(operationsClient).save(argThat((SaveQueryRequest r) -> r.version() == null));
        hpds.verify(postRequestedFor(urlEqualTo("/PIC-SURE/query")));
    }

    @Test
    void v3QueryCreatesWithVersion3AndHitsV3Base() throws Exception {
        UUID picsureId = UUID.randomUUID();
        hpds.stubFor(
            WireMock.post(urlEqualTo("/PIC-SURE/v3/query")).willReturn(okJson("{\"resourceResultId\":\"rr-3\",\"status\":\"PENDING\"}"))
        );
        when(operationsClient.save(any())).thenReturn(picsureId);

        mockMvc.perform(
            post("/hpds/auth/v3/query").header(GatewayUserResolver.HEADER_USER_ID, USER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"q\"}")
        ).andExpect(status().isOk()).andExpect(jsonPath("$.resourceResultId").value("rr-3"));

        verify(operationsClient).save(argThat((SaveQueryRequest r) -> "3".equals(r.version())));
        hpds.verify(postRequestedFor(urlEqualTo("/PIC-SURE/v3/query")));
    }

    // --- read ops dispatch on the STORED version, regardless of which ingress path (v1 or v3) was used (decision 9) ---

    @Test
    void resultDispatchesOnStoredVersionEvenViaV1Path() throws Exception {
        UUID id = UUID.randomUUID();
        StoredQuery stored = new StoredQuery(id, "{}", "rr-9", "PENDING", "3", null); // v3-stored, requested via the v1 path
        when(operationsClient.get(id)).thenReturn(stored);
        hpds.stubFor(
            WireMock.post(urlEqualTo("/PIC-SURE/v3/query/rr-9/result"))
                .willReturn(aResponse().withStatus(200).withBody(new byte[] {1, 2, 3}))
        );

        mockMvc.perform(
            post("/hpds/auth/query/{id}/result", id).header(GatewayUserResolver.HEADER_USER_ID, USER)
                .contentType(MediaType.APPLICATION_JSON).content("{}")
        ).andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM));

        hpds.verify(postRequestedFor(urlEqualTo("/PIC-SURE/v3/query/rr-9/result"))); // NOT the v1 base
    }

    @Test
    void signedUrlDispatchesOnStoredVersionEvenViaV1Path() throws Exception { // THE decision-9 bug fix, asserted at controller level
        UUID id = UUID.randomUUID();
        StoredQuery stored = new StoredQuery(id, "{}", "rr-9", "PENDING", "3", null); // v3-stored, requested via the v1 path
        when(operationsClient.get(id)).thenReturn(stored);
        hpds.stubFor(WireMock.post(urlEqualTo("/PIC-SURE/v3/query/rr-9/signed-url")).willReturn(okJson("{\"url\":\"https://s3/x\"}")));

        mockMvc
            .perform(
                post("/hpds/auth/query/{id}/signed-url", id).header(GatewayUserResolver.HEADER_USER_ID, USER)
                    .contentType(MediaType.APPLICATION_JSON).content("{}")
            ).andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(content().string(containsString("https://s3/x")));

        hpds.verify(postRequestedFor(urlEqualTo("/PIC-SURE/v3/query/rr-9/signed-url"))); // NOT the v1 base
    }

    @Test
    void resultViaV3PathAlsoDispatchesOnStoredVersionNotIngressVersion() throws Exception {
        UUID id = UUID.randomUUID();
        StoredQuery stored = new StoredQuery(id, "{}", "rr-1", "PENDING", null, null); // v1-stored, requested via the v3 path
        when(operationsClient.get(id)).thenReturn(stored);
        hpds.stubFor(
            WireMock.post(urlEqualTo("/PIC-SURE/query/rr-1/result")).willReturn(aResponse().withStatus(200).withBody(new byte[] {9}))
        );

        mockMvc.perform(
            post("/hpds/auth/v3/query/{id}/result", id).header(GatewayUserResolver.HEADER_USER_ID, USER)
                .contentType(MediaType.APPLICATION_JSON).content("{}")
        ).andExpect(status().isOk());

        hpds.verify(postRequestedFor(urlEqualTo("/PIC-SURE/query/rr-1/result"))); // NOT /v3
    }

    // --- upstream failures surface as 502, not 200/500 ---

    @Test
    void hpds500SurfacesAs502() throws Exception {
        hpds.stubFor(WireMock.post(urlEqualTo("/PIC-SURE/query")).willReturn(aResponse().withStatus(500)));

        mockMvc.perform(
            post("/hpds/auth/query").header(GatewayUserResolver.HEADER_USER_ID, USER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"q\"}")
        ).andExpect(status().isBadGateway()).andExpect(jsonPath("$.errorType").value("upstream_unavailable"));
    }

    // --- the ?isInstitute=true GIC branch ---

    @Test
    void isInstituteBranchAttachesGicMetadata() throws Exception {
        UUID picsureId = UUID.randomUUID();
        hpds.stubFor(
            WireMock.post(urlEqualTo("/PIC-SURE/query")).willReturn(okJson("{\"resourceResultId\":\"rr-gic\",\"status\":\"PENDING\"}"))
        );
        when(operationsClient.save(any())).thenReturn(picsureId);

        mockMvc.perform(
            post("/hpds/auth/query").param("isInstitute", "true").header(GatewayUserResolver.HEADER_USER_ID, USER)
                .header(GatewayUserResolver.HEADER_USER_EMAIL, "alice@harvard.edu").contentType(MediaType.APPLICATION_JSON)
                .content("{\"@type\":\"FederatedQueryRequest\",\"query\":\"q\"}")
        ).andExpect(status().isOk());

        verify(operationsClient).save(argThat((SaveQueryRequest r) -> r.metadata() != null)); // GIC metadata (site/email) attached
    }

    @Test
    void isInstituteBranchOnV3ControllerStampsVersion3() throws Exception {
        UUID picsureId = UUID.randomUUID();
        hpds.stubFor(
            WireMock.post(urlEqualTo("/PIC-SURE/v3/query")).willReturn(okJson("{\"resourceResultId\":\"rr-gic3\",\"status\":\"PENDING\"}"))
        );
        when(operationsClient.save(any())).thenReturn(picsureId);

        mockMvc.perform(
            post("/hpds/auth/v3/query").param("isInstitute", "true").header(GatewayUserResolver.HEADER_USER_ID, USER)
                .header(GatewayUserResolver.HEADER_USER_EMAIL, "alice@harvard.edu").contentType(MediaType.APPLICATION_JSON)
                .content("{\"@type\":\"FederatedQueryRequest\",\"query\":\"q\"}")
        ).andExpect(status().isOk());

        verify(operationsClient).save(argThat((SaveQueryRequest r) -> "3".equals(r.version()) && r.metadata() != null));
        hpds.verify(postRequestedFor(urlEqualTo("/PIC-SURE/v3/query")));
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

    // --- sync ---

    @Test
    void v1QuerySyncReturnsOctetStreamWithMetadataHeader() throws Exception {
        UUID picsureId = UUID.randomUUID();
        when(operationsClient.save(any())).thenReturn(picsureId);
        hpds.stubFor(
            WireMock.post(urlEqualTo("/PIC-SURE/query/sync"))
                .willReturn(aResponse().withStatus(200).withHeader("queryMetadata", "rr-sync").withBody("payload"))
        );

        mockMvc
            .perform(
                post("/hpds/auth/query/sync").header(GatewayUserResolver.HEADER_USER_ID, USER).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"query\":\"q\"}")
            ).andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM))
            .andExpect(content().bytes("payload".getBytes()));

        verify(operationsClient).update(eq(picsureId), argThat(u -> "rr-sync".equals(u.resourceResultId())));
    }
}
