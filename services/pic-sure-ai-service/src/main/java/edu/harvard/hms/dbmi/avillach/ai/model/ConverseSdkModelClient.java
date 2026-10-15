package edu.harvard.hms.dbmi.avillach.ai.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.ai.bedrock.converse.BedrockChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import edu.harvard.hms.dbmi.avillach.ai.mcp.ToolDefinition;
import edu.harvard.hms.dbmi.avillach.ai.model.ConversationEntry.AssistantToolCallEntry;
import edu.harvard.hms.dbmi.avillach.ai.model.ConversationEntry.ToolResultEntry;
import edu.harvard.hms.dbmi.avillach.ai.model.ConversationEntry.UserEntry;

/**
 * The default {@link ConverseModelClient}, and the only class in this service that imports a Bedrock- or Spring-AI-model-specific type
 * (enforced by {@code ArchitectureTest}). Delegates to whatever {@link ChatModel} bean the {@code spring-ai-starter-model-bedrock-converse}
 * autoconfiguration provides -- real AWS Bedrock Converse over SigV4/IAM. Active unless {@code picsure.ai.model.provider=http}, in which
 * case {@link ConverseHttpModelClient} runs instead -- same wire shape, plain bearer-token HTTP, no AWS SDK.
 */
@Component
@ConditionalOnProperty(prefix = "picsure.ai.model", name = "provider", havingValue = "aws-sdk", matchIfMissing = true)
class ConverseSdkModelClient implements ConverseModelClient {

    static final String UNKNOWN_TOOL = "unknown_tool";

    private final ChatModel chatModel;

    ConverseSdkModelClient(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public ModelTurnResult send(String systemPrompt, List<ConversationEntry> history, List<ToolDefinition> tools) {
        List<Message> messages = toMessages(systemPrompt, history);
        ChatOptions options = toOptions(tools);

        org.springframework.ai.chat.model.ChatResponse response;
        try {
            response = chatModel.call(new Prompt(messages, options));
        } catch (RuntimeException e) {
            throw new ModelUnavailableException("Bedrock Converse call failed: " + e.getMessage(), e);
        }

        Set<String> knownTools = tools.stream().map(ToolDefinition::name).collect(Collectors.toSet());
        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();

        // Spring AI emits one Generation per Converse content block, with tool calls in their own Generation -- often not the first
        // (a reasoning or empty text block can precede them). Reading only getResult() silently drops the tool call.
        List<RequestedToolCall> toolCalls = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (Generation generation : response.getResults()) {
            AssistantMessage output = generation.getOutput();
            if (output.hasToolCalls()) {
                output.getToolCalls().forEach(tc -> toolCalls.add(new RequestedToolCall(tc.id(), toolName(tc.name(), knownTools), tc.arguments())));
            }
            if (output.getText() != null && !output.getText().isEmpty()) {
                text.append(output.getText());
            }
        }

        return new ModelTurnResult(
            toolCalls.isEmpty(), text.isEmpty() ? null : text.toString(), List.copyOf(toolCalls),
            tokenCount(usage == null ? null : usage.getPromptTokens()), tokenCount(usage == null ? null : usage.getCompletionTokens())
        );
    }

    /** Bedrock rejects a replayed tool name outside {@code [a-zA-Z0-9_-]+}, and gpt-oss leaks template tokens into names. */
    static String toolName(String name, Set<String> knownTools) {
        String valid = RequestedToolCall.validPrefix(name);
        return valid == null || valid.isEmpty() ? UNKNOWN_TOOL : nearestKnown(valid, knownTools);
    }

    /** gpt-oss also garbles names ({@code search_concret}); snap to the one clearly closest known tool. */
    private static String nearestKnown(String name, Set<String> knownTools) {
        if (knownTools.contains(name)) {
            return name;
        }
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        boolean tie = false;
        for (String known : knownTools) {
            int distance = editDistance(name, known);
            if (distance < bestDistance) {
                best = known;
                bestDistance = distance;
                tie = false;
            } else if (distance == bestDistance) {
                tie = true;
            }
        }
        return best != null && !tie && bestDistance <= best.length() / 3 ? best : name;
    }

    private static int editDistance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            int[] current = new int[b.length() + 1];
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j], current[j - 1]) + 1);
            }
            previous = current;
        }
        return previous[b.length()];
    }

    private static int tokenCount(Integer value) {
        return value == null ? 0 : value;
    }

    private static List<Message> toMessages(String systemPrompt, List<ConversationEntry> history) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        for (ConversationEntry entry : history) {
            if (entry instanceof UserEntry user) {
                messages.add(new UserMessage(user.text()));
            } else if (entry instanceof AssistantToolCallEntry assistant) {
                messages.add(toAssistantMessage(assistant));
            } else if (entry instanceof ToolResultEntry toolResult) {
                appendToolResult(messages, toolResult);
            }
        }
        return messages;
    }

    private static AssistantMessage toAssistantMessage(AssistantToolCallEntry assistant) {
        List<AssistantMessage.ToolCall> toolCalls = assistant.toolCalls().stream()
            .map(call -> new AssistantMessage.ToolCall(call.id(), "function", call.name(), call.argumentsJson())).toList();
        return AssistantMessage.builder().content(assistant.text() == null ? "" : assistant.text()).toolCalls(toolCalls).build();
    }

    /** Consecutive {@link ToolResultEntry}s answering one assistant turn batch into a single {@link ToolResponseMessage}. */
    private static void appendToolResult(List<Message> messages, ToolResultEntry toolResult) {
        ToolResponseMessage.ToolResponse response =
            new ToolResponseMessage.ToolResponse(toolResult.toolCallId(), toolResult.toolName(), toolResult.content());
        int lastIndex = messages.size() - 1;
        if (lastIndex >= 0 && messages.get(lastIndex) instanceof ToolResponseMessage existing) {
            List<ToolResponseMessage.ToolResponse> merged = new ArrayList<>(existing.getResponses());
            merged.add(response);
            messages.set(lastIndex, ToolResponseMessage.builder().responses(merged).build());
        } else {
            messages.add(ToolResponseMessage.builder().responses(List.of(response)).build());
        }
    }

    /**
     * Built as {@link BedrockChatOptions} rather than the generic {@code ToolCallingChatOptions}: {@code BedrockProxyChatModel} runs a
     * generic one through {@code ModelOptionsUtils.copyToTarget}, which is suspected of dropping the tool callbacks -- the request then
     * goes out with no {@code toolConfig} and no error.
     */
    private static ChatOptions toOptions(List<ToolDefinition> tools) {
        List<ToolCallback> callbacks = tools.stream().<ToolCallback>map(DefinitionToolCallback::new).toList();
        return BedrockChatOptions.builder().toolCallbacks(callbacks).internalToolExecutionEnabled(false).build();
    }
}
