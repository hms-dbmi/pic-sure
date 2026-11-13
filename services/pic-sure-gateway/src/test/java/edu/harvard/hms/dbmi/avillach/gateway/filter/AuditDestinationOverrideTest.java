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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.github.tomakehurst.wiremock.WireMockServer;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingEvent;

/**
 * {@code DEST_IP} and {@code DEST_PORT}, the settings the other services read for the same purpose, replace the local address and port on
 * the gateway's audit events.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"DEST_IP=198.51.100.20", "DEST_PORT=443"})
class AuditDestinationOverrideTest {

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

    @Test
    void theConfiguredDestinationIsReportedInPlaceOfTheLocalAddressAndPort() {
        when(loggingClient.isEnabled()).thenReturn(true);
        hpdsStub.stubFor(get(urlEqualTo("/hpds/auth/query/abc-123/status")).willReturn(aResponse().withStatus(200).withBody("hpds-ok")));
        psamaStub.stubFor(
            post(urlEqualTo("/auth/token/inspect"))
                .willReturn(okJson("{\"active\":true,\"userId\":\"u-1\",\"sub\":\"s-1\",\"email\":\"a@b\",\"role\":\"USER\"}"))
        );
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer user-token");

        ResponseEntity<String> response = rest.exchange(
            URI.create("http://127.0.0.1:" + port + "/hpds/auth/query/abc-123/status"), HttpMethod.GET, new HttpEntity<>(headers),
            String.class
        );

        assertThat(response.getBody()).isEqualTo("hpds-ok");
        ArgumentCaptor<LoggingEvent> emitted = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient, timeout(5000)).send(emitted.capture(), any(), any());
        assertThat(emitted.getValue().getRequest().getDestIp()).isEqualTo("198.51.100.20");
        assertThat(emitted.getValue().getRequest().getDestPort()).isEqualTo(443);
        assertThat(emitted.getValue().getRequest().getBytes()).isEqualTo(7);
    }
}
