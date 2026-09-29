package edu.harvard.hms.dbmi.avillach.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/**
 * Starts the service on a random port and speaks MCP JSON-RPC to {@code /mcp} over plain HTTP, checking that the stateless transport
 * answers with a JSON body rather than an SSE stream.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"PICSURE_GATEWAY_URL=http://gateway.test:8080", "MCP_SERVICE_TOKEN=test-mcp-token",
        "MCP_ADAPTER_BASE_URL=https://picsure.test"}
)
class McpEndpointTest {

    private static final String ACCEPT_JSON_OR_SSE = "application/json, text/event-stream";

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void initializeAnswersWithJsonAndNamesTheServer() throws Exception {
        JsonNode result = call("""
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",
            "capabilities":{},"clientInfo":{"name":"test-client","version":"1.0.0"}}}""");

        assertThat(result.path("serverInfo").path("name").asText()).isEqualTo("pic-sure");
        JsonNode capabilities = result.path("capabilities");
        assertThat(capabilities.has("tools")).isTrue();
        assertThat(capabilities.has("prompts")).isFalse();
        assertThat(capabilities.has("resources")).isFalse();
        assertThat(capabilities.has("completions")).isFalse();
    }

    @Test
    void toolsListAnswersWithJsonAndNoToolsYet() throws Exception {
        JsonNode result = call("""
            {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""");

        assertThat(result.path("tools").isArray()).isTrue();
        assertThat(result.path("tools")).isEmpty();
    }

    /**
     * Posts one JSON-RPC request to {@code /mcp} and returns its {@code result}.
     *
     * @param body the JSON-RPC request
     * @return the {@code result} member of the JSON-RPC response
     * @throws Exception if the response body is not JSON
     */
    private JsonNode call(String body) throws Exception {
        ResponseEntity<String> response = RestClient.create("http://localhost:" + port).post().uri("/mcp")
            .contentType(MediaType.APPLICATION_JSON).header("Accept", ACCEPT_JSON_OR_SSE).body(body).retrieve().toEntity(String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();

        JsonNode json = objectMapper.readTree(response.getBody());
        assertThat(json.path("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(json.has("error")).isFalse();
        return json.path("result");
    }
}
