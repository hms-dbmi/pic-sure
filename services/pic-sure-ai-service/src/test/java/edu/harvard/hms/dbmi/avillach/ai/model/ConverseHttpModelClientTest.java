package edu.harvard.hms.dbmi.avillach.ai.model;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import edu.harvard.hms.dbmi.avillach.ai.mcp.ToolDefinition;
import edu.harvard.hms.dbmi.avillach.ai.model.ConversationEntry.AssistantToolCallEntry;
import edu.harvard.hms.dbmi.avillach.ai.model.ConversationEntry.ToolResultEntry;
import edu.harvard.hms.dbmi.avillach.ai.model.ConversationEntry.UserEntry;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ConverseHttpModelClient} sends the real Bedrock Converse REST shape ({@code POST /model/{modelId}/converse},
 * {@code messages}/{@code system}/{@code toolConfig} body) over a plain bearer token, and parses that same shape back -- the whole point
 * being that a self-hosted deployment can point this at LiteLLM's Converse-shaped ingress or their own proxy with zero code changes. These
 * tests pin the wire contract on both directions.
 */
class ConverseHttpModelClientTest {

    private WireMockServer server;
    private ConverseHttpModelClient client;

    @BeforeEach
    void start() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
        WireMock.configureFor(server.port());
        client = new ConverseHttpModelClient(server.baseUrl(), "test-token", "test-model", new ObjectMapper());
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    void sendsTheConverseShapeWithBearerAuthAndParsesAPlainTextTurn() {
        server.stubFor(
            post(urlEqualTo("/model/test-model/converse"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
                    {"output":{"message":{"role":"assistant","content":[{"text":"hello there"}]}},
                     "stopReason":"end_turn","usage":{"inputTokens":10,"outputTokens":5}}"""))
        );

        ModelTurnResult result = client.send("system prompt", List.of(new UserEntry("hi")), List.of());

        assertTrue(result.done());
        assertEquals("hello there", result.text());
        assertEquals(10, result.inputTokens());
        assertEquals(5, result.outputTokens());

        server.verify(
            postRequestedFor(urlEqualTo("/model/test-model/converse")).withHeader("Authorization", equalTo("Bearer test-token"))
                .withRequestBody(matchingJsonPath("$.system[0].text", equalTo("system prompt")))
                .withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("user")))
                .withRequestBody(matchingJsonPath("$.messages[0].content[0].text", equalTo("hi")))
        );
    }

    @Test
    void parsesAToolUseTurnAndOmitsToolConfigWhenNoToolsAreOffered() {
        server.stubFor(
            post(urlEqualTo("/model/test-model/converse"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
                    {"output":{"message":{"role":"assistant","content":[
                         {"toolUse":{"toolUseId":"call-1","name":"search_concepts","input":{"query":"bmi"}}}]}},
                     "stopReason":"tool_use","usage":{"inputTokens":1,"outputTokens":1}}"""))
        );

        ModelTurnResult result = client.send("system prompt", List.of(new UserEntry("find bmi")), List.of());

        assertTrue(result.hasToolCalls());
        RequestedToolCall call = result.toolCalls().get(0);
        assertEquals("call-1", call.id());
        assertEquals("search_concepts", call.name());
        assertEquals("bmi", readJson(call.argumentsJson()).path("query").asText());

        String sentBody = server.getAllServeEvents().get(0).getRequest().getBodyAsString();
        assertTrue(readJson(sentBody).path("toolConfig").isMissingNode(), "no tools offered, so toolConfig must be omitted");
    }

    @Test
    void sendsToolDefinitionsAndTheFullToolResultRoundTrip() {
        server.stubFor(
            post(urlEqualTo("/model/test-model/converse"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
                    {"output":{"message":{"role":"assistant","content":[{"text":"done"}]}},
                     "stopReason":"end_turn","usage":{"inputTokens":1,"outputTokens":1}}"""))
        );
        ToolDefinition tool = new ToolDefinition("search_concepts", "search", "{\"type\":\"object\"}");
        List<ConversationEntry> history = List.of(
            new UserEntry("find bmi"),
            new AssistantToolCallEntry(null, List.of(new RequestedToolCall("call-1", "search_concepts", "{\"query\":\"bmi\"}"))),
            new ToolResultEntry("call-1", "search_concepts", "{\"ok\":true}")
        );

        client.send("system prompt", history, List.of(tool));

        server.verify(
            postRequestedFor(urlEqualTo("/model/test-model/converse"))
                .withRequestBody(matchingJsonPath("$.toolConfig.tools[0].toolSpec.name", equalTo("search_concepts")))
                .withRequestBody(matchingJsonPath("$.messages[1].content[0].toolUse.toolUseId", equalTo("call-1")))
                .withRequestBody(matchingJsonPath("$.messages[2].content[0].toolResult.toolUseId", equalTo("call-1")))
        );
    }

    @Test
    void aNonOkResponseBecomesAModelUnavailableException() {
        server.stubFor(post(urlEqualTo("/model/test-model/converse")).willReturn(aResponse().withStatus(500)));

        assertThrows(ModelUnavailableException.class, () -> client.send("system prompt", List.of(new UserEntry("hi")), List.of()));
    }

    private static JsonNode readJson(String json) {
        try {
            return new ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
