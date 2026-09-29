package edu.harvard.hms.dbmi.avillach.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingEvent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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

    @MockitoBean
    private LoggingClient loggingClient;

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

        ArgumentCaptor<LoggingEvent> audit = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient, times(1)).send(audit.capture(), isNull(), org.mockito.ArgumentMatchers.eq("req-audit"));
        assertThat(audit.getValue().getEventType()).isEqualTo("QUERY");
        assertThat(audit.getValue().getAction()).isEqualTo("query.sync");
        assertThat(audit.getValue().getMetadata()).containsEntry("outcome", "success").containsEntry("result_type", "COUNT")
            .containsEntry("user_id", "user-e2e");

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
        ArgumentCaptor<LoggingEvent> audit = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient, times(1)).send(audit.capture(), isNull(), org.mockito.ArgumentMatchers.eq("req-audit"));
        assertThat(audit.getValue().getAction()).isEqualTo("query.sync");
        assertThat(audit.getValue().getMetadata()).containsEntry("outcome", "failure");
    }

    @Test
    void getAdapterCodeReturnsStructuredContentASetupBlockAndACodeBlockForTheUser() throws Exception {
        String response = post("""
            {"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"get_adapter_code","arguments":{
              "resultType":"count","language":"python","query":{
              "phenotypicClause":{"phenotypicFilterType":"FILTER","conceptPath":"\\\\phs1\\\\sex\\\\","values":["Female"]}}}}}""");
        JsonNode result = objectMapper.readTree(response).path("result");

        assertThat(result.path("isError").asBoolean()).isFalse();
        JsonNode structured = result.path("structuredContent");
        assertThat(structured.path("language").asText()).isEqualTo("python");
        assertThat(structured.path("requires"))
            .isEqualTo(objectMapper.readTree("{\"package\":\"picsure\",\"minVersion\":\"3.0.0\",\"runtime\":\"Python >= 3.10\"}"));
        assertThat(structured.path("install").asText()).isEqualTo("pip install 'picsure>=3.0.0'");
        assertThat(structured.path("code").asText())
            .contains("picsure.connect(\"https://picsure.test\", token=os.environ[\"PICSURE_TOKEN\"]").contains("categories=[\"Female\"]")
            .endsWith("print(count.raw)\n");
        JsonNode content = result.path("content");
        assertThat(content).hasSize(2);
        assertThat(content.path(0).path("text").asText()).startsWith("Requires picsure >= 3.0.0 (Python >= 3.10).");
        assertThat(content.path(0).has("annotations")).isFalse();
        assertThat(content.path(1).path("text").asText()).isEqualTo(structured.path("code").asText());
        assertThat(content.path(1).path("annotations").path("audience")).isEqualTo(objectMapper.readTree("[\"user\"]"));
        assertThat(response).doesNotContain("caller-token", "test-mcp-token");
        assertThat(REQUESTS).isEmpty();

        ArgumentCaptor<LoggingEvent> audit = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient, times(1)).send(audit.capture(), isNull(), org.mockito.ArgumentMatchers.eq("req-audit"));
        assertThat(audit.getValue().getEventType()).isEqualTo("OTHER");
        assertThat(audit.getValue().getAction()).isEqualTo("adapter.code");
        assertThat(audit.getValue().getMetadata()).containsEntry("outcome", "success").containsEntry("result_type", "count");
    }

    @Test
    void getAdapterCodeWritesRAndBashThroughTheWiredGenerators() throws Exception {
        String template = """
            {"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"get_adapter_code","arguments":{
              "resultType":"participant","language":"%s","query":{"select":["\\\\phs1\\\\bmi\\\\"],
              "phenotypicClause":{"phenotypicFilterType":"FILTER","conceptPath":"\\\\phs1\\\\sex\\\\","values":["Female"]}}}}}""";

        String r = post(template.formatted("r"));
        String bash = post(template.formatted("bash"));
        JsonNode rResult = objectMapper.readTree(r).path("result");
        JsonNode bashResult = objectMapper.readTree(bash).path("result");

        assertThat(rResult.path("isError").asBoolean()).isFalse();
        assertThat(rResult.path("structuredContent").path("requires").path("minVersion").asText()).isEqualTo("v3.0.0");
        assertThat(rResult.path("structuredContent").path("code").asText())
            .contains("session <- picsure::connect(\"https://picsure.test\", token = Sys.getenv(\"PICSURE_TOKEN\")")
            .contains("picsure::exportCSV(session, df, output_path)");
        assertThat(bashResult.path("isError").asBoolean()).isFalse();
        assertThat(bashResult.path("structuredContent").path("requires").has("minVersion")).isFalse();
        assertThat(bashResult.path("structuredContent").path("code").asText()).startsWith("#!/usr/bin/env bash\n")
            .contains("query_url='https://picsure.test/picsure/hpds/auth/query'", "\"expectedResultType\" : \"DATAFRAME\"");
        assertThat(bashResult.path("content").path(1).path("annotations").path("audience")).isEqualTo(objectMapper.readTree("[\"user\"]"));
        assertThat(r + bash).doesNotContain("caller-token", "test-mcp-token");
        assertThat(REQUESTS).isEmpty();
    }

    @Test
    void anAnnotatedDictionaryToolCallIsAuditedThroughItsProxy() throws Exception {
        call("""
            {"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"search_concepts","arguments":{"query":"sex","page":1}}}""");

        ArgumentCaptor<LoggingEvent> audit = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient, times(1)).send(audit.capture(), isNull(), org.mockito.ArgumentMatchers.eq("req-audit"));
        assertThat(audit.getValue().getEventType()).isEqualTo("SEARCH");
        assertThat(audit.getValue().getAction()).isEqualTo("concept.search");
        assertThat(audit.getValue().getMetadata()).containsEntry("query", "sex").containsEntry("page", 1);
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
        return objectMapper.readTree(post(body)).path("result");
    }

    private String post(String body) throws Exception {
        String response = RestClient.create("http://localhost:" + port).post().uri("/mcp").contentType(MediaType.APPLICATION_JSON)
            .header("Accept", "application/json, text/event-stream").header("Authorization", "Bearer caller-token")
            .header("X-Request-Id", "req-audit").header("X-User-Id", "user-e2e").body(body).retrieve().body(String.class);
        JsonNode json = objectMapper.readTree(response);
        assertThat(json.has("error")).as(response).isFalse();
        return response;
    }
}
