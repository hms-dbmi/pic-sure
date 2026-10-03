package edu.harvard.hms.dbmi.avillach.mcp;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Drives the running service exactly as an MCP client does: plain HTTP POSTs to {@code /mcp} carrying {@code initialize},
 * {@code notifications/initialized}, {@code tools/list}, and one {@code tools/call} per tool, in that order, with WireMock standing in for
 * the gateway at {@code picsure.mcp.gateway-url}.
 *
 * <p> It checks the stateless transport's answers (JSON bodies, no SSE, no session header), the advertised tools and their schemas, the
 * structured result of every tool, and that each loop-back call to the gateway carries the caller's {@code Authorization}, the MCP
 * credential, and the caller's {@code X-Request-Id}. It also checks the failure layers: a downstream 500 becomes {@code isError} with the
 * model-facing message and none of the downstream body, an unknown tool is a JSON-RPC invalid-params error, a {@code not} in a count query
 * is an {@code isError} naming the field, and a wrong {@code Accept} gets 400. The last step asserts that no response header or body in the
 * whole run holds the caller's bearer token, the caller's API key, or the MCP token.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class McpProtocolIT {

    private static final String BEARER = "Bearer protocol-caller-token-7f3a";
    private static final String API_KEY = "protocol-api-key-91c2";
    private static final String MCP_TOKEN = "protocol-mcp-token-5d8e";
    private static final String PROTOCOL_VERSION = "2025-06-18";
    private static final int INVALID_PARAMS = -32602;
    private static final String ACCEPT = "application/json, text/event-stream";
    private static final String DOWNSTREAM_SECRET = "hpds-internal-detail-4c1b";
    private static final String QUERY_SYNC = "/hpds/open/query/sync";
    private static final Set<String> TOOL_NAMES =
        Set.of("search_concepts", "list_facets", "get_concept", "count_participants", "cross_count", "get_adapter_code");
    private static final Set<String> QUERY_TOOLS = Set.of("count_participants", "cross_count", "get_adapter_code");

    private static final WireMockServer GATEWAY = startGateway();
    private static final List<String> RESPONSES = new CopyOnWriteArrayList<>();
    private static final AtomicInteger REQUEST_IDS = new AtomicInteger();

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @LocalServerPort
    private int port;

    private String requestId;

    private static WireMockServer startGateway() {
        WireMockServer server = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort().http2PlainDisabled(true));
        server.start();
        return server;
    }

    @DynamicPropertySource
    static void gateway(DynamicPropertyRegistry registry) {
        registry.add("picsure.mcp.gateway-url", () -> "http://127.0.0.1:" + GATEWAY.port());
        registry.add("picsure.mcp.service-token", () -> MCP_TOKEN);
        registry.add("picsure.mcp.adapter.base-url", () -> "https://picsure.test");
    }

    @AfterAll
    static void stopGateway() {
        GATEWAY.stop();
    }

    @BeforeEach
    void resetGateway() {
        GATEWAY.resetAll();
        requestId = "protocol-req-" + REQUEST_IDS.incrementAndGet();
    }

    @Test
    @Order(1)
    void initializeAnswersWithOneJsonBodyNoSessionAndOnlyTheToolsCapability() throws Exception {
        HttpResponse<String> response = send(ACCEPT, false, """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",
            "capabilities":{},"clientInfo":{"name":"protocol-it","version":"1.0.0"}}}""");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type"))
            .hasValueSatisfying(type -> assertThat(type).startsWith("application/json"));
        assertThat(response.headers().firstValue("Mcp-Session-Id")).isEmpty();
        assertThat(response.body()).doesNotStartWith("event:").doesNotStartWith("data:");
        JsonNode result = objectMapper.readTree(response.body()).path("result");
        assertThat(result.path("serverInfo").path("name").asText()).isEqualTo("pic-sure");
        assertThat(fieldNames(result.path("capabilities"))).containsExactly("tools");
        assertThat(result.path("instructions").asText()).isNotBlank();
        assertThat(result.path("protocolVersion").asText()).isEqualTo(PROTOCOL_VERSION);
    }

    @Test
    @Order(2)
    void theInitializedNotificationIsAccepted() throws Exception {
        HttpResponse<String> response = send(ACCEPT, true, """
            {"jsonrpc":"2.0","method":"notifications/initialized"}""");

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(response.headers().firstValue("Mcp-Session-Id")).isEmpty();
    }

    @Test
    @Order(3)
    void toolsListAdvertisesSixReadOnlyToolsWithOutputSchemasAndSelfContainedQuerySchemas() throws Exception {
        JsonNode result = result(call("""
            {"jsonrpc":"2.0","id":3,"method":"tools/list","params":{}}"""));

        JsonNode tools = result.path("tools");
        assertThat(tools).extracting(tool -> tool.path("name").asText()).containsExactlyInAnyOrderElementsOf(TOOL_NAMES);
        for (JsonNode tool : tools) {
            String name = tool.path("name").asText();
            JsonNode annotations = tool.path("annotations");
            assertThat(annotations.path("readOnlyHint").isBoolean() && annotations.path("readOnlyHint").asBoolean()).as(name).isTrue();
            assertThat(annotations.path("destructiveHint").isBoolean() && !annotations.path("destructiveHint").asBoolean()).as(name)
                .isTrue();
            assertThat(annotations.path("idempotentHint").isBoolean() && annotations.path("idempotentHint").asBoolean()).as(name).isTrue();
            assertThat(annotations.path("openWorldHint").isBoolean() && !annotations.path("openWorldHint").asBoolean()).as(name).isTrue();
            assertThat(tool.path("outputSchema").isObject()).as(name).isTrue();
            assertThat(tool.path("outputSchema").isEmpty()).as(name).isFalse();
            if (QUERY_TOOLS.contains(name)) {
                assertSelfContained(name, tool.path("inputSchema"));
            }
        }
    }

    @Test
    @Order(4)
    void searchConceptsReturnsTheMappedPage() throws Exception {
        GATEWAY.stubFor(
            post(urlPathEqualTo("/dictionary/concepts")).withQueryParam("page_number", equalTo("0"))
                .withQueryParam("page_size", equalTo("10")).withRequestBody(matchingJsonPath("$.search", equalTo("sex")))
                .willReturn(okJson("""
                    {"content":[
                      {"type":"Categorical","conceptPath":"\\\\phs999999\\\\demographics\\\\sex\\\\","name":"sex","display":"Sex",
                       "dataset":"phs999999","description":"Participant sex","values":["Female","Male"],"allowFiltering":true,
                       "studyAcronym":"SYN","meta":{"unit":"none"},"children":[],"table":{},"study":{}},
                      {"type":"Continuous","conceptPath":"\\\\phs999999\\\\demographics\\\\age\\\\","name":"age","display":"Age",
                       "dataset":"phs999999","description":"Age in years","min":18.0,"max":90.5,"studyAcronym":"SYN"}
                    ],"pageable":{"pageNumber":0},"totalElements":38,"totalPages":4}"""))
        );

        JsonNode structured = successfulCall("search_concepts", """
            {"query":"sex"}""");

        assertThat(structured.path("total").asLong()).isEqualTo(38);
        assertThat(structured.path("concepts")).hasSize(2);
        JsonNode sex = structured.path("concepts").path(0);
        assertThat(sex.path("conceptPath").asText()).isEqualTo("\\phs999999\\demographics\\sex\\");
        assertThat(sex.path("dataset").asText()).isEqualTo("phs999999");
        assertThat(sex.path("type").asText()).isEqualTo("categorical");
        assertThat(sex.path("values")).extracting(JsonNode::asText).containsExactly("Female", "Male");
        assertThat(structured.path("concepts").path(1).path("max").asDouble()).isEqualTo(90.5);
        assertLoopBackCarriesCallerHeaders();
    }

    @Test
    @Order(5)
    void searchConceptsWithTermsMergesOneLoopBackCallPerTerm() throws Exception {
        GATEWAY.stubFor(
            post(urlPathEqualTo("/dictionary/concepts")).withRequestBody(matchingJsonPath("$.search", equalTo("sex"))).willReturn(okJson("""
                {"content":[{"type":"Categorical","conceptPath":"\\\\phs999999\\\\demographics\\\\sex\\\\","display":"Sex",
                 "dataset":"phs999999","values":["Female","Male"]}],"totalElements":1}"""))
        );
        GATEWAY.stubFor(
            post(urlPathEqualTo("/dictionary/concepts")).withRequestBody(matchingJsonPath("$.search", equalTo("gender")))
                .willReturn(okJson("""
                    {"content":[
                      {"type":"Categorical","conceptPath":"\\\\phs999999\\\\demographics\\\\gender\\\\","display":"Gender",
                       "dataset":"phs999999","values":["Woman","Man"]},
                      {"type":"Categorical","conceptPath":"\\\\phs999999\\\\demographics\\\\sex\\\\","display":"Sex",
                       "dataset":"phs999999","values":["Female","Male"]}],"totalElements":2}"""))
        );

        JsonNode structured = successfulCall("search_concepts", """
            {"terms":["sex","gender"]}""");

        assertThat(structured.has("query")).isFalse();
        assertThat(structured.path("terms")).extracting(JsonNode::asText).containsExactly("sex", "gender");
        assertThat(structured.path("total").asLong()).isEqualTo(3);
        assertThat(structured.path("truncated").asBoolean(true)).isFalse();
        assertThat(structured.path("concepts")).hasSize(2);
        assertThat(structured.path("concepts").path(0).path("matchedTerms")).extracting(JsonNode::asText).containsExactly("sex", "gender");
        assertThat(structured.path("concepts").path(1).path("matchedTerms")).extracting(JsonNode::asText).containsExactly("gender");
        assertThat(GATEWAY.findAll(anyRequestedFor(anyUrl()))).hasSize(2);
        assertLoopBackCarriesCallerHeaders();
    }

    @Test
    @Order(6)
    void listFacetsReturnsTheCategories() throws Exception {
        GATEWAY.stubFor(
            post(urlPathEqualTo("/dictionary/facets")).withRequestBody(matchingJsonPath("$.search", equalTo("asthma")))
                .willReturn(okJson("""
                    [{"name":"study_ids","display":"Study","description":"Studies","facets":[
                      {"name":"phs999999","display":"Synthetic study","count":12,"children":[],"meta":{}}]}]"""))
        );

        JsonNode structured = successfulCall("list_facets", """
            {"query":"asthma"}""");

        assertThat(structured.path("query").asText()).isEqualTo("asthma");
        JsonNode category = structured.path("categories").path(0);
        assertThat(category.path("name").asText()).isEqualTo("study_ids");
        assertThat(category.path("facets").path(0).path("name").asText()).isEqualTo("phs999999");
        assertThat(category.path("facets").path(0).path("count").asInt()).isEqualTo(12);
        assertLoopBackCarriesCallerHeaders();
    }

    @Test
    @Order(7)
    void getConceptReturnsTheDetail() throws Exception {
        GATEWAY.stubFor(
            post(urlPathEqualTo("/dictionary/concepts/detail/phs999999")).withRequestBody(equalTo("\\phs999999\\demographics\\age\\"))
                .willReturn(okJson("""
                    {"type":"Continuous","conceptPath":"\\\\phs999999\\\\demographics\\\\age\\\\","name":"age","display":"Age",
                     "dataset":"phs999999","description":"Age in years","min":18.0,"max":90.5,"studyAcronym":"SYN",
                     "children":[{"conceptPath":"child"}],"meta":{"unit":"years"}}"""))
        );

        JsonNode structured = successfulCall("get_concept", """
            {"dataset":"phs999999","conceptPath":"\\\\phs999999\\\\demographics\\\\age\\\\"}""");

        assertThat(structured.path("conceptPath").asText()).isEqualTo("\\phs999999\\demographics\\age\\");
        assertThat(structured.path("type").asText()).isEqualTo("continuous");
        assertThat(structured.path("min").asDouble()).isEqualTo(18.0);
        assertThat(structured.path("meta").path("unit").asText()).isEqualTo("years");
        assertThat(structured.has("children")).isFalse();
        assertLoopBackCarriesCallerHeaders();
    }

    @Test
    @Order(8)
    void countParticipantsReturnsTheParsedCount() throws Exception {
        GATEWAY.stubFor(
            post(urlPathEqualTo(QUERY_SYNC)).withRequestBody(matchingJsonPath("$.query.expectedResultType", equalTo("COUNT")))
                .willReturn(okJson("1234 ±3"))
        );

        JsonNode structured = successfulCall("count_participants", """
            {"query":{"phenotypicClause":{"phenotypicFilterType":"FILTER",
              "conceptPath":"\\\\phs999999\\\\demographics\\\\sex\\\\","values":["Female"]}}}""");

        assertThat(structured.path("display").asText()).isEqualTo("1234 ±3");
        assertThat(structured.path("count").asInt()).isEqualTo(1234);
        assertThat(structured.path("variance").asInt()).isEqualTo(3);
        assertThat(structured.path("suppressed").asBoolean()).isFalse();
        assertLoopBackCarriesCallerHeaders();
    }

    @Test
    @Order(9)
    void crossCountReturnsTheCells() throws Exception {
        GATEWAY.stubFor(
            post(urlPathEqualTo(QUERY_SYNC))
                .withRequestBody(matchingJsonPath("$.query.expectedResultType", equalTo("CATEGORICAL_CROSS_COUNT"))).willReturn(okJson("""
                    {"\\\\phs999999\\\\demographics\\\\sex\\\\":{
                      "Female":{"count":1234,"display":"1234 ±3","variance":3},
                      "Male":{"count":0,"display":"< 10","variance":0}}}"""))
        );

        JsonNode structured = successfulCall("cross_count", """
            {"resultType":"CATEGORICAL_CROSS_COUNT","query":{"phenotypicClause":{"phenotypicFilterType":"FILTER",
              "conceptPath":"\\\\phs999999\\\\demographics\\\\sex\\\\","values":["Female","Male"]}}}""");

        assertThat(structured.path("resultType").asText()).isEqualTo("CATEGORICAL_CROSS_COUNT");
        assertThat(structured.path("totalCells").asInt()).isEqualTo(2);
        JsonNode female = cell(structured, "Female");
        assertThat(female.path("conceptPath").asText()).isEqualTo("\\phs999999\\demographics\\sex\\");
        assertThat(female.path("count").path("count").asInt()).isEqualTo(1234);
        assertThat(cell(structured, "Male").path("count").path("suppressed").asBoolean()).isTrue();
        assertLoopBackCarriesCallerHeaders();
    }

    @Test
    @Order(10)
    void getAdapterCodeReturnsPythonAndChecksTheConceptsThroughTheGateway() throws Exception {
        GATEWAY.stubFor(post(urlPathEqualTo("/dictionary/concepts/detail")).willReturn(okJson("""
            [{"type":"Categorical","conceptPath":"\\\\phs999999\\\\demographics\\\\sex\\\\","name":"sex","display":"Sex",
              "dataset":"phs999999","values":["Female","Male"]}]""")));

        JsonNode structured = successfulCall("get_adapter_code", """
            {"resultType":"count","language":"python","checkConcepts":true,"query":{"phenotypicClause":{
              "phenotypicFilterType":"FILTER","conceptPath":"\\\\phs999999\\\\demographics\\\\sex\\\\","values":["Female"]}}}""");

        assertThat(structured.path("language").asText()).isEqualTo("python");
        assertThat(structured.path("requires").path("package").asText()).isEqualTo("picsure");
        assertThat(structured.path("install").asText()).isNotBlank();
        assertThat(structured.path("code").asText()).contains("picsure.connect(\"https://picsure.test\"", "os.environ[\"PICSURE_TOKEN\"]");
        assertThat(structured.has("warnings")).isFalse();
        assertLoopBackCarriesCallerHeaders();
    }

    @Test
    @Order(11)
    void aDownstreamFailureIsAnIsErrorWithTheModelMessageAndNoDownstreamBody() throws Exception {
        GATEWAY.stubFor(
            post(urlPathEqualTo(QUERY_SYNC)).willReturn(
                aResponse().withStatus(500).withHeader("Content-Type", "text/plain").withBody("stack trace " + DOWNSTREAM_SECRET)
            )
        );

        String body = call(toolCall("count_participants", """
            {"query":{"phenotypicClause":{"phenotypicFilterType":"FILTER",
              "conceptPath":"\\\\phs999999\\\\demographics\\\\sex\\\\","values":["Female"]}}}"""));

        JsonNode result = result(body);
        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.path("content").path(0).path("text").asText()).isEqualTo("The query service is unavailable. Try again shortly.");
        assertThat(result.has("structuredContent")).isFalse();
        assertThat(body).doesNotContain(DOWNSTREAM_SECRET, "stack trace");
        assertLoopBackCarriesCallerHeaders();
    }

    @Test
    @Order(12)
    void anUnknownToolIsAJsonRpcError() throws Exception {
        String body = call(toolCall("drop_tables", "{}"));

        JsonNode json = objectMapper.readTree(body);
        assertThat(json.has("result")).isFalse();
        assertThat(json.path("error").path("code").asInt()).isEqualTo(INVALID_PARAMS);
        assertThat(json.path("error").path("message").asText()).isNotBlank();
        assertThat(GATEWAY.getAllServeEvents()).isEmpty();
    }

    @Test
    @Order(13)
    void aNotInACountQueryIsAnIsErrorNamingTheField() throws Exception {
        JsonNode result = result(call(toolCall("count_participants", """
            {"query":{"phenotypicClause":{"phenotypicFilterType":"FILTER",
              "conceptPath":"\\\\phs999999\\\\demographics\\\\sex\\\\","values":["Female"],"not":true}}}""")));

        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.path("content").path(0).path("text").asText()).isEqualTo("Field 'not' is not part of this tool's input.");
        assertThat(GATEWAY.getAllServeEvents()).isEmpty();
    }

    @Test
    @Order(14)
    void aRequestThatDoesNotAcceptBothJsonAndSseGets400() throws Exception {
        HttpResponse<String> response = send("application/json", true, """
            {"jsonrpc":"2.0","id":13,"method":"tools/list","params":{}}""");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(GATEWAY.getAllServeEvents()).isEmpty();
    }

    @Test
    @Order(15)
    void noResponseHeaderOrBodyInTheRunHoldsTheCallerTokenTheApiKeyOrTheMcpToken() {
        assertThat(RESPONSES).as("responses collected; fewer than 14 means earlier steps did not all reach the server")
            .hasSizeGreaterThanOrEqualTo(14);
        String callerToken = BEARER.substring("Bearer ".length());
        for (String response : RESPONSES) {
            assertThat(response).doesNotContain(callerToken, API_KEY, MCP_TOKEN);
        }
    }

    private JsonNode successfulCall(String tool, String arguments) throws Exception {
        JsonNode result = result(call(toolCall(tool, arguments)));
        assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
        assertThat(result.path("structuredContent").isObject()).as(result.toString()).isTrue();
        return result.path("structuredContent");
    }

    private String toolCall(String tool, String arguments) {
        return """
            {"jsonrpc":"2.0","id":%d,"method":"tools/call","params":{"name":"%s","arguments":%s}}"""
            .formatted(REQUEST_IDS.get() + 100, tool, arguments);
    }

    private String call(String body) throws Exception {
        HttpResponse<String> response = send(ACCEPT, true, body);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type"))
            .hasValueSatisfying(type -> assertThat(type).startsWith("application/json"));
        return response.body();
    }

    private JsonNode result(String body) throws Exception {
        JsonNode json = objectMapper.readTree(body);
        assertThat(json.has("error")).as(body).isFalse();
        return json.path("result");
    }

    private HttpResponse<String> send(String accept, boolean afterInitialize, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
            .header("Content-Type", "application/json").header("Accept", accept).header("Authorization", BEARER)
            .header("X-PICSURE-API-Key", API_KEY).header("X-Request-Id", requestId).POST(HttpRequest.BodyPublishers.ofString(body));
        if (afterInitialize) {
            request.header("MCP-Protocol-Version", PROTOCOL_VERSION);
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        RESPONSES.add(response.headers().map() + "\n" + response.body());
        return response;
    }

    private void assertLoopBackCarriesCallerHeaders() {
        List<LoggedRequest> requests = GATEWAY.findAll(anyRequestedFor(anyUrl()));
        assertThat(requests).isNotEmpty();
        for (LoggedRequest request : requests) {
            assertThat(request.getHeader("Authorization")).as(request.getUrl()).isEqualTo(BEARER);
            assertThat(request.getHeader("X-PIC-SURE-MCP-TOKEN")).as(request.getUrl()).isEqualTo(MCP_TOKEN);
            assertThat(request.getHeader("X-Request-Id")).as(request.getUrl()).isEqualTo(requestId);
        }
    }

    private static JsonNode cell(JsonNode crossCount, String category) {
        for (JsonNode cell : crossCount.path("cells")) {
            if (category.equals(cell.path("category").asText())) {
                return cell;
            }
        }
        throw new AssertionError("No cell for category " + category + " in " + crossCount);
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.properties().forEach(property -> names.add(property.getKey()));
        return names;
    }

    private static void assertSelfContained(String tool, JsonNode schema) {
        JsonNode defs = schema.path("$defs");
        assertThat(defs.isObject() && !defs.isEmpty()).as(tool + " has $defs at the root").isTrue();
        Set<String> refs = new HashSet<>();
        List<String> nestedDefs = new ArrayList<>();
        List<String> notKeys = new ArrayList<>();
        walk(schema, "", true, refs, nestedDefs, notKeys);
        assertThat(nestedDefs).as(tool + " has $defs only at the root").isEmpty();
        assertThat(notKeys).as(tool + " has no not anywhere").isEmpty();
        assertThat(refs).as(tool + " uses $ref").isNotEmpty();
        for (String ref : refs) {
            assertThat(ref).as(tool).startsWith("#/$defs/");
            assertThat(schema.at(ref.substring(1)).isMissingNode()).as(tool + " resolves " + ref).isFalse();
        }
    }

    private static void walk(JsonNode node, String pointer, boolean root, Set<String> refs, List<String> nestedDefs, List<String> notKeys) {
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                walk(node.get(i), pointer + "/" + i, false, refs, nestedDefs, notKeys);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        for (Map.Entry<String, JsonNode> field : node.properties()) {
            String key = field.getKey();
            if ("$ref".equals(key)) {
                refs.add(field.getValue().asText());
            }
            if ("$defs".equals(key) && !root) {
                nestedDefs.add(pointer);
            }
            if ("not".equals(key)) {
                notKeys.add(pointer + "/not");
            }
            walk(field.getValue(), pointer + "/" + key, false, refs, nestedDefs, notKeys);
        }
    }
}
