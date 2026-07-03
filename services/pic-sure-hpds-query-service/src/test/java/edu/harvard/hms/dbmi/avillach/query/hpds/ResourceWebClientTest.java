package edu.harvard.hms.dbmi.avillach.query.hpds;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import com.github.tomakehurst.wiremock.WireMockServer;

import edu.harvard.dbmi.avillach.domain.GeneralQueryRequest;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
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
        return "http://localhost:" + hpds.port() + "/PIC-SURE";
    }

    private HpdsTarget target() {
        return new HpdsTarget(base(), TOKEN);
    }

    private QueryRequest req() {
        GeneralQueryRequest r = new GeneralQueryRequest();
        r.setQuery("q");
        return r;
    }

    @Test
    void queryInjectsServiceBearerToken() {
        hpds.stubFor(
            post(urlEqualTo("/PIC-SURE/query")).withHeader("Authorization", equalTo("Bearer " + TOKEN)) // service token preserved
                .willReturn(okJson("{\"resourceResultId\":\"rr-1\",\"status\":\"PENDING\"}"))
        );

        var status = client().query(target(), req());

        assertThat(status.getResourceResultId()).isEqualTo("rr-1");
    }

    @Test
    void resultBuffersOctetStreamWithToken() {
        hpds.stubFor(
            post(urlEqualTo("/PIC-SURE/query/rr-1/result")).withHeader("Authorization", equalTo("Bearer " + TOKEN))
                .willReturn(aResponse().withStatus(200).withBody(new byte[] {1, 2, 3}))
        );

        ResponseEntity<byte[]> resp = client().queryResult(target(), "rr-1", req());

        assertThat(resp.getBody()).containsExactly(1, 2, 3);
    }

    @Test
    void signedUrlBuffersJsonStringWithToken() {
        hpds.stubFor(
            post(urlEqualTo("/PIC-SURE/query/rr-1/signed-url")).withHeader("Authorization", equalTo("Bearer " + TOKEN))
                .willReturn(okJson("{\"url\":\"https://s3/x\"}"))
        );

        ResponseEntity<String> resp = client().queryResultSignedUrl(target(), "rr-1", req());

        assertThat(resp.getBody()).contains("https://s3/x");
    }

    @Test
    void querySyncInjectsTokenPropagatesMetadataHeaderAndSendsRequestSource() {
        hpds.stubFor(
            post(urlEqualTo("/PIC-SURE/query/sync")).withHeader("Authorization", equalTo("Bearer " + TOKEN))
                .withHeader("request-source", equalTo("UI"))
                .willReturn(aResponse().withStatus(200).withHeader("queryMetadata", "rr-9").withBody("payload"))
        );

        var result = client().querySync(target(), req(), "UI");

        assertThat(new String(result.body())).isEqualTo("payload");
        assertThat(result.queryMetadata()).isEqualTo("rr-9");
    }

    @Test
    void statusInjectsToken() {
        hpds.stubFor(
            post(urlEqualTo("/PIC-SURE/query/rr-1/status")).withHeader("Authorization", equalTo("Bearer " + TOKEN))
                .willReturn(okJson("{\"resourceResultId\":\"rr-1\",\"status\":\"AVAILABLE\"}"))
        );

        var status = client().queryStatus(target(), "rr-1", req());

        assertThat(status.getStatus().name()).isEqualTo("AVAILABLE");
    }

    @Test
    void searchDoesNotInjectServiceToken() { // parity with PicsureSearchService (no BEARER_TOKEN)
        hpds.stubFor(
            post(urlEqualTo("/PIC-SURE/search")).withHeader("Authorization", absent())
                .willReturn(okJson("{\"searchQuery\":\"q\",\"results\":{}}"))
        );

        var result = client().search(base(), req()); // String base, not HpdsTarget -- no token path

        assertThat(result).isNotNull();
    }

    @Test
    void searchValuesIsGetWithParamsAndNoServiceToken() {
        hpds.stubFor(
            get(urlPathEqualTo("/PIC-SURE/search/values/")).withHeader("Authorization", absent())
                .withQueryParam("genomicConceptPath", equalTo("\\gene\\")).withQueryParam("query", equalTo("BRCA"))
                .withQueryParam("page", equalTo("1")).willReturn(okJson("{\"results\":[],\"page\":1,\"total\":0}"))
        );

        var result = client().searchConceptValues(base(), req(), "\\gene\\", "BRCA", 1, 10);

        assertThat(result).isNotNull();
    }

    @Test
    void hpdsNon2xxThrowsCommunicationException() {
        hpds.stubFor(post(urlEqualTo("/PIC-SURE/query")).willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> client().query(target(), req())).isInstanceOf(HpdsCommunicationException.class);
    }
}
