package edu.harvard.hms.dbmi.avillach.ai.mcp.gateway;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import edu.harvard.hms.dbmi.avillach.ai.chat.CallerContext;
import edu.harvard.hms.dbmi.avillach.ai.error.ErrorSummary;
import edu.harvard.hms.dbmi.avillach.ai.mcp.McpToolGateway;
import edu.harvard.hms.dbmi.avillach.ai.mcp.ToolDefinition;
import edu.harvard.hms.dbmi.avillach.ai.mcp.ToolResult;
import edu.harvard.hms.dbmi.avillach.commons.request.RequestIdFilter;

/**
 * The real {@link McpToolGateway}: a plain JSON-RPC-over-HTTP client against {@code pic-sure-mcp}'s stateless streamable-HTTP transport,
 * reached through the gateway's {@code /mcp} route. Active when {@code picsure.ai.mcp.mode=gateway}.
 *
 * <p>Deliberately hand-rolled rather than built on the official MCP Java SDK client, the same trade-off {@code ConverseHttpModelClient}
 * already makes on the model side: the server's transport is stateless (no {@code Mcp-Session-Id}, confirmed by {@code pic-sure-mcp}'s
 * {@code McpProtocolIT}), and the caller's identity changes on every chat request, which doesn't fit a singleton SDK session cleanly. A
 * plain {@code RestClient} POST per call, with the caller's headers attached per call, is simpler and gives full control.
 *
 * <p>The {@code initialize}/{@code notifications/initialized} handshake is done once, lazily, and not repeated after it succeeds --
 * {@code tools/list} and {@code tools/call} are then independent, per-call, caller-scoped requests. The server holds no per-caller state,
 * but the gateway introspects every {@code /mcp} request, so the handshake replays the triggering caller's token like any other call.
 */
@Component
@ConditionalOnProperty(prefix = "picsure.ai.mcp", name = "mode", havingValue = "gateway", matchIfMissing = true)
public class GatewayMcpToolGateway implements McpToolGateway {

    private static final Logger log = LoggerFactory.getLogger(GatewayMcpToolGateway.class);

    private static final String PROTOCOL_VERSION = "2025-06-18";
    private static final MediaType EVENT_STREAM = MediaType.valueOf("text/event-stream");
    private static final String UNAVAILABLE = "The dictionary/query service is unavailable right now. Try again shortly.";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private volatile boolean initialized;

    /** @param readTimeout without one the JDK client waits forever, hanging the chat turn */
    public GatewayMcpToolGateway(
        @Value("${picsure.ai.mcp.gateway-url:}") String gatewayUrl, ObjectMapper objectMapper,
        @Value("${picsure.ai.mcp.connect-timeout:5s}") Duration connectTimeout, @Value("${picsure.ai.mcp.read-timeout:20s}") Duration readTimeout
    ) {
        requireNonBlank(gatewayUrl);
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        this.restClient = RestClient.builder().baseUrl(gatewayUrl + "/mcp").requestFactory(requestFactory).build();
        this.objectMapper = objectMapper;
    }

    private static void requireNonBlank(String gatewayUrl) {
        if (gatewayUrl == null || gatewayUrl.isBlank()) {
            throw new IllegalStateException("picsure.ai.mcp.gateway-url is required when picsure.ai.mcp.mode=gateway");
        }
    }

    @Override
    public List<ToolDefinition> listTools(CallerContext caller) {
        ensureInitialized(caller);
        JsonNode response = post(rpcRequest(1, "tools/list", objectMapper.createObjectNode()), caller);
        // Thrown, not read as an empty list: with no tools the turn would still run, and the model would answer from memory.
        if (response == null || !response.path("result").path("tools").isArray()) {
            String message = response == null ? "empty response" : response.path("error").path("message").asText("no tool list");
            throw new IllegalStateException("MCP tools/list failed: " + message);
        }
        List<ToolDefinition> tools = new ArrayList<>();
        for (JsonNode tool : response.path("result").path("tools")) {
            tools.add(
                new ToolDefinition(tool.path("name").asText(), tool.path("description").asText(), tool.path("inputSchema").toString())
            );
        }
        return tools;
    }

    @Override
    public ToolResult callTool(String name, String argumentsJson, CallerContext caller) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("name", name);
        params.set("arguments", parseOrEmptyObject(argumentsJson));

        JsonNode response;
        try {
            // Inside the try: a failed handshake is the same outage to the model, and its raw message (URL, status) must not reach it.
            ensureInitialized(caller);
            response = post(rpcRequest(2, "tools/call", params), caller);
        } catch (RestClientException e) {
            log.warn("MCP tools/call failed: tool={} error={}", name, ErrorSummary.of(e));
            return ToolResult.failure(UNAVAILABLE);
        }
        if (response == null) {
            log.warn("MCP tools/call returned no body: tool={}", name);
            return ToolResult.failure(UNAVAILABLE);
        }

        JsonNode error = response.path("error");
        if (!error.isMissingNode()) {
            return ToolResult.failure(error.path("message").asText("The dictionary/query service returned an error."));
        }

        JsonNode result = response.path("result");
        if (result.path("isError").asBoolean(false)) {
            return ToolResult.failure(result.path("content").path(0).path("text").asText("The tool call failed."));
        }
        // A tool that returns only text content (no structuredContent) still answers; reading just structuredContent would send "".
        JsonNode structured = result.path("structuredContent");
        return ToolResult.success(structured.isMissingNode() || structured.isNull() ? result.path("content").path(0).path("text").asText("")
            : structured.toString());
    }

    /**
     * Sends {@code initialize} then {@code notifications/initialized} once; a no-op on every call after the first success. Concurrent first
     * calls wait for the one handshake rather than going ahead before it is done; a failed handshake is retried by the next call.
     */
    private void ensureInitialized(CallerContext caller) {
        if (initialized) {
            return;
        }
        synchronized (this) {
            if (initialized) {
                return;
            }
            ObjectNode params = objectMapper.createObjectNode();
            params.put("protocolVersion", PROTOCOL_VERSION);
            params.set("capabilities", objectMapper.createObjectNode());
            ObjectNode clientInfo = objectMapper.createObjectNode();
            clientInfo.put("name", "pic-sure-ai-service");
            clientInfo.put("version", "1.0.0");
            params.set("clientInfo", clientInfo);
            post(rpcRequest(0, "initialize", params), caller);

            ObjectNode notification = objectMapper.createObjectNode();
            notification.put("jsonrpc", "2.0");
            notification.put("method", "notifications/initialized");
            post(notification, caller);
            initialized = true;
        }
    }

    private JsonNode post(ObjectNode body, CallerContext caller) {
        return restClient.post().contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON, EVENT_STREAM)
            .headers(headers -> {
                if (caller != null) {
                    caller.applyTo(headers);
                    applyRequestId(headers, caller);
                }
            }).body(body).retrieve().body(JsonNode.class);
    }

    private static void applyRequestId(HttpHeaders headers, CallerContext caller) {
        if (caller.requestId() != null && !caller.requestId().isBlank()) {
            headers.set(RequestIdFilter.HEADER, caller.requestId());
        }
    }

    private ObjectNode rpcRequest(int id, String method, ObjectNode params) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.set("params", params);
        return request;
    }

    private JsonNode parseOrEmptyObject(String json) {
        try {
            return objectMapper.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }
}
