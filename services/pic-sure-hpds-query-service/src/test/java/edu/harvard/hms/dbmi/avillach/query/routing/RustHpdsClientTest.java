package edu.harvard.hms.dbmi.avillach.query.routing;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import com.github.tomakehurst.wiremock.WireMockServer;

import edu.harvard.dbmi.avillach.domain.GeneralQueryRequest;
import edu.harvard.hms.dbmi.avillach.query.config.HpdsClientConfig;
import edu.harvard.hms.dbmi.avillach.query.config.HpdsProperties;

/** Covers {@link RustHpdsClient} against a stub server: answers of every status are returned, never thrown, and failures are classified. */
class RustHpdsClientTest {

    static WireMockServer rust;

    @BeforeAll
    static void start() {
        rust = new WireMockServer(options().dynamicPort().http2PlainDisabled(true));
        rust.start();
    }

    @AfterAll
    static void stop() {
        rust.stop();
    }

    @BeforeEach
    void reset() {
        rust.resetAll();
    }

    private static RustHpdsClient client(int timeoutMs) {
        HpdsProperties props = new HpdsProperties();
        props.getRs().setTimeoutMs(timeoutMs);
        return new RustHpdsClient(HpdsClientConfig.rustClientFor(props));
    }

    private String base() {
        return "http://localhost:" + rust.port() + "/PIC-SURE/v3";
    }

    private static GeneralQueryRequest req() {
        return new GeneralQueryRequest().setQuery("q");
    }

    @Test
    void syncSendsTheTokenAndRequestSourceAndReturnsBodyAndQueryMetadata() {
        rust.stubFor(
            post(urlEqualTo("/PIC-SURE/v3/query/sync")).withHeader("Authorization", equalTo("Bearer rs-token"))
                .withHeader("request-source", equalTo("Authorized"))
                .willReturn(aResponse().withStatus(200).withHeader("queryMetadata", "rust-id").withBody("42"))
        );

        RustOutcome outcome = client(5000).querySync(base(), "rs-token", req(), "Authorized", RustHpdsClient.UNLIMITED);

        assertThat(outcome).isInstanceOf(RustOutcome.Answered.class);
        RustOutcome.Answered answered = (RustOutcome.Answered) outcome;
        assertThat(answered.status()).isEqualTo(200);
        assertThat(new String(answered.body(), StandardCharsets.UTF_8)).isEqualTo("42");
        assertThat(answered.queryMetadata()).isEqualTo("rust-id");
    }

    @Test
    void aBlankTokenSendsNoAuthorizationHeader() {
        rust.stubFor(post(urlEqualTo("/PIC-SURE/v3/query/sync")).willReturn(aResponse().withStatus(200).withBody("1")));

        client(5000).querySync(base(), " ", req(), null, RustHpdsClient.UNLIMITED);

        rust.verify(postRequestedFor(urlEqualTo("/PIC-SURE/v3/query/sync")).withHeader("Authorization", absent()));
    }

    @Test
    void aRefusalIsReturnedWithItsStatus() {
        rust.stubFor(post(urlEqualTo("/PIC-SURE/v3/query/sync")).willReturn(aResponse().withStatus(400).withBody("refused")));

        RustOutcome outcome = client(5000).querySync(base(), "t", req(), null, RustHpdsClient.UNLIMITED);

        assertThat(outcome).isInstanceOfSatisfying(RustOutcome.Answered.class, a -> {
            assertThat(a.status()).isEqualTo(400);
            assertThat(a.successful()).isFalse();
        });
    }

    @Test
    void aServerErrorIsReturnedWithItsStatus() {
        rust.stubFor(post(urlEqualTo("/PIC-SURE/v3/search")).willReturn(aResponse().withStatus(503)));

        assertThat(client(5000).search(base(), req(), RustHpdsClient.UNLIMITED))
            .isInstanceOfSatisfying(RustOutcome.Answered.class, a -> assertThat(a.status()).isEqualTo(503));
    }

    @Test
    void aSlowAnswerIsATimeout() {
        rust.stubFor(
            post(urlEqualTo("/PIC-SURE/v3/query/sync")).willReturn(aResponse().withStatus(200).withBody("1").withFixedDelay(2000))
        );

        assertThat(client(200).querySync(base(), "t", req(), null, RustHpdsClient.UNLIMITED))
            .isEqualTo(new RustOutcome.Failed(RustFailure.TIMEOUT));
    }

    @Test
    void aClosedPortIsAConnectError() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }

        RustOutcome outcome =
            client(5000).querySync("http://localhost:" + port + "/PIC-SURE/v3", "t", req(), null, RustHpdsClient.UNLIMITED);

        assertThat(outcome).isEqualTo(new RustOutcome.Failed(RustFailure.CONNECT_ERROR));
    }

    @Test
    void valuesUseTheJavaUrlShape() {
        rust.stubFor(
            get(urlPathEqualTo("/PIC-SURE/v3/search/values/")).withQueryParam("genomicConceptPath", equalTo("Gene_with_variant"))
                .withQueryParam("query", equalTo("AP")).withQueryParam("page", equalTo("2")).withQueryParam("size", equalTo("5"))
                .willReturn(aResponse().withStatus(200).withBody("{\"results\":[],\"page\":2,\"total\":0}"))
        );

        assertThat(client(5000).searchConceptValues(base(), "Gene_with_variant", "AP", 2, 5, RustHpdsClient.UNLIMITED))
            .isInstanceOfSatisfying(RustOutcome.Answered.class, a -> assertThat(a.status()).isEqualTo(200));
    }

    @Test
    void aBodyOverTheLimitIsCutOffAndMarked() {
        rust.stubFor(post(urlEqualTo("/PIC-SURE/v3/query/sync")).willReturn(aResponse().withStatus(200).withBody("0123456789")));

        assertThat(client(5000).querySync(base(), "t", req(), null, 4)).isInstanceOfSatisfying(RustOutcome.Answered.class, a -> {
            assertThat(a.truncated()).isTrue();
            assertThat(new String(a.body(), StandardCharsets.UTF_8)).isEqualTo("0123");
        });
        assertThat(client(5000).querySync(base(), "t", req(), null, 10)).isInstanceOfSatisfying(RustOutcome.Answered.class, a -> {
            assertThat(a.truncated()).isFalse();
            assertThat(new String(a.body(), StandardCharsets.UTF_8)).isEqualTo("0123456789");
        });
    }

    @Test
    void aWaitForAPooledConnectionPastTheLeaseTimeoutIsPoolExhausted() throws Exception {
        rust.stubFor(
            post(urlEqualTo("/PIC-SURE/v3/query/sync")).willReturn(aResponse().withStatus(200).withBody("1").withFixedDelay(3000))
        );
        HpdsProperties props = new HpdsProperties();
        props.getRs().setTimeoutMs(5000);
        RustHpdsClient client = new RustHpdsClient(HpdsClientConfig.rustClientFor(props, 1, 1));
        CompletableFuture<RustOutcome> first =
            CompletableFuture.supplyAsync(() -> client.querySync(base(), "t", req(), null, RustHpdsClient.UNLIMITED));
        Thread.sleep(300);

        RustOutcome second = client.querySync(base(), "t", req(), null, RustHpdsClient.UNLIMITED);

        assertThat(second).isEqualTo(new RustOutcome.Failed(RustFailure.POOL_EXHAUSTED));
        assertThat(first.get(10, TimeUnit.SECONDS)).isInstanceOf(RustOutcome.Answered.class);
    }

    @Test
    void aRequestThatCannotBeWrittenIsAClientError() {
        GeneralQueryRequest unwritable = new GeneralQueryRequest().setQuery(new Object());

        assertThat(client(5000).querySync(base(), "t", unwritable, null, RustHpdsClient.UNLIMITED))
            .isEqualTo(new RustOutcome.Failed(RustFailure.CLIENT_ERROR));
    }

    @Test
    void classificationFollowsTheCauseChain() {
        assertThat(RustHpdsClient.classify(new ResourceAccessException("x", new ConnectionRequestTimeoutException("lease"))))
            .isEqualTo(RustFailure.POOL_EXHAUSTED);
        assertThat(RustHpdsClient.classify(new RestClientException("no cause"))).isEqualTo(RustFailure.CLIENT_ERROR);
        assertThat(RustHpdsClient.classify(new ResourceAccessException("x", new ConnectException("refused"))))
            .isEqualTo(RustFailure.CONNECT_ERROR);
        assertThat(RustHpdsClient.classify(new ResourceAccessException("x", new SocketTimeoutException("slow"))))
            .isEqualTo(RustFailure.TIMEOUT);
        assertThat(RustHpdsClient.classify(new ResourceAccessException("x", new IOException("reset")))).isEqualTo(RustFailure.IO_ERROR);
    }
}
