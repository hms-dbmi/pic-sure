package edu.harvard.hms.dbmi.avillach.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.mcp.caller.CallerHeaders;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.transport.WebMvcStatelessServerTransport;
import org.junit.jupiter.api.Test;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Posts a real {@code tools/call} to {@code /mcp} with the four caller headers set and checks that a tool sees them through
 * {@link CallerHeaders#from}. This proves the transport bound to {@code /mcp} is ours, with its context extractor, and not the starter's.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"PICSURE_GATEWAY_URL=http://gateway.test:8080", "MCP_SERVICE_TOKEN=test-mcp-token",
        "MCP_ADAPTER_BASE_URL=https://picsure.test"}
)
@Import(CallerHeadersEndToEndTest.EchoToolConfig.class)
class CallerHeadersEndToEndTest {

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ApplicationContext context;

    @Test
    void onlyOurTransportIsBound() {
        assertThat(context.getBeansOfType(WebMvcStatelessServerTransport.class)).containsOnlyKeys("picsureStatelessTransport");
    }

    @Test
    void toolSeesTheFourCallerHeaders() throws Exception {
        String response = RestClient.create("http://localhost:" + port).post().uri("/mcp").contentType(MediaType.APPLICATION_JSON)
            .header("Accept", "application/json, text/event-stream").header("Authorization", "Bearer caller-token")
            .header("X-PICSURE-API-Key", "caller-key").header("X-Request-Id", "req-e2e").header("X-Forwarded-For", "203.0.113.7").body("""
                {"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"echo_caller_headers","arguments":{}}}""").retrieve()
            .body(String.class);

        JsonNode result = objectMapper.readTree(response).path("result");
        assertThat(result.path("isError").asBoolean()).isFalse();
        JsonNode seen = objectMapper.readTree(result.path("content").path(0).path("text").asText());
        assertThat(seen.path("authorization").asText()).isEqualTo("Bearer caller-token");
        assertThat(seen.path("apiKey").asText()).isEqualTo("caller-key");
        assertThat(seen.path("requestId").asText()).isEqualTo("req-e2e");
        assertThat(seen.path("forwardedFor").asText()).isEqualTo("203.0.113.7");
    }

    /** Registers the echo tool for this test class only. */
    @TestConfiguration(proxyBeanMethods = false)
    static class EchoToolConfig {

        /**
         * The echo tool.
         *
         * @return a tool that returns the caller headers it received
         */
        @Bean
        EchoTool echoTool() {
            return new EchoTool();
        }
    }

    /** A test-only tool that returns the caller headers the transport context carried. */
    static class EchoTool {

        /**
         * Returns the caller headers the context extractor captured.
         *
         * @param context the MCP transport context
         * @return the captured headers
         */
        @McpTool(name = "echo_caller_headers", description = "Echo the caller headers.")
        public CallerHeaders echo(McpTransportContext context) {
            return CallerHeaders.from(context);
        }
    }
}
