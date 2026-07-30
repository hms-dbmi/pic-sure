package edu.harvard.hms.dbmi.avillach.query.aggregate;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;

import edu.harvard.dbmi.avillach.contracts.query.v3.SearchRequest;
import edu.harvard.dbmi.avillach.domain.SearchResults;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.query.config.AggregateProperties;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsCommunicationException;

class AggregateBackendClientTest {

    WireMockServer hpds;

    @BeforeEach
    void start() {
        hpds = new WireMockServer(0);
        hpds.start();
    }

    @AfterEach
    void stop() {
        hpds.stop();
    }

    private AggregateProperties properties() {
        AggregateProperties props = new AggregateProperties();
        props.setHpdsOpenUrl("http://localhost:" + hpds.port());
        props.setVisualizationUrl("http://localhost:" + hpds.port());
        props.setHpdsOpenToken("open-token");
        return props;
    }

    private AggregateBackendClient client() {
        return new AggregateBackendClient(RestClient.builder().build(), properties());
    }

    private Query query() {
        return new Query(List.of("\\age\\"), null, null, null, ResultType.CROSS_COUNT, null, null);
    }

    /**
     * The sync hop is BARE and v3: the query's own fields sit at the root of the body (no {@code query} wrapper, no {@code resourceUUID}),
     * and the path carries the /v3 prefix HPDS's only remaining query surface lives under.
     */
    @Test
    void querySyncPostsTheBareQueryToV3AndSendsBearerTokenAndPropagatesMetadata() {
        // finding I5: HPDS emits the metadata under "queryMetadata" (the legacy WAR's ResourceWebClient.QUERY_METADATA_FIELD); the
        // client must surface that exact header. Stubbing the real name here (not the constant) keeps this a genuine contract check.
        hpds.stubFor(
            post(urlEqualTo("/v3/query/sync")).withHeader("Content-Type", WireMock.containing("application/json"))
                .willReturn(aResponse().withStatus(200).withHeader("queryMetadata", "abc").withBody("42"))
        );

        ResponseEntity<String> resp = client().querySync(query());

        assertThat(resp.getBody()).isEqualTo("42");
        assertThat(resp.getHeaders().getFirst(AggregateBackendClient.QUERY_METADATA_FIELD)).isEqualTo("abc");
        assertThat(AggregateBackendClient.QUERY_METADATA_FIELD).isEqualTo("queryMetadata");
        hpds.verify(
            postRequestedFor(urlEqualTo("/v3/query/sync")).withHeader("Authorization", WireMock.equalTo("Bearer open-token"))
                .withRequestBody(matchingJsonPath("$.select"))
                .withRequestBody(matchingJsonPath("$.expectedResultType", WireMock.equalTo("CROSS_COUNT")))
        );
        // no envelope survives on this hop
        hpds.verify(0, postRequestedFor(urlEqualTo("/v3/query/sync")).withRequestBody(matchingJsonPath("$.query")));
        hpds.verify(0, postRequestedFor(urlEqualTo("/v3/query/sync")).withRequestBody(matchingJsonPath("$.resourceUUID")));
    }

    /** The consents lookup is a typed SearchRequest against HPDS's v3 search -- the unversioned enveloped /search is gone. */
    @Test
    void searchPostsASearchRequestToTheV3SearchPath() {
        hpds.stubFor(post(urlEqualTo("/v3/search")).willReturn(okJson("{\"searchQuery\":\"q\",\"results\":{\"phenotypes\":{}}}")));

        SearchResults results = client().search(new SearchRequest("\\_studies_consents\\"));

        assertThat(results).isNotNull();
        hpds.verify(
            postRequestedFor(urlEqualTo("/v3/search"))
                .withRequestBody(matchingJsonPath("$.query", WireMock.equalTo("\\_studies_consents\\")))
                .withHeader("Authorization", WireMock.equalTo("Bearer open-token"))
        );
        hpds.verify(0, postRequestedFor(urlEqualTo("/search")));
    }

    /** The binning hop carries the continuous counts under the viz service's {@code query} field and nothing else. */
    @Test
    void binContinuousPostsTheCountsToTheV3VizPathWithNoResourceUuid() {
        hpds.stubFor(post(urlEqualTo("/v3/bin/continuous")).willReturn(okJson("{}")));

        client().binContinuous(Map.of("\\age\\", Map.of("5", 100)));

        hpds.verify(
            postRequestedFor(urlEqualTo("/v3/bin/continuous")).withRequestBody(matchingJsonPath("$.query['\\\\age\\\\']['5']"))
                .withRequestBody(matchingJsonPath("$.resourceUUID", absent()))
        );
    }

    @Test
    void nonTwoxxResponseThrowsHpdsCommunicationException() {
        hpds.stubFor(post(urlEqualTo("/v3/query/sync")).willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> client().querySync(query())).isInstanceOf(HpdsCommunicationException.class);
    }

    @Test
    void searchFailureSurfacesAsHpdsCommunicationException() {
        hpds.stubFor(post(urlEqualTo("/v3/search")).willReturn(aResponse().withStatus(503)));
        assertThatThrownBy(() -> client().search(new SearchRequest("x"))).isInstanceOf(HpdsCommunicationException.class);
    }

    @Test
    void noTokenConfiguredOmitsAuthorizationHeader() {
        AggregateProperties props = properties();
        props.setHpdsOpenToken(null);
        AggregateBackendClient c = new AggregateBackendClient(RestClient.builder().build(), props);

        hpds.stubFor(post(urlEqualTo("/v3/query/sync")).willReturn(okJson("1")));
        c.querySync(query());

        hpds.verify(postRequestedFor(urlEqualTo("/v3/query/sync")).withoutHeader("Authorization"));
    }
}
