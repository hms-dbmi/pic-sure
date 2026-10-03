package edu.harvard.hms.dbmi.avillach.gateway.routing;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
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
import edu.harvard.hms.dbmi.avillach.gateway.filter.McpCallerFilter;
import edu.harvard.hms.dbmi.avillach.gateway.filter.OpenAccessFilter;

/**
 * Runs {@code pic-sure-mcp}'s loop-back call through the whole application with the MCP service secrets set through the same
 * {@code MCP_SERVICE_TOKEN} and {@code MCP_SERVICE_TOKEN_PREVIOUS} variables {@code application.yml} reads, so a mistyped key in that file
 * leaves the gateway with no secret and these tests fail. A request presenting the current or previous secret reaches the dictionary
 * service marked {@code X-Client-Type: mcp} without the secret or the {@code X-PICSURE-API-Key}, and is audited with caller {@code mcp}. A
 * request presenting any other value is refused before PSAMA or the dictionary service is called. A request to {@code /mcp} itself reaches
 * the MCP service with the API key and without the secret.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpLoopBackRouteTest {

    private static final String CURRENT = "current-loop-back-secret";
    private static final String PREVIOUS = "previous-loop-back-secret";
    private static final String API_KEY = "site-api-key";

    static WireMockServer dictionaryStub;
    static WireMockServer mcpStub;
    static WireMockServer psamaStub;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        dictionaryStub = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort().http2PlainDisabled(true));
        dictionaryStub.start();
        registry.add("DICTIONARY_URL", dictionaryStub::baseUrl);

        mcpStub = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort().http2PlainDisabled(true));
        mcpStub.start();
        registry.add("MCP_SERVICE_URL", mcpStub::baseUrl);

        psamaStub = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort().http2PlainDisabled(true));
        psamaStub.start();
        registry.add("TOKEN_INTROSPECTION_URL", () -> psamaStub.baseUrl() + "/auth/token/inspect");

        registry.add("MCP_SERVICE_TOKEN", () -> CURRENT);
        registry.add("MCP_SERVICE_TOKEN_PREVIOUS", () -> PREVIOUS);
    }

    @AfterAll
    static void stopStubs() {
        dictionaryStub.stop();
        mcpStub.stop();
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

        dictionaryStub.resetAll();
        dictionaryStub.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("dictionary-ok")));

        mcpStub.resetAll();
        mcpStub.stubFor(post(urlEqualTo("/mcp")).willReturn(aResponse().withStatus(200).withBody("mcp-ok")));

        psamaStub.resetAll();
        psamaStub.stubFor(
            post(urlEqualTo("/auth/token/inspect"))
                .willReturn(okJson("{\"active\":true,\"userId\":\"u-1\",\"sub\":\"s-1\",\"email\":\"a@b\",\"role\":\"USER\"}"))
        );
    }

    private ResponseEntity<String> loopBack(String presentedToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer user-token");
        headers.set(McpCallerFilter.TOKEN_HEADER, presentedToken);
        headers.set(OpenAccessFilter.API_KEY_HEADER, API_KEY);
        return rest.exchange(
            URI.create("http://127.0.0.1:" + port + "/dictionary/facets"), HttpMethod.GET, new HttpEntity<>(headers), String.class
        );
    }

    private LoggingEvent emittedEvent() {
        ArgumentCaptor<LoggingEvent> emitted = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient, timeout(5000)).send(emitted.capture(), ArgumentMatchers.any(), ArgumentMatchers.any());
        return emitted.getValue();
    }

    @Test
    void theCurrentSecretReachesTheServiceAsMcpWithoutTheSecretAndIsAuditedAsCallerMcp() {
        ResponseEntity<String> response = loopBack(CURRENT);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("dictionary-ok");
        dictionaryStub.verify(
            1,
            getRequestedFor(urlEqualTo("/facets")).withHeader("X-Client-Type", equalTo("mcp"))
                .withHeader(McpCallerFilter.TOKEN_HEADER, absent()).withHeader(OpenAccessFilter.API_KEY_HEADER, absent())
        );
        assertThat(emittedEvent().getCaller()).isEqualTo("mcp");
    }

    @Test
    void thePreviousSecretIsAcceptedDuringRotation() {
        ResponseEntity<String> response = loopBack(PREVIOUS);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        dictionaryStub.verify(
            1,
            getRequestedFor(urlEqualTo("/facets")).withHeader("X-Client-Type", equalTo("mcp"))
                .withHeader(McpCallerFilter.TOKEN_HEADER, absent())
        );
        assertThat(emittedEvent().getCaller()).isEqualTo("mcp");
    }

    @Test
    void aWrongSecretIsRefusedBeforePsamaOrTheServiceIsCalled() {
        ResponseEntity<String> response = loopBack("not-the-loop-back-secret");

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody()).contains("invalid_service_credential");
        dictionaryStub.verify(0, anyRequestedFor(anyUrl()));
        psamaStub.verify(0, postRequestedFor(anyUrl()));
        LoggingEvent event = emittedEvent();
        assertThat(event.getCaller()).isNull();
        assertThat(event.getMetadata()).containsEntry("auth_result", "failure")
            .containsEntry("auth_failure_reason", "invalid_service_credential");
    }

    @Test
    void theApiKeyReachesTheMcpServiceAndTheSecretDoesNot() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer user-token");
        headers.set("Accept", "application/json, text/event-stream");
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(OpenAccessFilter.API_KEY_HEADER, API_KEY);
        headers.set(McpCallerFilter.TOKEN_HEADER, CURRENT);

        ResponseEntity<String> response = rest.exchange(
            URI.create("http://127.0.0.1:" + port + McpCallerFilter.MCP_PATH), HttpMethod.POST, new HttpEntity<>("{}", headers),
            String.class
        );

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        mcpStub.verify(
            1,
            postRequestedFor(urlEqualTo("/mcp")).withHeader(OpenAccessFilter.API_KEY_HEADER, equalTo(API_KEY))
                .withHeader(McpCallerFilter.TOKEN_HEADER, absent())
        );
    }
}
