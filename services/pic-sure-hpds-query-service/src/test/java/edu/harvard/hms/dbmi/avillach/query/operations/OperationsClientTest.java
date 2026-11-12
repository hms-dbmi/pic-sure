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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

import com.github.tomakehurst.wiremock.WireMockServer;

import edu.harvard.dbmi.avillach.domain.SaveQueryRequest;
import edu.harvard.dbmi.avillach.domain.StoredQuery;
import edu.harvard.dbmi.avillach.domain.UpdateQueryRequest;
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
            post(urlEqualTo("/operations/internal/queries")).willReturn(
                aResponse().withStatus(201).withHeader("Content-Type", "application/json").withBody("{\"picsureId\":\"" + id + "\"}")
            )
        );

        SaveQueryRequest req = new SaveQueryRequest("{}", null, "QUEUED", "v1", null);
        UUID result = client().save(req);

        assertThat(result).isEqualTo(id);
        ops.verify(
            postRequestedFor(urlEqualTo("/operations/internal/queries")).withHeader(
                "X-PIC-SURE-INTERNAL-TOKEN", equalTo("test-token")
            ).withRequestBody(
                equalToJson(
                    "{\"query\":\"{}\",\"resourceResultId\":null,\"status\":\"QUEUED\",\"version\":\"v1\",\"metadata\":null,\"picsureId\":null,\"userUuid\":null,\"consents\":null,\"trigger\":null,\"queryToVariantUuid\":null,\"sample\":null,\"resultType\":null}"
                )
            )
        );
    }

    @Test
    void saveSendsEveryMemberInDeclarationOrderNullsIncluded() {
        UUID id = UUID.randomUUID();
        ops.stubFor(
            post(urlEqualTo("/operations/internal/queries")).willReturn(
                aResponse().withStatus(201).withHeader("Content-Type", "application/json").withBody("{\"picsureId\":\"" + id + "\"}")
            )
        );

        client().save(new SaveQueryRequest("{}", null, "QUEUED", "3", null));

        ops.verify(
            postRequestedFor(urlEqualTo("/operations/internal/queries")).withRequestBody(
                equalTo(
                    "{\"query\":\"{}\",\"resourceResultId\":null,\"status\":\"QUEUED\",\"version\":\"3\",\"metadata\":null,\"picsureId\":null,\"userUuid\":null,\"consents\":null,\"trigger\":null,\"queryToVariantUuid\":null,\"sample\":null,\"resultType\":null}"
                )
            )
        );
    }

    @Test
    void saveMalformedResponseThrowsBadGateway() {
        ops.stubFor(
            post(urlEqualTo("/operations/internal/queries"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json").withBody("{\"picsureId\":null}"))
        );

        assertThatThrownBy(() -> client().save(new SaveQueryRequest("{}", null, "QUEUED", "v1", null)))
            .isInstanceOfSatisfying(PicsureException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    @Test
    void saveServerErrorThrowsBadGateway() {
        ops.stubFor(post(urlEqualTo("/operations/internal/queries")).willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> client().save(new SaveQueryRequest("{}", null, "QUEUED", "v1", null)))
            .isInstanceOfSatisfying(PicsureException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    @Test
    void getReturnsStoredQuery() {
        UUID id = UUID.randomUUID();
        ops.stubFor(
            get(urlEqualTo("/operations/internal/queries/" + id)).willReturn(
                okJson(
                    "{\"picsureId\":\"" + id
                        + "\",\"query\":\"{}\",\"resourceResultId\":\"r-1\",\"status\":\"COMPLETE\",\"version\":\"v1\",\"metadata\":\"YWJj\"}"
                )
            )
        );

        StoredQuery result = client().get(id);

        assertThat(result).isEqualTo(new StoredQuery(id, "{}", "r-1", "COMPLETE", "v1", "YWJj"));
        ops.verify(
            getRequestedFor(urlEqualTo("/operations/internal/queries/" + id)).withHeader("X-PIC-SURE-INTERNAL-TOKEN", equalTo("test-token"))
        );
    }

    @Test
    void getNotFoundThrowsPicsureExceptionNotFound() {
        UUID id = UUID.randomUUID();
        ops.stubFor(get(urlEqualTo("/operations/internal/queries/" + id)).willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> client().get(id))
            .isInstanceOfSatisfying(PicsureException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void getServerErrorThrowsBadGateway() {
        UUID id = UUID.randomUUID();
        ops.stubFor(get(urlEqualTo("/operations/internal/queries/" + id)).willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> client().get(id))
            .isInstanceOfSatisfying(PicsureException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    @Test
    void updatePatchesInternalQueries() {
        UUID id = UUID.randomUUID();
        ops.stubFor(patch(urlEqualTo("/operations/internal/queries/" + id)).willReturn(aResponse().withStatus(204)));

        client().update(id, new UpdateQueryRequest("COMPLETE", "r-1", null, "{\"query\":{}}", "3"));

        ops.verify(
            patchRequestedFor(urlEqualTo("/operations/internal/queries/" + id)).withHeader(
                "X-PIC-SURE-INTERNAL-TOKEN", equalTo("test-token")
            ).withRequestBody(
                equalToJson(
                    "{\"status\":\"COMPLETE\",\"resourceResultId\":\"r-1\",\"metadata\":null,\"query\":\"{\\\"query\\\":{}}\",\"version\":\"3\",\"runId\":null}"
                )
            )
        );
    }

    @Test
    void updateServerErrorThrowsBadGateway() {
        UUID id = UUID.randomUUID();
        ops.stubFor(patch(urlEqualTo("/operations/internal/queries/" + id)).willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> client().update(id, new UpdateQueryRequest("COMPLETE", null, null, null, null)))
            .isInstanceOfSatisfying(PicsureException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    @Test
    void updateRefusedForAStaleRunThrowsBadGateway() {
        UUID id = UUID.randomUUID();
        ops.stubFor(
            patch(urlEqualTo("/operations/internal/queries/" + id)).willReturn(
                aResponse().withStatus(409).withHeader("Content-Type", "application/json")
                    .withBody("{\"errorType\":\"conflict\",\"message\":\"stale\",\"requestId\":null}")
            )
        );

        assertThatThrownBy(() -> client().update(id, new UpdateQueryRequest("AVAILABLE", null, null, null, null, UUID.randomUUID())))
            .isInstanceOfSatisfying(PicsureException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    @Test
    void updateOfTheLatestRunIsSentWithItsRunIdAndReportedAsApplied() {
        UUID id = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        ops.stubFor(patch(urlEqualTo("/operations/internal/queries/" + id)).willReturn(aResponse().withStatus(204)));

        boolean applied = client().updateLatestRun(id, new UpdateQueryRequest("AVAILABLE", null, null, null, null, runId));

        assertThat(applied).isTrue();
        ops.verify(
            patchRequestedFor(urlEqualTo("/operations/internal/queries/" + id)).withRequestBody(
                equalToJson(
                    "{\"status\":\"AVAILABLE\",\"resourceResultId\":null,\"metadata\":null,\"query\":null,\"version\":null,\"runId\":\""
                        + runId + "\"}"
                )
            )
        );
    }

    @Test
    void updateOfARunThatIsNoLongerTheLatestIsReportedAsNotAppliedAndDoesNotThrow() {
        UUID id = UUID.randomUUID();
        ops.stubFor(
            patch(urlEqualTo("/operations/internal/queries/" + id)).willReturn(
                aResponse().withStatus(409).withHeader("Content-Type", "application/json")
                    .withBody("{\"errorType\":\"conflict\",\"message\":\"stale\",\"requestId\":null}")
            )
        );

        boolean applied = client().updateLatestRun(id, new UpdateQueryRequest("AVAILABLE", null, null, null, null, UUID.randomUUID()));

        assertThat(applied).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 500})
    void updateOfTheLatestRunThatFailsForAnyOtherReasonThrowsBadGateway(int answer) {
        UUID id = UUID.randomUUID();
        ops.stubFor(patch(urlEqualTo("/operations/internal/queries/" + id)).willReturn(aResponse().withStatus(answer)));

        assertThatThrownBy(
            () -> client().updateLatestRun(id, new UpdateQueryRequest("AVAILABLE", null, null, null, null, UUID.randomUUID()))
        ).isInstanceOfSatisfying(PicsureException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    @Test
    void updateRefusedForTheBodyOfAnOwnedRowThrowsBadGateway() {
        UUID id = UUID.randomUUID();
        ops.stubFor(patch(urlEqualTo("/operations/internal/queries/" + id)).willReturn(aResponse().withStatus(400)));

        assertThatThrownBy(() -> client().update(id, new UpdateQueryRequest("PENDING", "rr", null, "{\"query\":{}}", "3")))
            .isInstanceOfSatisfying(PicsureException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    @Test
    void saveRefusedForADifferentOwnerIsAServerFaultThatRepeatsNothingOperationsSaid() {
        UUID id = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        ops.stubFor(
            post(urlEqualTo("/operations/internal/queries")).willReturn(
                aResponse().withStatus(409).withHeader("Content-Type", "application/json").withBody(
                    "{\"errorType\":\"conflict\",\"message\":\"Query " + id + " belongs to a different owner\",\"requestId\":null}"
                )
            )
        );
        SaveQueryRequest request = new SaveQueryRequest("{}", id.toString(), "QUEUED", "3", null, id, owner, Map.of(), "FIRST_SUBMIT");

        assertThatThrownBy(() -> client().save(request)).isInstanceOfSatisfying(PicsureException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(e.getErrorType()).isEqualTo("internal_server_error");
            assertThat(e.getMessage()).isEqualTo("The query could not be stored").doesNotContain("owner").doesNotContain("belongs")
                .doesNotContain(owner.toString()).doesNotContain(id.toString());
        });
    }

    @Test
    void saveSendsIdOwnerConsentsAndTrigger() {
        UUID id = UUID.fromString("8694e3d4-5cb4-510f-8431-993445e6d3f6");
        UUID owner = UUID.fromString("5f0c1f0e-6f0a-4a57-9d6e-0d1c6a3b9f11");
        ops.stubFor(
            post(urlEqualTo("/operations/internal/queries")).willReturn(
                aResponse().withStatus(201).withHeader("Content-Type", "application/json").withBody("{\"picsureId\":\"" + id + "\"}")
            )
        );

        UUID saved = client().save(
            new SaveQueryRequest(
                "{}", id.toString(), "PENDING", "3", null, id, owner, Map.of("\\_consents\\", List.of("phs001.c1", "phs001.c2")),
                "FIRST_SUBMIT"
            )
        );

        assertThat(saved).isEqualTo(id);
        ops.verify(
            postRequestedFor(urlEqualTo("/operations/internal/queries")).withRequestBody(
                equalToJson(
                    "{\"query\":\"{}\",\"resourceResultId\":\"" + id + "\",\"status\":\"PENDING\",\"version\":\"3\",\"metadata\":null,"
                        + "\"picsureId\":\"" + id + "\",\"userUuid\":\"" + owner + "\","
                        + "\"consents\":{\"\\\\_consents\\\\\":[\"phs001.c1\",\"phs001.c2\"]},\"trigger\":\"FIRST_SUBMIT\","
                        + "\"queryToVariantUuid\":null,\"sample\":null,\"resultType\":null}"
                )
            )
        );
    }

    @Test
    void findReturnsTheStoredQueryWithItsOwnerConsentsAndRun() {
        UUID id = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        ops.stubFor(
            get(urlEqualTo("/operations/internal/queries/" + id)).willReturn(
                aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(
                    "{\"picsureId\":\"" + id + "\",\"query\":\"{}\",\"resourceResultId\":\"" + id + "\",\"status\":\"PENDING\","
                        + "\"version\":\"3\",\"metadata\":null,\"userUuid\":\"" + owner + "\","
                        + "\"consents\":{\"\\\\_consents\\\\\":[\"phs001.c1\"]},\"runId\":\"" + run + "\"}"
                )
            )
        );

        Optional<StoredQuery> found = client().find(id);

        assertThat(found).hasValueSatisfying(stored -> {
            assertThat(stored.picsureId()).isEqualTo(id);
            assertThat(stored.userUuid()).isEqualTo(owner);
            assertThat(stored.consents()).containsEntry("\\_consents\\", List.of("phs001.c1"));
            assertThat(stored.runId()).isEqualTo(run);
        });
    }

    @Test
    void findReturnsEmptyForAnUnknownId() {
        UUID id = UUID.randomUUID();
        ops.stubFor(get(urlEqualTo("/operations/internal/queries/" + id)).willReturn(aResponse().withStatus(404)));

        assertThat(client().find(id)).isEmpty();
    }

    @Test
    void findServerErrorThrowsBadGateway() {
        UUID id = UUID.randomUUID();
        ops.stubFor(get(urlEqualTo("/operations/internal/queries/" + id)).willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> client().find(id))
            .isInstanceOfSatisfying(PicsureException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }
}
