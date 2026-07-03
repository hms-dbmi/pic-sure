package edu.harvard.hms.dbmi.avillach.query.operations;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

import com.github.tomakehurst.wiremock.WireMockServer;

import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;

class OperationsClientTest {

    static WireMockServer ops;

    @BeforeAll
    static void start() {
        // http2PlainDisabled avoids a known JDK HttpClient <-> WireMock(Jetty) h2c upgrade bug (RST_STREAM) when
        // RestClient's default JDK-backed request factory is used.
        ops = new WireMockServer(options().dynamicPort().http2PlainDisabled(true));
        ops.start();
    }

    @AfterAll
    static void stop() {
        ops.stop();
    }

    private OperationsClient client() {
        RestClient http =
            RestClient.builder().baseUrl("http://localhost:" + ops.port()).defaultHeader("X-PIC-SURE-INTERNAL-TOKEN", "test-token").build();
        return new OperationsClient(http);
    }

    @Test
    void savePostsToInternalQueriesAndReturnsPicsureId() {
        UUID id = UUID.randomUUID();
        ops.stubFor(
            post(urlEqualTo("/internal/queries")).willReturn(
                aResponse().withStatus(201).withHeader("Content-Type", "application/json").withBody("{\"picsureId\":\"" + id + "\"}")
            )
        );

        SaveQueryRequest req = new SaveQueryRequest("{}", null, "QUEUED", "v1", null);
        UUID result = client().save(req);

        assertThat(result).isEqualTo(id);
        ops.verify(
            postRequestedFor(urlEqualTo("/internal/queries")).withHeader("X-PIC-SURE-INTERNAL-TOKEN", equalTo("test-token"))
                .withRequestBody(
                    equalToJson("{\"query\":\"{}\",\"resourceResultId\":null,\"status\":\"QUEUED\",\"version\":\"v1\",\"metadata\":null}")
                )
        );
    }

    @Test
    void saveMalformedResponseThrowsBadGateway() {
        ops.stubFor(
            post(urlEqualTo("/internal/queries"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json").withBody("{\"picsureId\":null}"))
        );

        assertThatThrownBy(() -> client().save(new SaveQueryRequest("{}", null, "QUEUED", "v1", null)))
            .isInstanceOfSatisfying(PicsureException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    @Test
    void saveServerErrorThrowsBadGateway() {
        ops.stubFor(post(urlEqualTo("/internal/queries")).willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> client().save(new SaveQueryRequest("{}", null, "QUEUED", "v1", null)))
            .isInstanceOfSatisfying(PicsureException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    @Test
    void getReturnsStoredQuery() {
        UUID id = UUID.randomUUID();
        ops.stubFor(
            get(urlEqualTo("/internal/queries/" + id)).willReturn(
                okJson(
                    "{\"picsureId\":\"" + id
                        + "\",\"query\":\"{}\",\"resourceResultId\":\"r-1\",\"status\":\"COMPLETE\",\"version\":\"v1\",\"metadata\":\"YWJj\"}"
                )
            )
        );

        StoredQuery result = client().get(id);

        assertThat(result).isEqualTo(new StoredQuery(id, "{}", "r-1", "COMPLETE", "v1", "YWJj"));
        ops.verify(getRequestedFor(urlEqualTo("/internal/queries/" + id)).withHeader("X-PIC-SURE-INTERNAL-TOKEN", equalTo("test-token")));
    }

    @Test
    void getNotFoundThrowsPicsureExceptionNotFound() {
        UUID id = UUID.randomUUID();
        ops.stubFor(get(urlEqualTo("/internal/queries/" + id)).willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> client().get(id))
            .isInstanceOfSatisfying(PicsureException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void getServerErrorThrowsBadGateway() {
        UUID id = UUID.randomUUID();
        ops.stubFor(get(urlEqualTo("/internal/queries/" + id)).willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> client().get(id))
            .isInstanceOfSatisfying(PicsureException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    @Test
    void updatePatchesInternalQueries() {
        UUID id = UUID.randomUUID();
        ops.stubFor(patch(urlEqualTo("/internal/queries/" + id)).willReturn(aResponse().withStatus(204)));

        client().update(id, new UpdateQueryRequest("COMPLETE", "r-1", null));

        ops.verify(
            patchRequestedFor(urlEqualTo("/internal/queries/" + id)).withHeader("X-PIC-SURE-INTERNAL-TOKEN", equalTo("test-token"))
                .withRequestBody(equalToJson("{\"status\":\"COMPLETE\",\"resourceResultId\":\"r-1\",\"metadata\":null}"))
        );
    }

    @Test
    void updateServerErrorThrowsBadGateway() {
        UUID id = UUID.randomUUID();
        ops.stubFor(patch(urlEqualTo("/internal/queries/" + id)).willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> client().update(id, new UpdateQueryRequest("COMPLETE", null, null)))
            .isInstanceOfSatisfying(PicsureException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    @Test
    void findByCommonAreaUUIDReturnsStoredQuery() {
        UUID id = UUID.randomUUID();
        UUID commonAreaUUID = UUID.randomUUID();
        ops.stubFor(
            get(urlEqualTo("/internal/queries/by-common-area/" + commonAreaUUID)).willReturn(
                okJson(
                    "{\"picsureId\":\"" + id
                        + "\",\"query\":\"{}\",\"resourceResultId\":null,\"status\":\"QUEUED\",\"version\":\"v1\",\"metadata\":null}"
                )
            )
        );

        StoredQuery result = client().findByCommonAreaUUID(commonAreaUUID);

        assertThat(result).isEqualTo(new StoredQuery(id, "{}", null, "QUEUED", "v1", null));
    }

    @Test
    void findByCommonAreaUUIDServerErrorThrowsBadGateway() {
        UUID commonAreaUUID = UUID.randomUUID();
        ops.stubFor(get(urlEqualTo("/internal/queries/by-common-area/" + commonAreaUUID)).willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> client().findByCommonAreaUUID(commonAreaUUID))
            .isInstanceOfSatisfying(PicsureException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }
}
