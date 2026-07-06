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
 * MockMvc coverage of {@link AggregateController} (the v1 obfuscation ingress at {@code /hpds/open/query/sync}), with
 * {@link AggregateService} replaced by a Mockito mock (the obfuscation logic itself is exhaustively covered by
 * {@link AggregateServiceTest}) -- this class exists to prove the CONTROLLER WIRING and, critically, the coexistence with
 * {@link edu.harvard.hms.dbmi.avillach.query.query.HpdsQueryV1Controller}'s generic {@code /hpds/{backend}/query/sync} mapping: a literal
 * {@code /hpds/open/query/sync} request must be dispatched to this controller (and therefore through obfuscation), while
 * {@code /hpds/auth/query/sync} must NOT be -- it must fall through to the generic, path-variable controller untouched.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class AggregateControllerTest {

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
        // Backs the GENERIC HpdsQueryV1Controller/QueryService path (used by the "auth" coexistence assertion below); the
        // AggregateService bean itself is mocked, so aggregate.* properties are irrelevant to this test class.
        registry.add("hpds.auth-url", () -> "http://localhost:" + hpds.port() + "/PIC-SURE");
        registry.add("hpds.open-url", () -> "http://localhost:" + hpds.port() + "/PIC-SURE");
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
    void openQuerySyncRoutesToAggregateServiceObfuscationAndReturnsResult() throws Exception {
        when(aggregateService.querySync(any(QueryRequest.class), eq(AggregateVariant.V1)))
            .thenReturn(ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body("< 10"));

        mockMvc
            .perform(
                post("/hpds/open/query/sync").header(GatewayUserResolver.HEADER_USER_ID, USER).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"query\":{\"expectedResultType\":\"COUNT\"}}")
            ).andExpect(status().isOk()).andExpect(content().string("< 10"));

        verify(aggregateService).querySync(any(QueryRequest.class), eq(AggregateVariant.V1));
        verifyNoInteractions(operationsClient); // the aggregate path never touches operations-service persistence
        hpds.verify(0, WireMock.postRequestedFor(urlEqualTo("/PIC-SURE/query/sync"))); // never reached the generic HPDS backend
    }

    @Test
    void authQuerySyncIsNotInterceptedByAggregateController() throws Exception {
        hpds.stubFor(
            WireMock.post(urlEqualTo("/PIC-SURE/query/sync"))
                .willReturn(aResponse().withStatus(200).withHeader("queryMetadata", "rr-1").withBody("payload"))
        );

        mockMvc.perform(
            post("/hpds/auth/query/sync").header(GatewayUserResolver.HEADER_USER_ID, USER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"q\"}")
        ).andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_JSON));

        verifyNoInteractions(aggregateService); // the aggregate obfuscation controller was never invoked
        hpds.verify(WireMock.postRequestedFor(urlEqualTo("/PIC-SURE/query/sync"))); // handled by the generic HpdsQueryV1Controller
    }

    @Test
    void openQuerySyncWithoutGatewayIdentityIsUnauthorized() throws Exception {
        mockMvc.perform(post("/hpds/open/query/sync").contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"q\"}"))
            .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(401, 403));

        verifyNoInteractions(aggregateService);
    }

    @Test
    void openQuerySyncUpstreamErrorSurfacesAs502() throws Exception {
        when(aggregateService.querySync(any(QueryRequest.class), eq(AggregateVariant.V1)))
            .thenThrow(new HpdsCommunicationException("Aggregate query/sync call failed", new RuntimeException("boom")));

        mockMvc
            .perform(
                post("/hpds/open/query/sync").header(GatewayUserResolver.HEADER_USER_ID, USER).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"query\":{\"expectedResultType\":\"COUNT\"}}")
            ).andExpect(status().isBadGateway());
    }
}
