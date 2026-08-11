package edu.harvard.hms.dbmi.avillach.query.search;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

/**
 * Full-context MockMvc coverage of {@code /hpds/{backend}/v3/search/**}. The ingress is v3-only and typed ({@code SearchRequest} in,
 * {@code PaginatedResponse<String>} out of {@code /search/values}), and so is the downstream hop: HPDS serves {@code /search} and
 * {@code /search/values/} only under {@code PIC-SURE/v3} now that its v1 controller is gone, so the ingress must land on the {@code /v3}
 * HPDS URL. {@code auth} and {@code open} are pointed at distinct paths on one WireMock instance so backend selection is verifiable without
 * running two servers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class HpdsSearchControllerTest {

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
        registry.add("hpds.auth-url", () -> "http://localhost:" + hpds.port() + "/AUTH");
        registry.add("hpds.open-url", () -> "http://localhost:" + hpds.port() + "/OPEN");
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OperationsClient operationsClient; // unused by search, present so the whole context (incl. query controllers) wires cleanly

    @BeforeEach
    void resetStubs() {
        hpds.resetAll();
    }

    @Test
    void searchBindsTheTypedRequestAndHitsTheV3DownstreamUrl() throws Exception {
        hpds.stubFor(WireMock.post(urlEqualTo("/AUTH/v3/search")).willReturn(okJson("{\"searchQuery\":\"q\",\"results\":{}}")));

        mockMvc.perform(
            post("/hpds/auth/v3/search").header(GatewayUserResolver.HEADER_USER_ID, USER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"BRCA\"}")
        ).andExpect(status().isOk());

        // the typed SearchRequest goes straight down, and it goes to the v3 URL -- HPDS no longer serves unversioned /search
        hpds.verify(postRequestedFor(urlEqualTo("/AUTH/v3/search")).withRequestBody(equalToJson("{\"query\":\"BRCA\"}", true, true)));
        hpds.verify(0, postRequestedFor(urlEqualTo("/AUTH/search")));
    }

    @Test
    void searchOnOpenBackendResolvesToOpenUrl() throws Exception {
        hpds.stubFor(WireMock.post(urlEqualTo("/OPEN/v3/search")).willReturn(okJson("{\"searchQuery\":\"q\",\"results\":{}}")));

        mockMvc.perform(
            post("/hpds/open/v3/search").header(GatewayUserResolver.HEADER_USER_ID, USER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"BRCA\"}")
        ).andExpect(status().isOk());

        hpds.verify(postRequestedFor(urlEqualTo("/OPEN/v3/search")));
    }

    /** Strict deserialization: a body carrying anything but the modelled {@code query} field is a 400, not a silently-dropped field. */
    @Test
    void unknownFieldsOnTheSearchRequestAreRejectedWith400() throws Exception {
        mockMvc.perform(
            post("/hpds/auth/v3/search").header(GatewayUserResolver.HEADER_USER_ID, USER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"BRCA\",\"resourceUUID\":\"00000000-0000-0000-0000-000000000000\"}")
        ).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorType").value("bad_request"));

        hpds.verify(0, postRequestedFor(urlEqualTo("/AUTH/v3/search")));
    }

    @Test
    void valuesIsAPureQueryParamGetReturningTheTypedPage() throws Exception {
        hpds.stubFor(
            WireMock.get(urlPathEqualTo("/AUTH/v3/search/values/")).withQueryParam("genomicConceptPath", equalTo("\\gene\\"))
                .withQueryParam("query", equalTo("BRCA")).willReturn(okJson("{\"results\":[\"BRCA1\"],\"page\":1,\"total\":1}"))
        );

        mockMvc.perform(
            get("/hpds/auth/v3/search/values").header(GatewayUserResolver.HEADER_USER_ID, USER).param("genomicConceptPath", "\\gene\\")
                .param("query", "BRCA")
        ).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.results[0]").value("BRCA1"));

        hpds.verify(getRequestedFor(urlPathEqualTo("/AUTH/v3/search/values/")));
    }

    // --- the legacy v1 search ingress routes are gone ---

    @Test
    void legacyV1SearchRouteIsGone() throws Exception {
        mockMvc.perform(
            post("/hpds/auth/search").header(GatewayUserResolver.HEADER_USER_ID, USER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"BRCA\"}")
        ).andExpect(status().isNotFound());
    }

    @Test
    void legacyV1SearchValuesRouteIsGone() throws Exception {
        mockMvc.perform(
            get("/hpds/auth/search/values").header(GatewayUserResolver.HEADER_USER_ID, USER).param("genomicConceptPath", "\\gene\\")
        ).andExpect(status().isNotFound());
    }

    @Test
    void hpdsFailureOnSearchSurfacesAs502() throws Exception {
        hpds.stubFor(WireMock.post(urlEqualTo("/AUTH/v3/search")).willReturn(aResponse().withStatus(500)));

        mockMvc.perform(
            post("/hpds/auth/v3/search").header(GatewayUserResolver.HEADER_USER_ID, USER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"BRCA\"}")
        ).andExpect(status().isBadGateway());
    }

    @Test
    void searchWithoutGatewayIdentityIsRejected() throws Exception {
        mockMvc.perform(post("/hpds/auth/v3/search").contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"BRCA\"}"))
            .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(401, 403));
    }
}
