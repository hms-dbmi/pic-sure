package edu.harvard.hms.dbmi.avillach.ai.model;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import edu.harvard.hms.dbmi.avillach.ai.mcp.ToolDefinition;
import edu.harvard.hms.dbmi.avillach.ai.model.ConversationEntry.AssistantToolCallEntry;
import edu.harvard.hms.dbmi.avillach.ai.model.ConversationEntry.ToolResultEntry;
import edu.harvard.hms.dbmi.avillach.ai.model.ConversationEntry.UserEntry;

/**
 * A second {@link ConverseModelClient}: the same Bedrock Converse wire shape as {@link ConverseSdkModelClient}, but sent as a plain HTTP
 * POST (Spring's {@code RestClient}, no AWS SDK, no SigV4) to a configurable {@code base-url} with {@code Authorization: Bearer <token>}.
 * Active when {@code picsure.ai.model.provider=http}.
 *
 * <p>This is what makes a self-hosted PIC-SURE deployment's "any model API" story actually true: real Bedrock's REST contract for Converse
 * is {@code POST {base}/model/{modelId}/converse} with a JSON body of
 * {@code messages}/{@code system}/{@code toolConfig}/{@code inferenceConfig}, and that contract works over a bare bearer token too -- it's
 * not SigV4-only. A deployer who wants a different vendor points {@code base-url} at either their own small translating proxy, or at a
 * LiteLLM instance's genuine Converse-shaped, bearer-token- authenticated ingress ({@code POST /bedrock/model/{model}/converse}) configured
 * for whatever backend they want -- zero changes to this service. OpenRouter doesn't fit here: it only speaks the OpenAI shape, not
 * Converse.
 */
@Component
@ConditionalOnProperty(prefix = "picsure.ai.model", name = "provider", havingValue = "http")
class ConverseHttpModelClient implements ConverseModelClient {

    private final RestClient restClient;
    private final String apiToken;
    private final String modelId;
    private final ObjectMapper objectMapper;

    ConverseHttpModelClient(
        @Value("${picsure.ai.model.http.base-url:}") String baseUrl, @Value("${picsure.ai.model.http.api-token:}") String apiToken,
        @Value("${picsure.ai.model.http.model-id:}") String modelId, ObjectMapper objectMapper
    ) {
        requireNonBlank(baseUrl, "picsure.ai.model.http.base-url");
        requireNonBlank(apiToken, "picsure.ai.model.http.api-token");
        requireNonBlank(modelId, "picsure.ai.model.http.model-id");
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.apiToken = apiToken;
        this.modelId = modelId;
        this.objectMapper = objectMapper;
    }

    private static void requireNonBlank(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " is required when picsure.ai.model.provider=http");
        }
    }

    @Override
    public ModelTurnResult send(String systemPrompt, List<ConversationEntry> history, List<ToolDefinition> tools) {
        ObjectNode body = objectMapper.createObjectNode();
        body.set("system", oneTextBlockArray(systemPrompt));
        body.set("messages", toMessages(history));
        if (!tools.isEmpty()) {
            body.set("toolConfig", toToolConfig(tools));
        }

        JsonNode response;
        try {
            response = restClient.post().uri("/model/{modelId}/converse", modelId).contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + apiToken).body(body).retrieve().body(JsonNode.class);
        } catch (RestClientException e) {
            throw new ModelUnavailableException("Converse-over-HTTP call failed: " + e.getMessage(), e);
        }

        return toModelTurnResult(response);
    }

    private ModelTurnResult toModelTurnResult(JsonNode response) {
        JsonNode content = response.path("output").path("message").path("content");
        StringBuilder text = new StringBuilder();
        List<RequestedToolCall> toolCalls = new ArrayList<>();
        for (JsonNode block : content) {
            if (block.has("text")) {
                text.append(block.get("text").asText());
            } else if (block.has("toolUse")) {
                JsonNode toolUse = block.get("toolUse");
                toolCalls.add(
                    new RequestedToolCall(
                        toolUse.path("toolUseId").asText(), toolUse.path("name").asText(), toolUse.path("input").toString()
                    )
                );
            }
        }

        boolean done = toolCalls.isEmpty();
        JsonNode usage = response.path("usage");
        return new ModelTurnResult(
            done, text.isEmpty() ? null : text.toString(), toolCalls, usage.path("inputTokens").asInt(0),
            usage.path("outputTokens").asInt(0)
        );
    }

    private ArrayNode toMessages(List<ConversationEntry> history) {
        ArrayNode messages = objectMapper.createArrayNode();
        for (ConversationEntry entry : history) {
            if (entry instanceof UserEntry user) {
                messages.add(roleMessage("user", oneTextBlockArray(user.text())));
            } else if (entry instanceof AssistantToolCallEntry assistant) {
                messages.add(roleMessage("assistant", assistantContent(assistant)));
            } else if (entry instanceof ToolResultEntry toolResult) {
                appendToolResult(messages, toolResult);
            }
        }
        return messages;
    }

    private ArrayNode assistantContent(AssistantToolCallEntry assistant) {
        ArrayNode content = objectMapper.createArrayNode();
        if (assistant.text() != null && !assistant.text().isBlank()) {
            content.add(textBlock(assistant.text()));
        }
        for (RequestedToolCall call : assistant.toolCalls()) {
            ObjectNode toolUse = objectMapper.createObjectNode();
            toolUse.put("toolUseId", call.id());
            toolUse.put("name", call.name());
            toolUse.set("input", parseOrEmptyObject(call.argumentsJson()));
            ObjectNode block = objectMapper.createObjectNode();
            block.set("toolUse", toolUse);
            content.add(block);
        }
        return content;
    }

    /** Consecutive {@link ToolResultEntry}s answering one assistant turn batch into a single Converse "user" message. */
    private void appendToolResult(ArrayNode messages, ToolResultEntry toolResult) {
        ObjectNode toolResultBlock = objectMapper.createObjectNode();
        ObjectNode toolResultBody = objectMapper.createObjectNode();
        toolResultBody.put("toolUseId", toolResult.toolCallId());
        toolResultBody.set("content", oneTextBlockArray(toolResult.content()));
        toolResultBlock.set("toolResult", toolResultBody);

        int lastIndex = messages.size() - 1;
        if (lastIndex >= 0 && isToolResultMessage(messages.get(lastIndex))) {
            ((ArrayNode) messages.get(lastIndex).get("content")).add(toolResultBlock);
        } else {
            ArrayNode content = objectMapper.createArrayNode();
            content.add(toolResultBlock);
            messages.add(roleMessage("user", content));
        }
    }

    private static boolean isToolResultMessage(JsonNode message) {
        JsonNode content = message.path("content");
        return content.size() > 0 && content.get(content.size() - 1).has("toolResult");
    }

    private ObjectNode toToolConfig(List<ToolDefinition> tools) {
        ArrayNode toolArray = objectMapper.createArrayNode();
        for (ToolDefinition tool : tools) {
            ObjectNode toolSpec = objectMapper.createObjectNode();
            toolSpec.put("name", tool.name());
            toolSpec.put("description", tool.description());
            ObjectNode inputSchema = objectMapper.createObjectNode();
            inputSchema.set("json", parseOrEmptyObject(tool.inputSchemaJson()));
            toolSpec.set("inputSchema", inputSchema);
            ObjectNode wrapper = objectMapper.createObjectNode();
            wrapper.set("toolSpec", toolSpec);
            toolArray.add(wrapper);
        }
        ObjectNode toolConfig = objectMapper.createObjectNode();
        toolConfig.set("tools", toolArray);
        return toolConfig;
    }

    private JsonNode parseOrEmptyObject(String json) {
        try {
            return objectMapper.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private ObjectNode roleMessage(String role, ArrayNode content) {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", role);
        message.set("content", content);
        return message;
    }

    private ArrayNode oneTextBlockArray(String text) {
        ArrayNode array = objectMapper.createArrayNode();
        array.add(textBlock(text));
        return array;
    }

    private ObjectNode textBlock(String text) {
        ObjectNode block = objectMapper.createObjectNode();
        block.put("text", text == null ? "" : text);
        return block;
    }
}
