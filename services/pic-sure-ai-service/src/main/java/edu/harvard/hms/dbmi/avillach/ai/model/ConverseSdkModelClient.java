package edu.harvard.hms.dbmi.avillach.ai.model;

import java.util.ArrayList;
import java.util.List;

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
import org.springframework.ai.model.tool.ToolCallingChatOptions;
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

        Generation result = response.getResult();
        AssistantMessage output = result.getOutput();
        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();

        List<RequestedToolCall> toolCalls = output.hasToolCalls()
            ? output.getToolCalls().stream().map(tc -> new RequestedToolCall(tc.id(), tc.name(), tc.arguments())).toList()
            : List.of();

        return new ModelTurnResult(
            !output.hasToolCalls(), output.getText(), toolCalls, tokenCount(usage == null ? null : usage.getPromptTokens()),
            tokenCount(usage == null ? null : usage.getCompletionTokens())
        );
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

    private static ChatOptions toOptions(List<ToolDefinition> tools) {
        List<ToolCallback> callbacks = tools.stream().<ToolCallback>map(DefinitionToolCallback::new).toList();
        return ToolCallingChatOptions.builder().toolCallbacks(callbacks).internalToolExecutionEnabled(false).build();
    }
}
