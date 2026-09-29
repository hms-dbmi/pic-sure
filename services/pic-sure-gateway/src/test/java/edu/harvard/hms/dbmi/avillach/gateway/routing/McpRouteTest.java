package edu.harvard.hms.dbmi.avillach.gateway.routing;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;

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

import edu.harvard.hms.dbmi.avillach.gateway.config.RouteSurfaceProperties;

/**
 * The {@code mcp} route forwards {@code /mcp} to the MCP service with the path unchanged and the client's {@code Accept} header untouched,
 * since the MCP endpoint answers 400 unless {@code Accept} lists both {@code application/json} and {@code text/event-stream}. Also pins
 * which alternate spellings of the path the router sends to the service.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpRouteTest {

    private static final String ACCEPT = "application/json, text/event-stream";

    static WireMockServer mcpStub;
    static WireMockServer psamaStub;

    @DynamicPropertySource
    static void urls(DynamicPropertyRegistry registry) {
        mcpStub = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort().http2PlainDisabled(true));
        mcpStub.start();
        registry.add("MCP_SERVICE_URL", mcpStub::baseUrl);

        psamaStub = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort().http2PlainDisabled(true));
        psamaStub.start();
        registry.add("TOKEN_INTROSPECTION_URL", () -> psamaStub.baseUrl() + "/auth/token/inspect");
    }

    @AfterAll
    static void stopStubs() {
        mcpStub.stop();
        psamaStub.stop();
    }

    @BeforeEach
    void resetStubs() {
        when(loggingClient.isEnabled()).thenReturn(true);

        mcpStub.resetAll();
        mcpStub.stubFor(post(urlEqualTo("/mcp")).willReturn(aResponse().withStatus(200).withBody("mcp-ok")));

        psamaStub.resetAll();
        psamaStub.stubFor(
            post(urlEqualTo("/auth/token/inspect"))
                .willReturn(okJson("{\"active\":true,\"userId\":\"u-1\",\"sub\":\"s-1\",\"email\":\"a@b\",\"role\":\"USER\"}"))
        );
    }

    @Autowired
    private TestRestTemplate rest;

    @MockitoBean
    private LoggingClient loggingClient;

    @LocalServerPort
    int port;

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private ResponseEntity<String> callMcp(String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer user-token");
        headers.set("Accept", ACCEPT);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(URI.create(url(path)), HttpMethod.POST, new HttpEntity<>("{}", headers), String.class);
    }

    @Test
    void forwardsMcpToTheMcpServiceWithThePathKeptAndAcceptUnchanged() {
        ResponseEntity<String> response = callMcp("/mcp");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("mcp-ok");
        mcpStub.verify(postRequestedFor(urlEqualTo("/mcp")).withHeader("Accept", equalTo(ACCEPT)));
    }

    @Test
    void mcpIsAGatewayOwnedPrefix() {
        assertThat(RouteSurfaceProperties.DEFAULT_OWNED_PREFIXES).contains("/mcp");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/mcp/", "/MCP", "/mcp/x"})
    void spellingsTheRouterDoesNotMatchNeverReachTheMcpService(String path) {
        ResponseEntity<String> response = callMcp(path);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(mcpStub.getAllServeEvents()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/mcp;x=y", "/mcp%2F", "//mcp"})
    void spellingsTheFirewallRejectsNeverReachTheMcpService(String path) {
        ResponseEntity<String> response = callMcp(path);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(mcpStub.getAllServeEvents()).isEmpty();
    }

    /**
     * The router decodes the path before matching, so an encoded first letter still routes to the service, and it is forwarded still
     * encoded. The filters that mark and audit {@code /mcp} match the same decoded path, so this spelling is also marked verified caller
     * {@code mcp}, carries {@code X-Client-Type: mcp} downstream, and is audited as {@code mcp.request}. Marking it does not skip the
     * bearer token check: introspection still runs.
     */
    @Test
    void anEncodedSpellingReachesTheMcpServiceWithTheRawPathKept() {
        callMcp("/%6Dcp");

        assertThat(mcpStub.getAllServeEvents()).hasSize(1);
        assertThat(mcpStub.getAllServeEvents().get(0).getRequest().getUrl()).isEqualTo("/%6Dcp");
        mcpStub.verify(postRequestedFor(urlEqualTo("/%6Dcp")).withHeader("X-Client-Type", equalTo("mcp")));
        psamaStub.verify(1, postRequestedFor(urlEqualTo("/auth/token/inspect")));

        ArgumentCaptor<LoggingEvent> emitted = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient, timeout(5000)).send(emitted.capture(), any(), any());
        assertThat(emitted.getValue().getAction()).isEqualTo("mcp.request");
        assertThat(emitted.getValue().getCaller()).isEqualTo("mcp");
        assertThat(emitted.getValue().getRequest().getUrl()).isEqualTo("/%6Dcp");
    }

    @Test
    void aQueryStringDoesNotChangeTheMatchedPath() {
        callMcp("/mcp?a=b");

        assertThat(mcpStub.getAllServeEvents()).hasSize(1);
        assertThat(mcpStub.getAllServeEvents().get(0).getRequest().getUrl()).isEqualTo("/mcp?a=b");
    }
}
