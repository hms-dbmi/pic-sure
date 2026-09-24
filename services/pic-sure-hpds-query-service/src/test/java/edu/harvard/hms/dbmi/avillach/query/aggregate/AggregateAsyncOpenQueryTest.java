package edu.harvard.hms.dbmi.avillach.query.aggregate;

import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
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

/**
 * Full-context coverage of async open-path consent scoping using the real {@link AggregateService},
 * {@link edu.harvard.hms.dbmi.avillach.query.query.QueryService}, HpdsBackendSelector, and ResourceWebClient. WireMock stands in for the
 * open HPDS backend (the {@code /search} study-consents lookup and the {@code /PIC-SURE/query} async submit), and a Mockito
 * {@link OperationsClient} stands in for operations-service persistence (this module is DB-free).
 *
 * <p>Asserts: (a) {@code POST /hpds/open/query} for a CROSS_COUNT submission is rewritten (force CROSS_COUNT + inject the study-consents
 * allow-list) and the REWRITTEN query is what gets persisted AND dispatched -- so any later status/result read served off the stored query
 * is already consent-scoped; (b) the generic authorized path {@code /hpds/auth/v3/query} is untouched; and (c) a request missing
 * {@code expectedResultType} returns 400 before any backend or persistence call.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class AggregateAsyncOpenQueryTest {

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
    static void props(DynamicPropertyRegistry registry) {
        registry.add("hpds.auth-url", () -> "http://localhost:" + hpds.port() + "/PIC-SURE");
        registry.add("hpds.open-url", () -> "http://localhost:" + hpds.port() + "/PIC-SURE");
        // AggregateBackendClient (study-consents lookup) posts to the open backend root
        registry.add("aggregate.hpds-open-url", () -> "http://localhost:" + hpds.port());
        registry.add("consent.based.authorization.enabled", () -> false);
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OperationsClient operationsClient;

    @BeforeEach
    void reset() {
        hpds.resetAll();
    }

    @Test
    void openAsyncCrossCountIsRewrittenAndConsentScopedBeforePersistAndDispatch() throws Exception {
        hpds.stubFor(
            WireMock.post(urlEqualTo("/search")).willReturn(okJson("{\"results\":{\"phenotypes\":{\"consentA\":{},\"consentB\":{}}}}"))
        );
        hpds.stubFor(
            WireMock.post(urlEqualTo("/PIC-SURE/query")).willReturn(okJson("{\"resourceResultId\":\"rr-async\",\"status\":\"PENDING\"}"))
        );
        when(operationsClient.save(any())).thenReturn(UUID.randomUUID());

        mockMvc.perform(
            post("/hpds/open/query").header(GatewayUserResolver.HEADER_USER_ID, USER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":{\"expectedResultType\":\"CROSS_COUNT\"}}")
        ).andExpect(status().isOk()).andExpect(jsonPath("$.resourceResultId").value("rr-async"));

        // The STORED query is the rewritten, consent-scoped one -- NOT the raw submission.
        verify(operationsClient).save(
            argThat((SaveQueryRequest r) -> r.query() != null && r.query().contains("crossCountFields") && r.query().contains("consentA"))
        );
        // The consent lookup happened and the async submit dispatched the injected allow-list to HPDS.
        hpds.verify(postRequestedFor(urlEqualTo("/search")));
        hpds.verify(postRequestedFor(urlEqualTo("/PIC-SURE/query")).withRequestBody(matchingJsonPath("$.query.crossCountFields")));
    }

    @Test
    void openAsyncV3CrossCountUsesSelectAndHitsV3Base() throws Exception {
        hpds.stubFor(
            WireMock.post(urlEqualTo("/search")).willReturn(okJson("{\"results\":{\"phenotypes\":{\"consentA\":{},\"consentB\":{}}}}"))
        );
        hpds.stubFor(
            WireMock.post(urlEqualTo("/PIC-SURE/v3/query"))
                .willReturn(okJson("{\"resourceResultId\":\"rr-async3\",\"status\":\"PENDING\"}"))
        );
        when(operationsClient.save(any())).thenReturn(UUID.randomUUID());

        mockMvc.perform(
            post("/hpds/open/v3/query").header(GatewayUserResolver.HEADER_USER_ID, USER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":{\"expectedResultType\":\"CROSS_COUNT\"}}")
        ).andExpect(status().isOk());

        verify(operationsClient)
            .save(argThat((SaveQueryRequest r) -> "3".equals(r.version()) && r.query() != null && r.query().contains("select")));
        hpds.verify(postRequestedFor(urlEqualTo("/PIC-SURE/v3/query")).withRequestBody(matchingJsonPath("$.query.select")));
    }

    @Test
    void authAsyncQueryIsNotRewritten() throws Exception {
        hpds.stubFor(
            WireMock.post(urlEqualTo("/PIC-SURE/v3/query")).willReturn(okJson("{\"resourceResultId\":\"rr-auth\",\"status\":\"PENDING\"}"))
        );
        when(operationsClient.save(any())).thenReturn(UUID.randomUUID());

        mockMvc.perform(
            post("/hpds/auth/v3/query").header(GatewayUserResolver.HEADER_USER_ID, USER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":{\"expectedResultType\":\"DATAFRAME\"}}")
        ).andExpect(status().isOk());

        // Generic authorized path: the raw DATAFRAME query is stored + dispatched unchanged; no consent lookup, no CROSS_COUNT rewrite.
        verify(operationsClient).save(
            argThat((SaveQueryRequest r) -> r.query() != null && r.query().contains("DATAFRAME") && !r.query().contains("crossCountFields"))
        );
        hpds.verify(0, postRequestedFor(urlEqualTo("/search")));
        hpds.verify(
            postRequestedFor(urlEqualTo("/PIC-SURE/v3/query"))
                .withRequestBody(matchingJsonPath("$.query.expectedResultType", WireMock.equalTo("DATAFRAME")))
        );
    }

    @Test
    void openAsyncQueryMissingExpectedResultTypeIsRejectedAs400() throws Exception {
        mockMvc.perform(
            post("/hpds/open/query").header(GatewayUserResolver.HEADER_USER_ID, USER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":{\"foo\":\"bar\"}}")
        ).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorType").value("bad_request"));

        verify(operationsClient, never()).save(any());
        hpds.verify(0, postRequestedFor(urlEqualTo("/search")));
    }
}
