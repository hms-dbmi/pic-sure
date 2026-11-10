package edu.harvard.hms.dbmi.avillach.query.hpds;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.notContaining;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;

import edu.harvard.dbmi.avillach.domain.PaginatedSearchResult;
import edu.harvard.dbmi.avillach.domain.GeneralQueryRequest;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.dbmi.avillach.domain.SignedUrlResponse;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsBackendSelector.HpdsTarget;

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

    private ResourceWebClient client() {
        return new ResourceWebClient(RestClient.builder().build());
    }

    private String base() {
        return "http://localhost:" + hpds.port() + "/PIC-SURE/v3";
    }

    private HpdsTarget target() {
        return new HpdsTarget(base(), TOKEN);
    }

    /** The body HPDS receives on status, result and signed-url: an envelope with no query, which HPDS requires and does not read. */
    private static String emptyEnvelope() throws JsonProcessingException {
        return new ObjectMapper().writeValueAsString(new GeneralQueryRequest());
    }

    private QueryRequest req() {
        GeneralQueryRequest r = new GeneralQueryRequest();
        r.setQuery("q");
        return r;
    }

    @Test
    void queryInjectsServiceBearerToken() {
        hpds.stubFor(
            post(urlEqualTo("/PIC-SURE/v3/query")).withHeader("Authorization", equalTo("Bearer " + TOKEN)) // service token preserved
                .willReturn(okJson("{\"resourceResultId\":\"rr-1\",\"status\":\"PENDING\"}"))
        );

        var status = client().query(target(), req());

        assertThat(status.getResourceResultId()).isEqualTo("rr-1");
    }

    private static final String CLIENT_ID = "8694e3d4-5cb4-410f-8431-993445e6d3f6";

    private static GeneralQueryRequest requestWithClientPicsureId(UUID resourceUuid) {
        Query query = new Query(List.of(), List.of(), null, List.of(), ResultType.COUNT, UUID.fromString(CLIENT_ID), null);
        GeneralQueryRequest request = new GeneralQueryRequest().setQuery(query);
        request.setResourceUUID(resourceUuid);
        return request;
    }

    @Test
    void queryPostsTheQueryWithoutItsPicsureIdAndLeavesTheCallersEnvelopeAlone() {
        hpds.stubFor(post(urlEqualTo("/PIC-SURE/v3/query")).willReturn(okJson("{\"resourceResultId\":\"rr-1\",\"status\":\"PENDING\"}")));
        UUID resourceUuid = UUID.randomUUID();
        GeneralQueryRequest request = requestWithClientPicsureId(resourceUuid);

        client().query(target(), request);

        hpds.verify(
            postRequestedFor(urlEqualTo("/PIC-SURE/v3/query"))
                .withRequestBody(matchingJsonPath("$.query.expectedResultType", equalTo("COUNT")))
                .withRequestBody(matchingJsonPath("$.resourceUUID", equalTo(resourceUuid.toString())))
                .withRequestBody(notContaining(CLIENT_ID))
        );
        assertThat(((Query) request.getQuery()).picsureId()).hasToString(CLIENT_ID);
    }

    @Test
    void querySyncPostsTheQueryWithoutItsPicsureId() {
        hpds.stubFor(post(urlEqualTo("/PIC-SURE/v3/query/sync")).willReturn(aResponse().withStatus(200).withBody("7")));

        client().querySync(target(), requestWithClientPicsureId(null), null);

        hpds.verify(
            postRequestedFor(urlEqualTo("/PIC-SURE/v3/query/sync"))
                .withRequestBody(matchingJsonPath("$.query.expectedResultType", equalTo("COUNT"))).withRequestBody(notContaining(CLIENT_ID))
        );
    }

    @Test
    void resultBuffersOctetStreamWithTokenAndPostsTheEmptyEnvelope() throws JsonProcessingException {
        hpds.stubFor(
            post(urlEqualTo("/PIC-SURE/v3/query/rr-1/result")).withHeader("Authorization", equalTo("Bearer " + TOKEN))
                .withRequestBody(equalToJson(emptyEnvelope())).willReturn(aResponse().withStatus(200).withBody(new byte[] {1, 2, 3}))
        );

        ResponseEntity<byte[]> resp = client().queryResult(target(), "rr-1");

        assertThat(resp.getBody()).containsExactly(1, 2, 3);
    }

    @Test
    void signedUrlReadsTheSignedUrlObjectWithTokenAndPostsTheEmptyEnvelope() throws JsonProcessingException {
        hpds.stubFor(
            post(urlEqualTo("/PIC-SURE/v3/query/rr-1/signed-url")).withHeader("Authorization", equalTo("Bearer " + TOKEN))
                .withRequestBody(equalToJson(emptyEnvelope())).willReturn(okJson("{\"signedUrl\":\"https://s3/x\"}"))
        );

        SignedUrlResponse resp = client().queryResultSignedUrl(target(), "rr-1");

        assertThat(resp.getSignedUrl()).isEqualTo("https://s3/x");
    }

    @Test
    void querySyncInjectsTokenPropagatesMetadataHeaderAndSendsRequestSource() {
        hpds.stubFor(
            post(urlEqualTo("/PIC-SURE/v3/query/sync")).withHeader("Authorization", equalTo("Bearer " + TOKEN))
                .withHeader("request-source", equalTo("UI"))
                .willReturn(aResponse().withStatus(200).withHeader("queryMetadata", "rr-9").withBody("payload"))
        );

        var result = client().querySync(target(), req(), "UI");

        assertThat(new String(result.body())).isEqualTo("payload");
        assertThat(result.queryMetadata()).isEqualTo("rr-9");
    }

    @Test
    void statusInjectsTokenAndPostsTheEmptyEnvelope() throws JsonProcessingException {
        hpds.stubFor(
            post(urlEqualTo("/PIC-SURE/v3/query/rr-1/status")).withHeader("Authorization", equalTo("Bearer " + TOKEN))
                .withRequestBody(equalToJson(emptyEnvelope()))
                .willReturn(okJson("{\"resourceResultId\":\"rr-1\",\"status\":\"AVAILABLE\"}"))
        );

        var status = client().queryStatus(target(), "rr-1");

        assertThat(status.getStatus().name()).isEqualTo("AVAILABLE");
    }

    @Test
    void searchDoesNotInjectServiceToken() { // Search requests do not send a service token.
        hpds.stubFor(
            post(urlEqualTo("/PIC-SURE/v3/search")).withHeader("Authorization", absent())
                .willReturn(okJson("{\"searchQuery\":\"q\",\"results\":{}}"))
        );

        var result = client().search(base(), req()); // String base, not HpdsTarget, so no token path

        assertThat(result).isNotNull();
    }

    @Test
    void searchValuesIsGetWithParamsAndNoServiceToken() {
        hpds.stubFor(
            get(urlPathEqualTo("/PIC-SURE/v3/search/values/")).withHeader("Authorization", absent())
                .withQueryParam("genomicConceptPath", equalTo("\\gene\\")).withQueryParam("query", equalTo("BRCA"))
                .withQueryParam("page", equalTo("1")).willReturn(okJson("{\"results\":[\"BRCA1\",\"BRCA2\"],\"page\":1,\"total\":2}"))
        );

        PaginatedSearchResult<String> result = client().searchConceptValues(base(), "\\gene\\", "BRCA", 1, 10);

        assertThat(result.getResults()).containsExactly("BRCA1", "BRCA2");
        assertThat(result.getPage()).isEqualTo(1);
        assertThat(result.getTotal()).isEqualTo(2);
    }

    @Test
    void hpdsClientErrorKeepsItsStatusAndBody() {
        hpds.stubFor(
            post(urlEqualTo("/PIC-SURE/v3/query/sync"))
                .willReturn(aResponse().withStatus(400).withBody("Result type DATAFRAME is served asynchronously."))
        );

        assertThatThrownBy(() -> client().querySync(target(), req(), null)).isInstanceOf(PicsureException.class)
            .satisfies(e -> assertThat(((PicsureException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST))
            .hasMessageContaining("served asynchronously");
    }

    @Test
    void hpds409And404SurfaceWithTheirStatus() {
        hpds.stubFor(post(urlEqualTo("/PIC-SURE/v3/query/lost/status")).willReturn(aResponse().withStatus(404)));
        hpds.stubFor(post(urlEqualTo("/PIC-SURE/v3/query/lost/result")).willReturn(aResponse().withStatus(404)));
        hpds.stubFor(post(urlEqualTo("/PIC-SURE/v3/query/lost/signed-url")).willReturn(aResponse().withStatus(404)));
        hpds.stubFor(
            post(urlEqualTo("/PIC-SURE/v3/query/running/result")).willReturn(aResponse().withStatus(409).withBody("Status : RUNNING"))
        );
        hpds.stubFor(
            post(urlEqualTo("/PIC-SURE/v3/query/running/signed-url")).willReturn(aResponse().withStatus(409).withBody("Status : PENDING"))
        );

        assertThatThrownBy(() -> client().queryStatus(target(), "lost"))
            .isInstanceOfSatisfying(PicsureException.class, lost -> assertThat(lost.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> client().queryResult(target(), "lost"))
            .isInstanceOfSatisfying(PicsureException.class, lost -> assertThat(lost.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> client().queryResultSignedUrl(target(), "lost"))
            .isInstanceOfSatisfying(PicsureException.class, lost -> assertThat(lost.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> client().queryResult(target(), "running")).isInstanceOfSatisfying(PicsureException.class, unfinished -> {
            assertThat(unfinished.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(unfinished.getMessage()).isEqualTo("Status : RUNNING");
        });
        assertThatThrownBy(() -> client().queryResultSignedUrl(target(), "running"))
            .isInstanceOfSatisfying(PicsureException.class, unfinished -> {
                assertThat(unfinished.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(unfinished.getMessage()).isEqualTo("Status : PENDING");
            });
    }

    @Test
    void hpdsLockedResourceSurfacesAsForbidden() {
        hpds.stubFor(post(urlEqualTo("/PIC-SURE/v3/query")).willReturn(aResponse().withStatus(403).withBody("Resource is locked")));

        assertThatThrownBy(() -> client().query(target(), req())).isInstanceOf(PicsureException.class)
            .satisfies(e -> assertThat(((PicsureException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN))
            .hasMessage("Resource is locked");
    }

    @Test
    void hpdsServerErrorThrowsCommunicationException() {
        hpds.stubFor(post(urlEqualTo("/PIC-SURE/v3/query")).willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> client().query(target(), req())).isInstanceOf(HpdsCommunicationException.class);
    }
}
