package edu.harvard.hms.dbmi.avillach.query.aggregate;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;

import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUserResolver;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsCommunicationException;
import edu.harvard.hms.dbmi.avillach.query.operations.OperationsClient;

/**
 * MockMvc coverage of {@link AggregateV3Controller} (the v3 obfuscation ingress at {@code /hpds/open/v3/query/sync}), mirroring
 * {@link AggregateControllerTest}: {@link AggregateService} is mocked (obfuscation logic itself lives in {@link AggregateServiceTest}), and
 * the point of this class is the routing/coexistence behavior with
 * {@link edu.harvard.hms.dbmi.avillach.query.query.HpdsQueryV3Controller}'s generic {@code /hpds/{backend}/v3/query/sync} mapping.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class AggregateV3ControllerTest {

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
        registry.add("consent.based.authorization.enabled", () -> false);
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AggregateService aggregateService;

    @MockitoBean
    private OperationsClient operationsClient;

    @BeforeEach
    void resetStubs() {
        hpds.resetAll();
    }

    @Test
    void openV3QuerySyncRoutesToAggregateServiceV3AndReturnsResult() throws Exception {
        when(aggregateService.querySync(any(QueryRequest.class), eq(AggregateVariant.V3)))
            .thenReturn(ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body("{}"));

        mockMvc
            .perform(
                post("/hpds/open/v3/query/sync").header(GatewayUserResolver.HEADER_USER_ID, USER)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"query\":{\"expectedResultType\":\"CROSS_COUNT\"}}")
            ).andExpect(status().isOk()).andExpect(content().string("{}"));

        verify(aggregateService).querySync(any(QueryRequest.class), eq(AggregateVariant.V3));
        verifyNoInteractions(operationsClient);
        hpds.verify(0, WireMock.postRequestedFor(urlEqualTo("/PIC-SURE/v3/query/sync")));
    }

    @Test
    void authV3QuerySyncIsNotInterceptedByAggregateV3Controller() throws Exception {
        hpds.stubFor(
            WireMock.post(urlEqualTo("/PIC-SURE/v3/query/sync"))
                .willReturn(aResponse().withStatus(200).withHeader("queryMetadata", "rr-3").withBody("payload"))
        );

        mockMvc.perform(
            post("/hpds/auth/v3/query/sync").header(GatewayUserResolver.HEADER_USER_ID, USER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"q\"}")
        ).andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_JSON));

        verifyNoInteractions(aggregateService);
        hpds.verify(WireMock.postRequestedFor(urlEqualTo("/PIC-SURE/v3/query/sync")));
    }

    @Test
    void openV3QuerySyncWithoutGatewayIdentityIsUnauthorized() throws Exception {
        mockMvc.perform(post("/hpds/open/v3/query/sync").contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"q\"}"))
            .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(401, 403));

        verifyNoInteractions(aggregateService);
    }

    @Test
    void openV3QuerySyncUpstreamErrorSurfacesAs502() throws Exception {
        when(aggregateService.querySync(any(QueryRequest.class), eq(AggregateVariant.V3)))
            .thenThrow(new HpdsCommunicationException("Aggregate query/sync call failed", new RuntimeException("boom")));

        mockMvc
            .perform(
                post("/hpds/open/v3/query/sync").header(GatewayUserResolver.HEADER_USER_ID, USER)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"query\":{\"expectedResultType\":\"CROSS_COUNT\"}}")
            ).andExpect(status().isBadGateway());
    }
}
