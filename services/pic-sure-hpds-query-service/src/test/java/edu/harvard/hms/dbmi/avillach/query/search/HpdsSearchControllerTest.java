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

/**
 * Full-context MockMvc coverage of {@code /hpds/{backend}/search/**}. Every call resolves to the same {@code /v3} HPDS downstream URL,
 * since {@link edu.harvard.hms.dbmi.avillach.query.hpds.HpdsBackendSelector} always resolves the v3 base regardless of backend.
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
    void searchOnAuthBackendMapsToTheV3DownstreamUrl() throws Exception {
        hpds.stubFor(WireMock.post(urlEqualTo("/AUTH/v3/search")).willReturn(okJson("{\"searchQuery\":\"q\",\"results\":{}}")));

        mockMvc.perform(
            post("/hpds/auth/search").header(GatewayUserResolver.HEADER_USER_ID, USER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"q\"}")
        ).andExpect(status().isOk());

        hpds.verify(1, postRequestedFor(urlEqualTo("/AUTH/v3/search")));
    }

    @Test
    void searchOnOpenBackendResolvesToOpenUrl() throws Exception {
        hpds.stubFor(WireMock.post(urlEqualTo("/OPEN/v3/search")).willReturn(okJson("{\"searchQuery\":\"q\",\"results\":{}}")));

        mockMvc.perform(
            post("/hpds/open/search").header(GatewayUserResolver.HEADER_USER_ID, USER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"q\"}")
        ).andExpect(status().isOk());

        hpds.verify(postRequestedFor(urlEqualTo("/OPEN/v3/search")));
    }

    @Test
    void searchNeverHitsTheNonVersionedDownstreamUrl() throws Exception {
        hpds.stubFor(WireMock.post(urlEqualTo("/AUTH/v3/search")).willReturn(okJson("{\"searchQuery\":\"q\",\"results\":{}}")));

        mockMvc.perform(
            post("/hpds/auth/search").header(GatewayUserResolver.HEADER_USER_ID, USER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"q\"}")
        ).andExpect(status().isOk());

        hpds.verify(0, postRequestedFor(urlEqualTo("/AUTH/search")));
    }

    @Test
    void valuesEndpointMapsToTheV3DownstreamUrl() throws Exception {
        hpds.stubFor(
            WireMock.get(urlPathEqualTo("/AUTH/v3/search/values/")).withQueryParam("genomicConceptPath", equalTo("\\gene\\"))
                .withQueryParam("query", equalTo("BRCA")).willReturn(okJson("{\"results\":[],\"page\":1,\"total\":0}"))
        );

        mockMvc.perform(
            get("/hpds/auth/search/values").header(GatewayUserResolver.HEADER_USER_ID, USER).param("genomicConceptPath", "\\gene\\")
                .param("query", "BRCA")
        ).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));

        hpds.verify(1, getRequestedFor(urlPathEqualTo("/AUTH/v3/search/values/")));
    }

    @Test
    void searchSendsHpdsTheTermInTheSameEnvelopeAndIgnoresOtherMembers() throws Exception {
        hpds.stubFor(WireMock.post(urlEqualTo("/AUTH/v3/search")).willReturn(okJson("{\"results\":{},\"searchQuery\":\"age\"}")));

        mockMvc.perform(
            post("/hpds/auth/search").header(GatewayUserResolver.HEADER_USER_ID, USER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"resourceUUID\":\"8694e3d4-5cb4-410f-8431-993445e6d3f6\",\"query\":\"age\"}")
        ).andExpect(status().isOk()).andExpect(content().string("{\"results\":{},\"searchQuery\":\"age\"}"));

        hpds.verify(
            postRequestedFor(urlEqualTo("/AUTH/v3/search"))
                .withRequestBody(equalTo("{\"@type\":\"GeneralQueryRequest\",\"query\":\"age\",\"resourceUUID\":null}"))
        );
    }

    @Test
    void searchWithATermThatIsNotTextIs400() throws Exception {
        mockMvc.perform(
            post("/hpds/auth/search").header(GatewayUserResolver.HEADER_USER_ID, USER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":{\"expectedResultType\":\"COUNT\"}}")
        ).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorType").value("bad_request"));

        hpds.verify(0, postRequestedFor(urlEqualTo("/AUTH/v3/search")));
    }

    @Test
    void valuesBodyIsByteIdenticalToWhatHpdsSentAndARequestBodyIsStillAccepted() throws Exception {
        String fromHpds = "{\"results\":[\"APOB\",\"APOE\"],\"page\":1,\"total\":2}";
        hpds.stubFor(WireMock.get(urlPathEqualTo("/AUTH/v3/search/values/")).willReturn(okJson(fromHpds)));

        mockMvc.perform(
            get("/hpds/auth/search/values").header(GatewayUserResolver.HEADER_USER_ID, USER)
                .param("genomicConceptPath", "Gene_with_variant").param("query", "APO").param("page", "1").param("size", "20")
        ).andExpect(status().isOk()).andExpect(content().string(fromHpds));
        mockMvc.perform(
            get("/hpds/auth/search/values").header(GatewayUserResolver.HEADER_USER_ID, USER)
                .param("genomicConceptPath", "Gene_with_variant").param("query", "APO").contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"ignored\"}")
        ).andExpect(status().isOk()).andExpect(content().string(fromHpds));
    }

    @Test
    void hpdsFailureOnSearchSurfacesAs502() throws Exception {
        hpds.stubFor(WireMock.post(urlEqualTo("/AUTH/v3/search")).willReturn(aResponse().withStatus(500)));

        mockMvc.perform(
            post("/hpds/auth/search").header(GatewayUserResolver.HEADER_USER_ID, USER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"q\"}")
        ).andExpect(status().isBadGateway());
    }

    @Test
    void searchWithoutGatewayIdentityIsRejected() throws Exception {
        mockMvc.perform(post("/hpds/auth/search").contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"q\"}"))
            .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(401, 403));
    }
}
