package edu.harvard.hms.dbmi.avillach.gateway.filter;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.github.tomakehurst.wiremock.WireMockServer;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingEvent;
import edu.harvard.dbmi.avillach.logging.RequestInfo;

/**
 * Audit events for requests the running gateway proxies report where the request arrived and how many body bytes went back to the client.
 * The requests go over a real socket to the embedded server and on to a stub backend, so the count covers the gateway's own copy of the
 * backend response, including one the backend sends in delayed chunks, and each test checks the client still receives the backend's exact
 * bytes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuditDestinationAndBytesTest {

    private static final String JSON_BODY = "{\"count\":42,\"label\":\"Zoë\"}";

    static WireMockServer hpdsStub;
    static WireMockServer psamaStub;

    @DynamicPropertySource
    static void urls(DynamicPropertyRegistry registry) {
        hpdsStub = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort().http2PlainDisabled(true));
        hpdsStub.start();
        registry.add("HPDS_QUERY_SERVICE_URL", hpdsStub::baseUrl);

        psamaStub = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort().http2PlainDisabled(true));
        psamaStub.start();
        registry.add("TOKEN_INTROSPECTION_URL", () -> psamaStub.baseUrl() + "/auth/token/inspect");
    }

    @AfterAll
    static void stopStubs() {
        hpdsStub.stop();
        psamaStub.stop();
    }

    @Autowired
    private TestRestTemplate rest;

    @MockitoBean
    private LoggingClient loggingClient;

    @LocalServerPort
    int port;

    @BeforeEach
    void resetStubs() {
        when(loggingClient.isEnabled()).thenReturn(true);

        hpdsStub.resetAll();
        psamaStub.resetAll();
        psamaStub.stubFor(
            post(urlEqualTo("/auth/token/inspect"))
                .willReturn(okJson("{\"active\":true,\"userId\":\"u-1\",\"sub\":\"s-1\",\"email\":\"a@b\",\"role\":\"USER\"}"))
        );
    }

    private ResponseEntity<byte[]> call(HttpMethod method, String path, boolean withToken) {
        HttpHeaders headers = new HttpHeaders();
        if (withToken) {
            headers.set("Authorization", "Bearer user-token");
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(method == HttpMethod.POST ? "{}" : null, headers);
        return rest.exchange(URI.create("http://127.0.0.1:" + port + path), method, entity, byte[].class);
    }

    private RequestInfo emittedRequest() {
        ArgumentCaptor<LoggingEvent> emitted = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient, timeout(5000)).send(emitted.capture(), any(), any());
        return emitted.getValue().getRequest();
    }

    private static byte[] resultRows(int rows) {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < rows; i++) {
            body.append("patient-").append(i).append(",phs000001,").append(i * 31).append('\n');
        }
        return body.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void aProxiedJsonResponseReportsTheLocalAddressThePortAndTheBodySize() {
        hpdsStub.stubFor(post(urlEqualTo("/hpds/auth/query/sync")).willReturn(okJson(JSON_BODY)));

        ResponseEntity<byte[]> response = call(HttpMethod.POST, "/hpds/auth/query/sync", true);

        byte[] expected = JSON_BODY.getBytes(StandardCharsets.UTF_8);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(expected);
        RequestInfo info = emittedRequest();
        assertThat(info.getDestIp()).isEqualTo("127.0.0.1");
        assertThat(info.getDestPort()).isEqualTo(port);
        assertThat(info.getBytes()).isEqualTo(expected.length);
    }

    /**
     * The backend sends the body in chunks spread over time with no {@code Content-Length}, once as a media type the gateway flushes chunk
     * by chunk and once as one it copies in a single pass. The event carries the full size either way, which shows the gateway finishes
     * writing the body before the audit filter emits.
     */
    @ParameterizedTest
    @ValueSource(strings = {"text/event-stream", "application/octet-stream"})
    void aStreamedResponseReportsEveryByteAndReachesTheClientUnchanged(String contentType) {
        byte[] body = resultRows(12_000);
        hpdsStub.stubFor(
            get(urlEqualTo("/hpds/auth/query/abc-123/result")).willReturn(
                aResponse().withStatus(200).withHeader("Content-Type", contentType).withBody(body).withChunkedDribbleDelay(8, 400)
            )
        );

        ResponseEntity<byte[]> response = call(HttpMethod.GET, "/hpds/auth/query/abc-123/result", true);

        assertThat(body.length).isGreaterThan(250_000);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentLength()).isEqualTo(-1);
        assertThat(response.getBody()).isEqualTo(body);
        RequestInfo info = emittedRequest();
        assertThat(info.getBytes()).isEqualTo(body.length);
        assertThat(info.getDestPort()).isEqualTo(port);
    }

    @Test
    void aProxiedResponseWithNoBodyReportsZeroBytes() {
        hpdsStub.stubFor(get(urlEqualTo("/hpds/auth/query/abc-123/status")).willReturn(aResponse().withStatus(204)));

        ResponseEntity<byte[]> response = call(HttpMethod.GET, "/hpds/auth/query/abc-123/status", true);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        assertThat(response.getBody()).isNull();
        assertThat(emittedRequest().getBytes()).isZero();
    }

    @Test
    void anErrorBodyTheGatewayWritesItselfIsCounted() {
        ResponseEntity<byte[]> response = call(HttpMethod.GET, "/hpds/auth/query/abc-123/status", false);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody()).isNotEmpty();
        RequestInfo info = emittedRequest();
        assertThat(info.getStatus()).isEqualTo(401);
        assertThat(info.getBytes()).isEqualTo(response.getBody().length);
    }
}
