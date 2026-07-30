package edu.harvard.hms.dbmi.avillach.query.hpds;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import com.github.tomakehurst.wiremock.WireMockServer;

import edu.harvard.dbmi.avillach.contracts.query.v3.PaginatedResponse;
import edu.harvard.dbmi.avillach.contracts.query.v3.PicSureStatus;
import edu.harvard.dbmi.avillach.contracts.query.v3.QueryStatusResponse;
import edu.harvard.dbmi.avillach.contracts.query.v3.SearchRequest;
import edu.harvard.dbmi.avillach.contracts.query.v3.SignedUrlResponse;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsBackendSelector.HpdsTarget;

/**
 * Pins the HPDS wire. Every hop is typed: submissions carry a BARE v3 {@link Query} (no {@code QueryRequest} envelope), the status hop is a
 * GET, and result/signed-url are bodyless POSTs -- reads have nothing to send, since HPDS already holds the query behind the
 * {@code resourceResultId}. Responses parse into the shared contract records.
 *
 * <p>These stubs are the SPEC for HPDS's side of the contract: what is asserted here -- verb, path, request body, and response shape -- is
 * exactly what HPDS accepts and emits (pinned on that side by {@code PicSureV3ServiceWebTest}).
 */
class ResourceWebClientTest {

    static WireMockServer hpds;
    static final String TOKEN = "svc-token-123";

    @BeforeAll
    static void start() {
        // http2PlainDisabled avoids a known JDK HttpClient <-> WireMock(Jetty) h2c upgrade bug (RST_STREAM) when
        // RestClient's default JDK-backed request factory is used (see OperationsClientTest).
        hpds = new WireMockServer(options().dynamicPort().http2PlainDisabled(true));
        hpds.start();
    }

    @AfterAll
    static void stop() {
        hpds.stop();
    }

    @BeforeEach
    void resetStubs() {
        hpds.resetAll();
    }

    private ResourceWebClient client() {
        return new ResourceWebClient(RestClient.builder().build());
    }

    private String base() {
        return "http://localhost:" + hpds.port() + "/PIC-SURE";
    }

    private HpdsTarget target() {
        return new HpdsTarget(base(), TOKEN);
    }

    private Query query() {
        return new Query(List.of("\\age\\"), null, null, null, ResultType.COUNT, null, null);
    }

    // --- create: a BARE Query on the wire, a typed status back ---

    @Test
    void queryPostsTheBareQueryAndInjectsTheServiceBearerToken() {
        hpds.stubFor(
            post(urlEqualTo("/PIC-SURE/query")).withHeader("Authorization", equalTo("Bearer " + TOKEN)) // service token preserved
                .willReturn(okJson("{\"resourceResultId\":\"rr-1\",\"status\":\"PENDING\"}"))
        );

        QueryStatusResponse status = client().query(target(), query());

        assertThat(status.resourceResultId()).isEqualTo("rr-1");
        assertThat(status.status()).isEqualTo(PicSureStatus.PENDING);
        // BARE: the query's own fields sit at the root -- there is no "query" wrapper left on this hop.
        hpds.verify(
            postRequestedFor(urlEqualTo("/PIC-SURE/query")).withRequestBody(matchingJsonPath("$.select"))
                .withRequestBody(matchingJsonPath("$.expectedResultType", equalTo("COUNT")))
        );
        hpds.verify(0, postRequestedFor(urlEqualTo("/PIC-SURE/query")).withRequestBody(matchingJsonPath("$.query")));
    }

    /**
     * HPDS's status response is read as the shared {@link QueryStatusResponse}. Fields HPDS emits that the contract does not model (the
     * legacy {@code picsureResultId}/{@code resourceID} echoes, alive until Task 8) must not break the hop.
     */
    @Test
    void statusIsAGetWithNoBodyAndParsesIntoTheContractRecord() {
        hpds.stubFor(
            get(urlEqualTo("/PIC-SURE/query/rr-1/status")).withHeader("Authorization", equalTo("Bearer " + TOKEN)).willReturn(
                okJson(
                    "{\"resourceResultId\":\"rr-1\",\"status\":\"AVAILABLE\",\"resourceStatus\":\"SUCCESS\"," //
                        + "\"sizeInBytes\":42,\"picsureResultId\":null,\"resourceID\":null}"
                )
            )
        );

        QueryStatusResponse status = client().queryStatus(target(), "rr-1");

        assertThat(status.status()).isEqualTo(PicSureStatus.AVAILABLE);
        assertThat(status.resourceStatus()).isEqualTo("SUCCESS");
        assertThat(status.sizeInBytes()).isEqualTo(42L);
        hpds.verify(getRequestedFor(urlEqualTo("/PIC-SURE/query/rr-1/status")));
        hpds.verify(0, postRequestedFor(urlEqualTo("/PIC-SURE/query/rr-1/status")));
    }

    /** Octet-stream, FULLY BUFFERED -- parity with the legacy ResourceWebClient.queryResult/readBytesFromResponse. */
    @Test
    void resultIsABodylessPostThatBuffersTheOctetStream() {
        hpds.stubFor(
            post(urlEqualTo("/PIC-SURE/query/rr-1/result")).withHeader("Authorization", equalTo("Bearer " + TOKEN))
                .willReturn(aResponse().withStatus(200).withBody(new byte[] {1, 2, 3}))
        );

        ResponseEntity<byte[]> resp = client().queryResult(target(), "rr-1");

        assertThat(resp.getBody()).containsExactly(1, 2, 3);
        hpds.verify(postRequestedFor(urlEqualTo("/PIC-SURE/query/rr-1/result")).withRequestBody(absent()));
    }

    @Test
    void signedUrlIsABodylessPostParsedIntoTheContractRecord() {
        hpds.stubFor(
            post(urlEqualTo("/PIC-SURE/query/rr-1/signed-url")).withHeader("Authorization", equalTo("Bearer " + TOKEN))
                .willReturn(okJson("{\"signedUrl\":\"https://s3/x\"}"))
        );

        SignedUrlResponse resp = client().queryResultSignedUrl(target(), "rr-1");

        assertThat(resp.signedUrl()).isEqualTo("https://s3/x");
        hpds.verify(postRequestedFor(urlEqualTo("/PIC-SURE/query/rr-1/signed-url")).withRequestBody(absent()));
    }

    /** A signed-url body that is not JSON is an upstream contract violation, not a 200 carrying a null url. */
    @Test
    void signedUrlWithAnUnreadableBodyThrowsCommunicationException() {
        hpds.stubFor(post(urlEqualTo("/PIC-SURE/query/rr-1/signed-url")).willReturn(okJson("not json")));

        assertThatThrownBy(() -> client().queryResultSignedUrl(target(), "rr-1")).isInstanceOf(HpdsCommunicationException.class);
    }

    @Test
    void querySyncPostsTheBareQueryPropagatesMetadataHeaderAndSendsRequestSource() {
        hpds.stubFor(
            post(urlEqualTo("/PIC-SURE/query/sync")).withHeader("Authorization", equalTo("Bearer " + TOKEN))
                .withHeader("request-source", equalTo("UI"))
                .willReturn(aResponse().withStatus(200).withHeader("queryMetadata", "rr-9").withBody("payload"))
        );

        var result = client().querySync(target(), query(), "UI");

        assertThat(new String(result.body())).isEqualTo("payload");
        assertThat(result.queryMetadata()).isEqualTo("rr-9");
        hpds.verify(postRequestedFor(urlEqualTo("/PIC-SURE/query/sync")).withRequestBody(matchingJsonPath("$.expectedResultType")));
        hpds.verify(0, postRequestedFor(urlEqualTo("/PIC-SURE/query/sync")).withRequestBody(matchingJsonPath("$.query")));
    }

    // --- search: NO service token (parity with PicsureSearchService, which never set BEARER_TOKEN) ---

    /**
     * Also pins this module's local {@code SearchResults} against the shape HPDS's {@code PicSureV3Service} actually emits: both components
     * must bind off the wire, or the aggregate consents lookup silently reads a null allow-list.
     */
    @Test
    void searchPostsTheTypedSearchRequestWithoutAServiceToken() {
        hpds.stubFor(
            post(urlEqualTo("/PIC-SURE/search")).withHeader("Authorization", absent())
                .willReturn(okJson("{\"searchQuery\":\"BRCA\",\"results\":{\"phenotypes\":{},\"info\":{}}}"))
        );

        var result = client().search(base(), new SearchRequest("BRCA")); // String base, not HpdsTarget -- no token path

        assertThat(result).isNotNull();
        assertThat(result.searchQuery()).isEqualTo("BRCA");
        assertThat(result.results()).isEqualTo(Map.of("phenotypes", Map.of(), "info", Map.of()));
        hpds.verify(postRequestedFor(urlEqualTo("/PIC-SURE/search")).withRequestBody(equalToJson("{\"query\":\"BRCA\"}")));
    }

    @Test
    void searchValuesIsGetWithParamsAndReturnsTheTypedPage() {
        hpds.stubFor(
            get(urlPathEqualTo("/PIC-SURE/search/values/")).withHeader("Authorization", absent())
                .withQueryParam("genomicConceptPath", equalTo("\\gene\\")).withQueryParam("query", equalTo("BRCA"))
                .withQueryParam("page", equalTo("1")).withQueryParam("size", equalTo("10"))
                .willReturn(okJson("{\"results\":[\"BRCA1\",\"BRCA2\"],\"page\":1,\"total\":2}"))
        );

        PaginatedResponse<String> result = client().searchConceptValues(base(), "\\gene\\", "BRCA", 1, 10);

        assertThat(result.results()).containsExactly("BRCA1", "BRCA2");
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.total()).isEqualTo(2);
    }

    @Test
    void hpdsNon2xxThrowsCommunicationException() {
        hpds.stubFor(post(urlEqualTo("/PIC-SURE/query")).willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> client().query(target(), query())).isInstanceOf(HpdsCommunicationException.class);
    }

    @Test
    void statusNon2xxThrowsCommunicationException() {
        hpds.stubFor(get(urlEqualTo("/PIC-SURE/query/rr-1/status")).willReturn(aResponse().withStatus(503)));
        assertThatThrownBy(() -> client().queryStatus(target(), "rr-1")).isInstanceOf(HpdsCommunicationException.class);
    }
}
