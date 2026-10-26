package edu.harvard.hms.dbmi.avillach.query.search;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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

import edu.harvard.hms.dbmi.avillach.commons.security.InternalTokenFilter;
import edu.harvard.hms.dbmi.avillach.query.operations.OperationsClient;

/**
 * Full-context MockMvc coverage of {@code POST /internal/search/{backend}} with an inbound token configured: the token gate rejects a
 * missing or wrong header, and a correct header reaches {@link SearchService} and the same {@code /v3} HPDS downstream URL the removed
 * public mapping used.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class InternalSearchControllerTest {

    private static final String TOKEN = "inbound-secret";

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
    static void props(DynamicPropertyRegistry registry) {
        registry.add("hpds.auth-url", () -> "http://localhost:" + hpds.port() + "/AUTH");
        registry.add("hpds.open-url", () -> "http://localhost:" + hpds.port() + "/OPEN");
        registry.add("picsure.internal.token", () -> TOKEN);
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OperationsClient operationsClient;

    @BeforeEach
    void resetStubs() {
        hpds.resetAll();
    }

    @Test
    void missingTokenIsForbiddenAndNeverReachesHpds() throws Exception {
        mockMvc.perform(post("/internal/search/auth").contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"q\"}"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorType").value("FORBIDDEN"));

        hpds.verify(0, postRequestedFor(urlEqualTo("/AUTH/v3/search")));
    }

    @Test
    void wrongTokenIsForbiddenAndNeverReachesHpds() throws Exception {
        mockMvc.perform(
            post("/internal/search/auth").header(InternalTokenFilter.HEADER, "not-the-secret").contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"q\"}")
        ).andExpect(status().isForbidden());

        hpds.verify(0, postRequestedFor(urlEqualTo("/AUTH/v3/search")));
    }

    @Test
    void correctTokenOnAuthBackendDelegatesToTheV3DownstreamUrl() throws Exception {
        hpds.stubFor(WireMock.post(urlEqualTo("/AUTH/v3/search")).willReturn(okJson("{\"searchQuery\":\"q\",\"results\":{}}")));

        mockMvc.perform(
            post("/internal/search/auth").header(InternalTokenFilter.HEADER, TOKEN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"q\"}")
        ).andExpect(status().isOk()).andExpect(jsonPath("$.searchQuery").value("q"));

        hpds.verify(1, postRequestedFor(urlEqualTo("/AUTH/v3/search")));
        hpds.verify(0, postRequestedFor(urlEqualTo("/AUTH/search")));
    }

    @Test
    void correctTokenOnOpenBackendResolvesToOpenUrl() throws Exception {
        hpds.stubFor(WireMock.post(urlEqualTo("/OPEN/v3/search")).willReturn(okJson("{\"searchQuery\":\"q\",\"results\":{}}")));

        mockMvc.perform(
            post("/internal/search/open").header(InternalTokenFilter.HEADER, TOKEN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"q\"}")
        ).andExpect(status().isOk());

        hpds.verify(1, postRequestedFor(urlEqualTo("/OPEN/v3/search")));
    }

    @Test
    void searchSendsHpdsTheTermInTheOutboundEnvelopeAndIgnoresOtherMembers() throws Exception {
        hpds.stubFor(WireMock.post(urlEqualTo("/AUTH/v3/search")).willReturn(okJson("{\"results\":{},\"searchQuery\":\"age\"}")));

        mockMvc.perform(
            post("/internal/search/auth").header(InternalTokenFilter.HEADER, TOKEN).contentType(MediaType.APPLICATION_JSON)
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
            post("/internal/search/auth").header(InternalTokenFilter.HEADER, TOKEN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":{\"expectedResultType\":\"COUNT\"}}")
        ).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorType").value("bad_request"));

        hpds.verify(0, postRequestedFor(urlEqualTo("/AUTH/v3/search")));
    }

    @Test
    void hpdsFailureSurfacesAs502() throws Exception {
        hpds.stubFor(WireMock.post(urlEqualTo("/AUTH/v3/search")).willReturn(aResponse().withStatus(500)));

        mockMvc.perform(
            post("/internal/search/auth").header(InternalTokenFilter.HEADER, TOKEN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"q\"}")
        ).andExpect(status().isBadGateway());
    }
}
