package edu.harvard.hms.dbmi.avillach.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Speaks to {@code /mcp} over plain HTTP with the headers the gateway sets, checking that {@code PIC_SURE_MCP} or a verified
 * {@code pic-sure-ai-service} call gets through and that every other caller, including open access and {@code ASSISTED_SEARCH} alone, gets
 * 403.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"PICSURE_GATEWAY_URL=http://gateway.test:8080", "MCP_SERVICE_TOKEN=test-mcp-token",
        "MCP_ADAPTER_BASE_URL=https://picsure.test"}
)
class McpAccessEndToEndTest {

    private static final String INITIALIZE = """
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",
        "capabilities":{},"clientInfo":{"name":"test-client","version":"1.0.0"}}}""";

    @LocalServerPort
    private int port;

    @Test
    void aCallerWithThePrivilegeIsAdmitted() {
        assertThat(post(Map.of("X-User-Id", "user-1", "X-User-Privileges", "PIC_SURE_MCP")).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void thePrivilegeMayBeOneOfSeveral() {
        assertThat(post(Map.of("X-User-Id", "user-1", "X-User-Privileges", "ASSISTED_SEARCH, PIC_SURE_MCP")).getStatusCode().value())
            .isEqualTo(200);
    }

    @Test
    void aVerifiedAiServiceCallIsAdmittedWithoutThePrivilege() {
        assertThat(
            post(Map.of("X-User-Id", "user-1", "X-User-Privileges", "ASSISTED_SEARCH", "X-Client-Type", "ai-service")).getStatusCode()
                .value()
        ).isEqualTo(200);
    }

    @Test
    void assistedSearchAloneIsRefused() {
        ResponseEntity<String> response = post(Map.of("X-User-Id", "user-1", "X-User-Privileges", "ASSISTED_SEARCH"));

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).contains("PIC_SURE_MCP");
    }

    @Test
    void aCallerWithNoPrivilegesIsRefused() {
        assertThat(post(Map.of("X-User-Id", "user-1")).getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void anOpenAccessCallerIsRefused() {
        assertThat(post(Map.of("X-User-Id", "OPEN_ACCESS:host", "X-Picsure-Access-Type", "open")).getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void aRequestWithNoIdentityIsRefusedEvenWithAPrivilegeHeader() {
        assertThat(post(Map.of("X-User-Privileges", "PIC_SURE_MCP")).getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void anotherClientTypeIsNotTreatedAsTheAiService() {
        assertThat(post(Map.of("X-User-Id", "user-1", "X-Client-Type", "web")).getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void theHealthProbeStaysOpen() {
        ResponseEntity<String> response = RestClient.create("http://localhost:" + port).get().uri("/actuator/health/liveness").retrieve()
            .onStatus(status -> true, (request, answer) -> {
            }).toEntity(String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    private ResponseEntity<String> post(Map<String, String> headers) {
        return RestClient.create("http://localhost:" + port).post().uri("/mcp").contentType(MediaType.APPLICATION_JSON)
            .header("Accept", "application/json, text/event-stream").headers(h -> headers.forEach(h::set)).body(INITIALIZE).retrieve()
            .onStatus(status -> true, (request, answer) -> {
            }).toEntity(String.class);
    }
}
