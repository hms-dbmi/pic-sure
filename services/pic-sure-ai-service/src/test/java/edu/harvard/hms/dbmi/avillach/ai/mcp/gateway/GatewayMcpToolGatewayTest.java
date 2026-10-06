package edu.harvard.hms.dbmi.avillach.ai.mcp.gateway;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import edu.harvard.hms.dbmi.avillach.ai.chat.CallerContext;
import edu.harvard.hms.dbmi.avillach.ai.mcp.ToolDefinition;
import edu.harvard.hms.dbmi.avillach.ai.mcp.ToolResult;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GatewayMcpToolGateway} speaks plain JSON-RPC over HTTP to the gateway's {@code /mcp} route, exactly as {@code pic-sure-mcp}'s own
 * {@code McpProtocolIT} drives it: a stateless POST per call, {@code structuredContent} on success, {@code isError}/{@code content[0].text}
 * or a JSON-RPC {@code error} on failure -- and {@link ToolResult} must never throw back out of {@link GatewayMcpToolGateway#callTool}.
 */
class GatewayMcpToolGatewayTest {

    private static final String RPC_OK = """
        {"jsonrpc":"2.0","id":0,"result":{}}""";

    private WireMockServer server;
    private GatewayMcpToolGateway gateway;

    @BeforeEach
    void start() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
        WireMock.configureFor(server.port());
        server.stubFor(
            post(urlEqualTo("/mcp")).withRequestBody(matchingJsonPath("$.method", equalTo("initialize"))).willReturn(okRpc(RPC_OK))
        );
        server.stubFor(
            post(urlEqualTo("/mcp")).withRequestBody(matchingJsonPath("$.method", equalTo("notifications/initialized")))
                .willReturn(aResponse().withStatus(202))
        );
        gateway = new GatewayMcpToolGateway(server.baseUrl(), new ObjectMapper());
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    void listToolsParsesToolDefinitionsFromToolsList() {
        server.stubFor(post(urlEqualTo("/mcp")).withRequestBody(matchingJsonPath("$.method", equalTo("tools/list"))).willReturn(okRpc("""
            {"jsonrpc":"2.0","id":1,"result":{"tools":[
               {"name":"search_concepts","description":"Search the dictionary.","inputSchema":{"type":"object"}}]}}""")));

        List<ToolDefinition> tools = gateway.listTools();

        assertEquals(1, tools.size());
        assertEquals("search_concepts", tools.get(0).name());
        assertEquals("Search the dictionary.", tools.get(0).description());
    }

    @Test
    void callToolSuccessReadsStructuredContent() {
        server.stubFor(
            post(urlEqualTo("/mcp")).withRequestBody(matchingJsonPath("$.method", equalTo("tools/call")))
                .withRequestBody(matchingJsonPath("$.params.name", equalTo("search_concepts"))).willReturn(okRpc("""
                    {"jsonrpc":"2.0","id":2,"result":{"isError":false,"structuredContent":{"total":1}}}"""))
        );

        ToolResult result = gateway.callTool("search_concepts", "{\"query\":\"bmi\"}", callerWith("Bearer caller-token", "req-1"));

        assertFalse(result.error());
        assertEquals("{\"total\":1}", result.content());
        server.verify(
            postRequestedFor(urlEqualTo("/mcp")).withHeader("Authorization", equalTo("Bearer caller-token"))
                .withHeader("X-Request-Id", equalTo("req-1")).withRequestBody(matchingJsonPath("$.method", equalTo("tools/call")))
        );
    }

    @Test
    void callToolToolLevelFailureBecomesAToolResultFailureNotAnException() {
        server.stubFor(post(urlEqualTo("/mcp")).withRequestBody(matchingJsonPath("$.method", equalTo("tools/call"))).willReturn(okRpc("""
            {"jsonrpc":"2.0","id":2,"result":{"isError":true,"content":[{"type":"text","text":"The query service is unavailable."}]}}""")));

        ToolResult result = gateway.callTool("count_participants", "{}", callerWith("Bearer caller-token", "req-1"));

        assertTrue(result.error());
        assertEquals("The query service is unavailable.", result.content());
    }

    @Test
    void callToolTransportLevelJsonRpcErrorBecomesAToolResultFailure() {
        server.stubFor(post(urlEqualTo("/mcp")).withRequestBody(matchingJsonPath("$.method", equalTo("tools/call"))).willReturn(okRpc("""
            {"jsonrpc":"2.0","id":2,"error":{"code":-32602,"message":"Unknown tool: bogus_tool"}}""")));

        ToolResult result = gateway.callTool("bogus_tool", "{}", callerWith("Bearer caller-token", "req-1"));

        assertTrue(result.error());
        assertEquals("Unknown tool: bogus_tool", result.content());
    }

    @Test
    void callToolOnTransportFailureBecomesAToolResultFailureNotAnException() {
        server.stubFor(
            post(urlEqualTo("/mcp")).withRequestBody(matchingJsonPath("$.method", equalTo("tools/call")))
                .willReturn(aResponse().withStatus(500))
        );

        ToolResult result = gateway.callTool("search_concepts", "{}", callerWith("Bearer caller-token", "req-1"));

        assertTrue(result.error());
    }

    @Test
    void differentCallersAuthorizationHeadersAreNeverMixedUp() {
        server.stubFor(post(urlEqualTo("/mcp")).withRequestBody(matchingJsonPath("$.method", equalTo("tools/call"))).willReturn(okRpc("""
            {"jsonrpc":"2.0","id":2,"result":{"isError":false,"structuredContent":{}}}""")));

        gateway.callTool("search_concepts", "{}", callerWith("Bearer caller-a", "req-a"));
        gateway.callTool("search_concepts", "{}", callerWith("Bearer caller-b", "req-b"));

        server.verify(postRequestedFor(urlEqualTo("/mcp")).withHeader("Authorization", equalTo("Bearer caller-a")));
        server.verify(postRequestedFor(urlEqualTo("/mcp")).withHeader("Authorization", equalTo("Bearer caller-b")));
    }

    private static CallerContext callerWith(String authorization, String requestId) {
        return new CallerContext(authorization, requestId, null);
    }

    private static ResponseDefinitionBuilder okRpc(String body) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
    }
}
