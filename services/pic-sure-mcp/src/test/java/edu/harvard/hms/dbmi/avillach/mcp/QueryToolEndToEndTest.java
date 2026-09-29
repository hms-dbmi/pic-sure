package edu.harvard.hms.dbmi.avillach.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Posts real {@code tools/call} requests for {@code count_participants} to {@code /mcp}, with a loopback HTTP server standing in for the
 * gateway. Proves the specification bean is registered, the caller's token and the MCP credential reach the gateway with the exact open
 * query, the count comes back as {@code structuredContent}, and a {@code not} in the input is an {@code isError} naming the field.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"MCP_SERVICE_TOKEN=test-mcp-token", "MCP_ADAPTER_BASE_URL=https://picsure.test"}
)
class QueryToolEndToEndTest {

    private static final HttpServer GATEWAY = startGateway();
    private static final List<Recorded> REQUESTS = new CopyOnWriteArrayList<>();

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    private record Recorded(String method, String path, String authorization, String mcpToken, String body) {
    }

    private static HttpServer startGateway() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                REQUESTS.add(
                    new Recorded(
                        exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders().getFirst("Authorization"),
                        exchange.getRequestHeaders().getFirst("X-PIC-SURE-MCP-TOKEN"), body
                    )
                );
                byte[] response = "1234 ±3".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void gatewayUrl(DynamicPropertyRegistry registry) {
        registry.add("PICSURE_GATEWAY_URL", () -> "http://127.0.0.1:" + GATEWAY.getAddress().getPort());
    }

    @AfterAll
    static void stopGateway() {
        GATEWAY.stop(0);
    }

    @BeforeEach
    void clear() {
        REQUESTS.clear();
    }

    @Test
    void countParticipantsReturnsStructuredContentAndSendsTheExactOpenQuery() throws Exception {
        JsonNode result = call(
            """
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"count_participants","arguments":{"query":{
                  "select":["\\\\phs1\\\\ignored\\\\"],
                  "phenotypicClause":{"operator":"AND","phenotypicClauses":[
                    {"phenotypicFilterType":"FILTER","conceptPath":"\\\\phs1\\\\sex\\\\","values":["Female"]},
                    {"operator":"OR","phenotypicClauses":[{"phenotypicFilterType":"FILTER","conceptPath":"\\\\phs1\\\\age\\\\","min":40}]}]}}}}}"""
        );

        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(result.path("structuredContent"))
            .isEqualTo(objectMapper.readTree("{\"display\":\"1234 ±3\",\"count\":1234,\"variance\":3,\"suppressed\":false}"));
        assertThat(objectMapper.readTree(result.path("content").path(0).path("text").asText())).isEqualTo(result.path("structuredContent"));

        assertThat(REQUESTS).hasSize(1);
        Recorded sent = REQUESTS.get(0);
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.path()).isEqualTo("/hpds/open/query/sync");
        assertThat(sent.authorization()).isEqualTo("Bearer caller-token");
        assertThat(sent.mcpToken()).isEqualTo("test-mcp-token");
        assertThat(objectMapper.readTree(sent.body())).isEqualTo(objectMapper.readTree("""
            {"query":{"select":[],"authorizationFilters":[],
              "phenotypicClause":{"not":null,"operator":"AND","phenotypicClauses":[
                {"phenotypicFilterType":"FILTER","conceptPath":"\\\\phs1\\\\sex\\\\","values":["Female"],"min":null,"max":null,"not":null},
                {"not":null,"operator":"OR","phenotypicClauses":[
                  {"phenotypicFilterType":"FILTER","conceptPath":"\\\\phs1\\\\age\\\\","values":null,"min":40.0,"max":null,"not":null}]}]},
              "genomicFilters":[],"expectedResultType":"COUNT","picsureId":null,"id":null}}"""));
    }

    @Test
    void aNotInTheInputIsAnIsErrorNamingTheFieldAndSendsNothing() throws Exception {
        JsonNode result = call(
            """
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"count_participants","arguments":{"query":{
                  "phenotypicClause":{"phenotypicFilterType":"FILTER","conceptPath":"\\\\phs1\\\\sex\\\\","values":["Female"],"not":true}}}}}"""
        );

        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.path("content").path(0).path("text").asText()).isEqualTo("Field 'not' is not part of this tool's input.");
        assertThat(result.has("structuredContent")).isFalse();
        assertThat(REQUESTS).isEmpty();
    }

    @Test
    void toolsListAdvertisesTheQueryToolsWithoutNot() throws Exception {
        JsonNode result = call("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/list\",\"params\":{}}");

        JsonNode count = null;
        for (JsonNode tool : result.path("tools")) {
            if ("count_participants".equals(tool.path("name").asText())) {
                count = tool;
            }
        }
        assertThat(count).isNotNull();
        assertThat(count.path("inputSchema").path("$defs").has("Subquery")).isTrue();
        assertThat(count.path("inputSchema").toString()).doesNotContain("\"not\"");
        assertThat(count.path("outputSchema").path("required")).extracting(JsonNode::asText).containsExactly("display");
    }

    private JsonNode call(String body) throws Exception {
        String response = RestClient.create("http://localhost:" + port).post().uri("/mcp").contentType(MediaType.APPLICATION_JSON)
            .header("Accept", "application/json, text/event-stream").header("Authorization", "Bearer caller-token").body(body).retrieve()
            .body(String.class);
        JsonNode json = objectMapper.readTree(response);
        assertThat(json.has("error")).as(response).isFalse();
        return json.path("result");
    }
}
