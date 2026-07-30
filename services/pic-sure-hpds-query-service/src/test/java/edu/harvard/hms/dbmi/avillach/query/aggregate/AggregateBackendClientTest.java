package edu.harvard.hms.dbmi.avillach.query.aggregate;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;

import edu.harvard.dbmi.avillach.domain.GeneralQueryRequest;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
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

    private QueryRequest req(Object query) {
        return new GeneralQueryRequest().setQuery(query);
    }

    @Test
    void querySyncPrependsTheVersionPrefixAndSendsBearerTokenAndPropagatesMetadata() {
        // finding I5: HPDS emits the metadata under "queryMetadata" (the legacy WAR's ResourceWebClient.QUERY_METADATA_FIELD); the
        // client must surface that exact header. Stubbing the real name here (not the constant) keeps this a genuine contract check.
        hpds.stubFor(
            post(urlEqualTo("/v3/query/sync")).withHeader("Content-Type", WireMock.containing("application/json"))
                .willReturn(aResponse().withStatus(200).withHeader("queryMetadata", "abc").withBody("42"))
        );

        ResponseEntity<String> resp = client().querySync(req("{}"), AggregateVariant.V3);

        assertThat(resp.getBody()).isEqualTo("42");
        assertThat(resp.getHeaders().getFirst(AggregateBackendClient.QUERY_METADATA_FIELD)).isEqualTo("abc");
        assertThat(AggregateBackendClient.QUERY_METADATA_FIELD).isEqualTo("queryMetadata");
        hpds.verify(postRequestedFor(urlEqualTo("/v3/query/sync")).withHeader("Authorization", WireMock.equalTo("Bearer open-token")));
    }

    @Test
    void v3BinContinuousPrependsVersionPrefix() {
        hpds.stubFor(post(urlEqualTo("/v3/bin/continuous")).willReturn(okJson("{}")));
        client().binContinuous(req("{}"), AggregateVariant.V3);
        hpds.verify(postRequestedFor(urlEqualTo("/v3/bin/continuous")));
    }

    @Test
    void injectsConfiguredTargetResourceId() {
        String id = UUID.randomUUID().toString();
        AggregateProperties props = properties();
        props.setTargetResourceId(id);
        AggregateBackendClient c = new AggregateBackendClient(RestClient.builder().build(), props);

        hpds.stubFor(post(urlEqualTo("/search")).willReturn(okJson("{\"searchQuery\":\"q\",\"results\":{}}")));
        c.search(req("\\_studies_consents\\"));

        hpds.verify(postRequestedFor(urlEqualTo("/search")).withRequestBody(matchingJsonPath("$.resourceUUID", WireMock.equalTo(id))));
    }

    @Test
    void nonTwoxxResponseThrowsHpdsCommunicationException() {
        hpds.stubFor(post(urlEqualTo("/v3/query/sync")).willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> client().querySync(req("{}"), AggregateVariant.V3)).isInstanceOf(HpdsCommunicationException.class);
    }

    @Test
    void noTokenConfiguredOmitsAuthorizationHeader() {
        AggregateProperties props = properties();
        props.setHpdsOpenToken(null);
        AggregateBackendClient c = new AggregateBackendClient(RestClient.builder().build(), props);

        hpds.stubFor(post(urlEqualTo("/v3/query/sync")).willReturn(okJson("1")));
        c.querySync(req("{}"), AggregateVariant.V3);

        hpds.verify(postRequestedFor(urlEqualTo("/v3/query/sync")).withoutHeader("Authorization"));
    }
}
