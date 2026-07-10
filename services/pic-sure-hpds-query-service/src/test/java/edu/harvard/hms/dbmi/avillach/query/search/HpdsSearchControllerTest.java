package edu.harvard.hms.dbmi.avillach.query.search;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
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

/**
 * Full-context MockMvc coverage of {@code /hpds/{backend}[/v3]/search/**}. Search is NOT versioned downstream (matching the ported
 * {@code PicsureSearchService}): both the v1 and v3 ingress paths for a given backend must land on the SAME non-{@code /v3} HPDS URL.
 * {@code auth} and {@code open} are pointed at distinct paths on one WireMock instance so backend selection is verifiable without running
 * two servers.
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
    void searchOnAuthBackendMapsToSameDownstreamUrlForV1AndV3() throws Exception {
        UUID resourceId = UUID.randomUUID();
        hpds.stubFor(WireMock.post(urlEqualTo("/AUTH/search")).willReturn(okJson("{\"searchQuery\":\"q\",\"results\":{}}")));

        mockMvc.perform(
            post("/hpds/auth/search/{resourceId}", resourceId).header(GatewayUserResolver.HEADER_USER_ID, USER)
                .contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"q\"}")
        ).andExpect(status().isOk());

        mockMvc.perform(
            post("/hpds/auth/v3/search/{resourceId}", resourceId).header(GatewayUserResolver.HEADER_USER_ID, USER)
                .contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"q\"}")
        ).andExpect(status().isOk());

        hpds.verify(2, postRequestedFor(urlEqualTo("/AUTH/search"))); // same non-versioned URL both times
    }

    @Test
    void searchOnOpenBackendResolvesToOpenUrl() throws Exception {
        UUID resourceId = UUID.randomUUID();
        hpds.stubFor(WireMock.post(urlEqualTo("/OPEN/search")).willReturn(okJson("{\"searchQuery\":\"q\",\"results\":{}}")));

        mockMvc.perform(
            post("/hpds/open/search/{resourceId}", resourceId).header(GatewayUserResolver.HEADER_USER_ID, USER)
                .contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"q\"}")
        ).andExpect(status().isOk());

        hpds.verify(postRequestedFor(urlEqualTo("/OPEN/search")));
    }

    @Test
    void searchViaV3PathNeverHitsAVersionedDownstreamUrl() throws Exception {
        UUID resourceId = UUID.randomUUID();
        hpds.stubFor(WireMock.post(urlEqualTo("/AUTH/search")).willReturn(okJson("{\"searchQuery\":\"q\",\"results\":{}}")));

        mockMvc.perform(
            post("/hpds/auth/v3/search/{resourceId}", resourceId).header(GatewayUserResolver.HEADER_USER_ID, USER)
                .contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"q\"}")
        ).andExpect(status().isOk());

        hpds.verify(0, postRequestedFor(urlEqualTo("/AUTH/v3/search"))); // search is never versioned downstream
    }

    @Test
    void valuesEndpointMapsForBothV1AndV3OnAuthBackend() throws Exception {
        UUID resourceId = UUID.randomUUID();
        hpds.stubFor(
            WireMock.get(urlPathEqualTo("/AUTH/search/values/")).withQueryParam("genomicConceptPath", equalTo("\\gene\\"))
                .withQueryParam("query", equalTo("BRCA")).willReturn(okJson("{\"results\":[],\"page\":1,\"total\":0}"))
        );

        mockMvc.perform(
            get("/hpds/auth/search/{resourceId}/values/", resourceId).header(GatewayUserResolver.HEADER_USER_ID, USER)
                .param("genomicConceptPath", "\\gene\\").param("query", "BRCA")
        ).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));

        mockMvc.perform(
            get("/hpds/auth/v3/search/{resourceId}/values/", resourceId).header(GatewayUserResolver.HEADER_USER_ID, USER)
                .param("genomicConceptPath", "\\gene\\").param("query", "BRCA")
        ).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));

        hpds.verify(2, getRequestedFor(urlPathEqualTo("/AUTH/search/values/")));
    }

    @Test
    void hpdsFailureOnSearchSurfacesAs502() throws Exception {
        UUID resourceId = UUID.randomUUID();
        hpds.stubFor(WireMock.post(urlEqualTo("/AUTH/search")).willReturn(aResponse().withStatus(500)));

        mockMvc.perform(
            post("/hpds/auth/search/{resourceId}", resourceId).header(GatewayUserResolver.HEADER_USER_ID, USER)
                .contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"q\"}")
        ).andExpect(status().isBadGateway());
    }

    @Test
    void searchWithoutGatewayIdentityIsRejected() throws Exception {
        UUID resourceId = UUID.randomUUID();
        mockMvc.perform(
            post("/hpds/auth/search/{resourceId}", resourceId).contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"q\"}")
        ).andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(401, 403));
    }
}
