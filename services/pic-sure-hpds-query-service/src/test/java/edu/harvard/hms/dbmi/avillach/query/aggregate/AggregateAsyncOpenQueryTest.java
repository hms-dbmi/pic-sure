package edu.harvard.hms.dbmi.avillach.query.aggregate;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
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

import edu.harvard.dbmi.avillach.contracts.internal.SaveQueryRequest;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUserResolver;
import edu.harvard.hms.dbmi.avillach.query.operations.OperationsClient;

/**
 * Full-context proof of finding I6 (async open path consent-scoping). Real {@link AggregateService} +
 * {@link edu.harvard.hms.dbmi.avillach.query.query.QueryService} + HpdsBackendSelector/ResourceWebClient; WireMock stands in for the open
 * HPDS backend (the {@code /PIC-SURE/v3/search} study-consents lookup and the {@code /PIC-SURE/v3/query} async submit), and a Mockito
 * {@link OperationsClient} stands in for operations-service persistence (this module is DB-free).
 *
 * <p><b>The wire is bare on both sides.</b> The open ingress binds the v3 {@code Query} record just like the authorized one, and the
 * downstream submit posts that same bare record -- there is no {@code QueryRequest} envelope left on either hop, so these assertions read
 * the query's fields at the ROOT of each body.
 *
 * <p>Asserts: (a) {@code POST /hpds/open/v3/query} for a CROSS_COUNT submission is rewritten (force CROSS_COUNT + inject the study-consents
 * allow-list into {@code select}) and the REWRITTEN query is what gets persisted AND dispatched -- so any later status/result read served
 * off the stored query is already consent-scoped; (b) the generic authorized path {@code /hpds/auth/v3/query} is UNTOUCHED (no rewrite, no
 * consent lookup); (c) a WAR-rejected shape (no {@code expectedResultType}) is still a 400 before any backend/persistence call; (d) the
 * retired unversioned open ingress 404s.
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
        // AggregateBackendClient (study-consents lookup) talks to the same open backend root and appends /v3 itself
        registry.add("aggregate.hpds-open-url", () -> "http://localhost:" + hpds.port() + "/PIC-SURE");
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OperationsClient operationsClient;

    @BeforeEach
    void reset() {
        hpds.resetAll();
    }

    /** The v1 open ingress is retired: neither the async submit nor the sync obfuscation route exists unversioned any more. */
    @Test
    void theUnversionedOpenIngressIsGone() throws Exception {
        mockMvc.perform(
            post("/hpds/open/query").header(GatewayUserResolver.HEADER_USER_ID, USER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedResultType\":\"CROSS_COUNT\"}")
        ).andExpect(status().isNotFound());

        mockMvc.perform(
            post("/hpds/open/query/sync").header(GatewayUserResolver.HEADER_USER_ID, USER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedResultType\":\"COUNT\"}")
        ).andExpect(status().isNotFound());

        verify(operationsClient, never()).save(any());
        hpds.verify(0, postRequestedFor(urlEqualTo("/PIC-SURE/v3/search")));
    }

    @Test
    void openAsyncV3CrossCountUsesSelectAndPostsTheBareQueryToTheV3Base() throws Exception {
        hpds.stubFor(
            WireMock.post(urlEqualTo("/PIC-SURE/v3/search"))
                .willReturn(okJson("{\"results\":{\"phenotypes\":{\"consentA\":{},\"consentB\":{}}}}"))
        );
        hpds.stubFor(
            WireMock.post(urlEqualTo("/PIC-SURE/v3/query"))
                .willReturn(okJson("{\"resourceResultId\":\"rr-async3\",\"status\":\"PENDING\"}"))
        );
        when(operationsClient.save(any())).thenReturn(UUID.randomUUID());

        mockMvc.perform(
            post("/hpds/open/v3/query").header(GatewayUserResolver.HEADER_USER_ID, USER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedResultType\":\"CROSS_COUNT\"}")
        ).andExpect(status().isOk()).andExpect(jsonPath("$.resourceResultId").value("rr-async3"));

        // the consents lookup is the typed SearchRequest against HPDS's v3 search
        hpds.verify(
            postRequestedFor(urlEqualTo("/PIC-SURE/v3/search"))
                .withRequestBody(matchingJsonPath("$.query", WireMock.equalTo("\\_studies_consents\\")))
        );
        // the persisted blob is still the {"query": ...} store wrapper (Task 15 migrates it), around the REWRITTEN query
        verify(operationsClient).save(
            argThat(
                (SaveQueryRequest r) -> "3".equals(r.version()) && r.query() != null && r.query().contains("consentA")
                    && r.query().contains("CROSS_COUNT")
            )
        );
        // BARE downstream body: select sits at the ROOT, and no envelope field survives
        hpds.verify(
            postRequestedFor(urlEqualTo("/PIC-SURE/v3/query"))
                .withRequestBody(matchingJsonPath("$.select[0]", WireMock.equalTo("consentA")))
                .withRequestBody(matchingJsonPath("$.expectedResultType", WireMock.equalTo("CROSS_COUNT")))
                .withRequestBody(matchingJsonPath("$.query", absent()))
        );
    }

    @Test
    void authAsyncQueryIsNotRewritten() throws Exception {
        hpds.stubFor(
            WireMock.post(urlEqualTo("/PIC-SURE/v3/query")).willReturn(okJson("{\"resourceResultId\":\"rr-auth\",\"status\":\"PENDING\"}"))
        );
        when(operationsClient.save(any())).thenReturn(UUID.randomUUID());

        mockMvc.perform(
            post("/hpds/auth/v3/query").header(GatewayUserResolver.HEADER_USER_ID, USER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedResultType\":\"DATAFRAME\"}")
        ).andExpect(status().isOk());

        // Generic authorized path: the raw DATAFRAME query is stored + dispatched unchanged; no consent lookup, no CROSS_COUNT rewrite.
        verify(operationsClient).save(argThat((SaveQueryRequest r) -> r.query() != null && r.query().contains("DATAFRAME")));
        hpds.verify(0, postRequestedFor(urlEqualTo("/PIC-SURE/v3/search")));
        hpds.verify(
            postRequestedFor(urlEqualTo("/PIC-SURE/v3/query"))
                .withRequestBody(matchingJsonPath("$.expectedResultType", WireMock.equalTo("DATAFRAME")))
        );
    }

    @Test
    void openAsyncQueryMissingExpectedResultTypeIsRejectedAs400() throws Exception {
        mockMvc.perform(
            post("/hpds/open/v3/query").header(GatewayUserResolver.HEADER_USER_ID, USER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"select\":[\"\\\\age\\\\\"]}")
        ).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorType").value("bad_request"));

        verify(operationsClient, never()).save(any());
        hpds.verify(0, postRequestedFor(urlEqualTo("/PIC-SURE/v3/search")));
    }

    /** A leftover envelope no longer submits a query by accident: strict deserialization rejects the unmodelled field outright. */
    @Test
    void openAsyncQueryStillWrappedInTheRetiredEnvelopeIsRejectedAs400() throws Exception {
        mockMvc.perform(
            post("/hpds/open/v3/query").header(GatewayUserResolver.HEADER_USER_ID, USER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":{\"expectedResultType\":\"CROSS_COUNT\"}}")
        ).andExpect(status().isBadRequest());

        verify(operationsClient, never()).save(any());
        hpds.verify(0, postRequestedFor(urlEqualTo("/PIC-SURE/v3/query")));
        hpds.verify(0, postRequestedFor(urlEqualTo("/PIC-SURE/v3/search")));
    }
}
