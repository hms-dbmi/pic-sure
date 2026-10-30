package edu.harvard.hms.dbmi.avillach.ai.model;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import edu.harvard.hms.dbmi.avillach.ai.mcp.ToolDefinition;
import edu.harvard.hms.dbmi.avillach.ai.model.ConversationEntry.AssistantToolCallEntry;
import edu.harvard.hms.dbmi.avillach.ai.model.ConversationEntry.ToolResultEntry;
import edu.harvard.hms.dbmi.avillach.ai.model.ConversationEntry.UserEntry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConverseSdkModelClientTest {

    private final ChatModel chatModel = mock(ChatModel.class);
    private final ConverseSdkModelClient client = new ConverseSdkModelClient(chatModel);

    @Test
    void parsesAPlainTextTurnWithUsage() {
        ChatResponse response = ChatResponse.builder().generations(List.of(new Generation(new AssistantMessage("hello"))))
            .metadata(ChatResponseMetadata.builder().usage(new DefaultUsage(10, 5)).build()).build();
        when(chatModel.call(any(Prompt.class))).thenReturn(response);

        ModelTurnResult result = client.send("sys", List.of(new UserEntry("hi")), List.of());

        assertThat(result.done()).isTrue();
        assertThat(result.text()).isEqualTo("hello");
        assertThat(result.inputTokens()).isEqualTo(10);
        assertThat(result.outputTokens()).isEqualTo(5);
    }

    @Test
    void parsesAToolCallTurnAndTreatsMissingUsageAsZero() {
        AssistantMessage message = AssistantMessage.builder().content("")
            .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "search_concepts", "{\"query\":\"bmi\"}"))).build();
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(message))));

        ModelTurnResult result = client.send("sys", List.of(new UserEntry("hi")), List.of());

        assertThat(result.done()).isFalse();
        assertThat(result.toolCalls()).containsExactly(new RequestedToolCall("c1", "search_concepts", "{\"query\":\"bmi\"}"));
        assertThat(result.inputTokens()).isZero();
        assertThat(result.outputTokens()).isZero();
    }

    @Test
    void readsAToolCallFromALaterGenerationAndJoinsTheText() {
        // Spring AI emits one Generation per Converse content block; the tool call often follows a text block.
        AssistantMessage toolCall = AssistantMessage.builder().content("")
            .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "search_concepts", "{\"query\":\"bmi\"}"))).build();
        when(chatModel.call(any(Prompt.class)))
            .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("Searching.")), new Generation(toolCall))));

        ModelTurnResult result = client.send("sys", List.of(new UserEntry("hi")), List.of());

        assertThat(result.done()).isFalse();
        assertThat(result.toolCalls()).containsExactly(new RequestedToolCall("c1", "search_concepts", "{\"query\":\"bmi\"}"));
        assertThat(result.text()).isEqualTo("Searching.");
    }

    @Test
    void sendsSystemUserAssistantAndBatchedToolResultMessagesWithToolsAdvertised() {
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("ok")))));
        List<ConversationEntry> history = List.of(
            new UserEntry("find"),
            new AssistantToolCallEntry(null, List.of(new RequestedToolCall("c1", "a", "{}"), new RequestedToolCall("c2", "b", "{}"))),
            new ToolResultEntry("c1", "a", "r1"), new ToolResultEntry("c2", "b", "r2")
        );

        client.send("sys", history, List.of(new ToolDefinition("a", "desc", "{\"type\":\"object\"}")));

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(captor.capture());
        List<Message> messages = captor.getValue().getInstructions();
        assertThat(messages).hasSize(4);
        assertThat(messages.get(0).getText()).isEqualTo("sys");
        assertThat(messages.get(2)).isInstanceOf(AssistantMessage.class);
        assertThat(((AssistantMessage) messages.get(2)).getToolCalls()).hasSize(2);
        assertThat(((ToolResponseMessage) messages.get(3)).getResponses()).hasSize(2);
        ToolCallingChatOptions options = (ToolCallingChatOptions) captor.getValue().getOptions();
        assertThat(options.getToolCallbacks()).hasSize(1);
        assertThat(options.getInternalToolExecutionEnabled()).isFalse();
    }

    @Test
    void aRuntimeFailureBecomesAModelUnavailableException() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> client.send("sys", List.of(new UserEntry("hi")), List.of()))
            .isInstanceOf(ModelUnavailableException.class).hasMessageContaining("boom");
    }
}
