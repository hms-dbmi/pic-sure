package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import edu.harvard.hms.dbmi.avillach.ai.config.WebSecurityConfig;
import edu.harvard.hms.dbmi.avillach.ai.mcp.McpToolGateway;
import edu.harvard.hms.dbmi.avillach.ai.mcp.ToolDefinition;
import edu.harvard.hms.dbmi.avillach.ai.mcp.ToolResult;
import edu.harvard.hms.dbmi.avillach.ai.model.ConverseModelClient;
import edu.harvard.hms.dbmi.avillach.ai.model.ConversationEntry;
import edu.harvard.hms.dbmi.avillach.ai.model.ModelTurnResult;
import edu.harvard.hms.dbmi.avillach.ai.model.RequestedToolCall;
import edu.harvard.hms.dbmi.avillach.commons.error.GatewayExceptionAdvice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Two different users' JWTs produce two different consent-scoped MCP results, with no shared or synthetic credential in between. Runs the
 * real {@link ChatController}, {@link CallerContextArgumentResolver}, and {@link ToolUseLoopService} -- only {@link ConverseModelClient}
 * and the {@link McpToolGateway} are faked, since calling a real model or a real MCP server from a test is out of bounds. The fake gateway
 * echoes the caller's {@code userId} in its result, so the one thing that can differ between the two HTTP responses is which caller the
 * gateway was actually invoked with.
 */
@WebMvcTest(ChatController.class)
@Import({GatewayExceptionAdvice.class, WebSecurityConfig.class, ChatControllerCallerIdentityTest.RealLoopWithFakeModel.class})
class ChatControllerCallerIdentityTest {

    @Autowired
    private MockMvc mockMvc;

    private static final String BODY = "{\"message\":\"find blood pressure concepts\",\"conversationId\":\"c1\",\"requestId\":\"r1\"}";

    @Test
    void twoDifferentCallersGetTwoDifferentMockedMcpResults() throws Exception {
        String firstUsersResponse = chat("Bearer jwt-for-user-a", "user-a");
        String secondUsersResponse = chat("Bearer jwt-for-user-b", "user-b");

        assertThat(firstUsersResponse).contains("user-a");
        assertThat(secondUsersResponse).contains("user-b");
        assertThat(firstUsersResponse).isNotEqualTo(secondUsersResponse);
    }

    private String chat(String authorization, String userId) throws Exception {
        MvcResult result = mockMvc.perform(
            post("/chat").contentType(MediaType.APPLICATION_JSON).content(BODY).header("Authorization", authorization)
                .header("X-User-Id", userId)
        ).andExpect(status().isOk()).andReturn();
        return result.getResponse().getContentAsString();
    }

    @TestConfiguration
    static class RealLoopWithFakeModel {

        @Bean
        ChatOrchestrator chatOrchestrator() {
            return new ToolUseLoopService(echoingFakeModel(), new CallerEchoingToolGateway(), new ProposeQueryTool(new ObjectMapper()), 8);
        }

        /** Echoes the caller's {@code userId} in every result, so two different callers visibly get two different results. */
        private static final class CallerEchoingToolGateway implements McpToolGateway {

            @Override
            public List<ToolDefinition> listTools(CallerContext caller) {
                return List.of();
            }

            @Override
            public ToolResult callTool(String name, String argumentsJson, CallerContext caller) {
                return ToolResult.success("{\"caller\":\"" + caller.userId() + "\"}");
            }
        }

        private static ConverseModelClient echoingFakeModel() {
            return (systemPrompt, history, tools) -> {
                boolean alreadyCalledSearch = history.stream().anyMatch(e -> e instanceof ConversationEntry.ToolResultEntry);
                if (!alreadyCalledSearch) {
                    return new ModelTurnResult(
                        false, null, List.of(new RequestedToolCall("call-1", "search_concepts", "{\"query\":\"blood pressure\"}")), 1, 1
                    );
                }
                ConversationEntry.ToolResultEntry toolResult = history.stream().filter(e -> e instanceof ConversationEntry.ToolResultEntry)
                    .map(e -> (ConversationEntry.ToolResultEntry) e).reduce((first, second) -> second).orElseThrow();
                return new ModelTurnResult(true, toolResult.content(), List.of(), 1, 1);
            };
        }
    }
}
