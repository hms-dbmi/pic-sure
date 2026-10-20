package edu.harvard.hms.dbmi.avillach.gateway.routing;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import edu.harvard.hms.dbmi.avillach.gateway.filter.HpdsBackendAuditFilter;

/**
 * Through the real {@code /hpds/**} route: the query service's backend header is recorded as the {@code hpds_backend} metadata of the
 * gateway's {@code query.sync} audit event, and the client's response does not carry it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HpdsBackendAuditRouteTest {

    static WireMockServer queryServiceStub;
    static WireMockServer psamaStub;

    @DynamicPropertySource
    static void urls(DynamicPropertyRegistry registry) {
        queryServiceStub = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort().http2PlainDisabled(true));
        queryServiceStub.start();
        registry.add("HPDS_QUERY_SERVICE_URL", queryServiceStub::baseUrl);

        psamaStub = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort().http2PlainDisabled(true));
        psamaStub.start();
        registry.add("TOKEN_INTROSPECTION_URL", () -> psamaStub.baseUrl() + "/auth/token/inspect");
    }

    @AfterAll
    static void stopStubs() {
        queryServiceStub.stop();
        psamaStub.stop();
    }

    @MockitoBean
    LoggingClient loggingClient;

    @Autowired
    private TestRestTemplate rest;

    @LocalServerPort
    int port;

    @BeforeEach
    void resetStubs() {
        when(loggingClient.isEnabled()).thenReturn(true);
        queryServiceStub.resetAll();
        psamaStub.resetAll();
        psamaStub.stubFor(
            post(urlEqualTo("/auth/token/inspect"))
                .willReturn(okJson("{\"active\":true,\"userId\":\"u-1\",\"sub\":\"s-1\",\"email\":\"a@b\",\"role\":\"USER\"}"))
        );
    }

    @Test
    void theBackendHeaderBecomesAuditMetadataAndIsRemoved() {
        queryServiceStub.stubFor(
            post(urlEqualTo("/hpds/auth/query/sync")).willReturn(
                aResponse().withStatus(200).withHeader("Content-Type", "application/json").withHeader(HpdsBackendAuditFilter.HEADER, "rust")
                    .withHeader("queryMetadata", "q-1").withBody("42")
            )
        );
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer user-token");
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = rest.exchange(
            "http://127.0.0.1:" + port + "/hpds/auth/query/sync", HttpMethod.POST,
            new HttpEntity<>("{\"query\":{\"expectedResultType\":\"COUNT\"}}", headers), String.class
        );

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("42");
        assertThat(response.getHeaders().getFirst("queryMetadata")).isEqualTo("q-1");
        assertThat(response.getHeaders().getFirst(HpdsBackendAuditFilter.HEADER)).isNull();
        ArgumentCaptor<LoggingEvent> events = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient, timeout(5000)).send(events.capture(), nullable(String.class), nullable(String.class));
        assertThat(events.getAllValues()).anySatisfy(event -> {
            assertThat(event.getAction()).isEqualTo("query.sync");
            assertThat(event.getMetadata()).containsEntry(HpdsBackendAuditFilter.METADATA_KEY, "rust");
        });
    }
}
