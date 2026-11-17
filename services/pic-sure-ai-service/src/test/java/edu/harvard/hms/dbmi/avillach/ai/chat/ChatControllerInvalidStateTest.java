package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import edu.harvard.hms.dbmi.avillach.ai.config.WebSecurityConfig;
import edu.harvard.hms.dbmi.avillach.ai.error.ValidationExceptionAdvice;
import edu.harvard.hms.dbmi.avillach.ai.mcp.McpToolGateway;
import edu.harvard.hms.dbmi.avillach.ai.mcp.ToolDefinition;
import edu.harvard.hms.dbmi.avillach.ai.mcp.ToolResult;
import edu.harvard.hms.dbmi.avillach.ai.model.ConverseModelClient;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An invalid request state (query, facets or search) reaches the caller as HTTP 400 with error type {@code invalid_request}, through the
 * real {@link ToolUseLoopService} and {@link ChatStateValidator}; the model must never be called for it.
 */
@WebMvcTest(ChatController.class)
@TestPropertySource(properties = "picsure.ai.enabled=true")
@Import(
    {ValidationExceptionAdvice.class, WebSecurityConfig.class, InMemoryTurnResultStore.class,
        ChatControllerInvalidStateTest.LoopConfig.class}
)
class ChatControllerInvalidStateTest {

    @Autowired
    private MockMvc mockMvc;

    private void assertInvalidRequest(String body, String field) throws Exception {
        mockMvc
            .perform(
                post("/chat").contentType(MediaType.APPLICATION_JSON).content(body).header("Authorization", "Bearer test-jwt")
                    .header("X-User-Id", "user-a").header("X-User-Privileges", "ASSISTED_SEARCH")
            ).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorType").value("invalid_request"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(field)));
    }

    @Test
    void aQueryThatIsNotAnObjectIsRejectedWith400InvalidRequest() throws Exception {
        assertInvalidRequest("{\"message\":\"hi\",\"conversationId\":\"c1\",\"requestId\":\"r1\",\"query\":\"not an object\"}", "query");
    }

    @Test
    void aMalformedFacetIsRejectedWith400InvalidRequest() throws Exception {
        assertInvalidRequest(
            "{\"message\":\"hi\",\"conversationId\":\"c1\",\"requestId\":\"r1\",\"facets\":[{\"category\":\"study\",\"name\":\" \"}]}",
            "facets"
        );
    }

    @Test
    void aSearchThatIsNotAStringIsRejectedWith400InvalidRequest() throws Exception {
        assertInvalidRequest("{\"message\":\"hi\",\"conversationId\":\"c1\",\"requestId\":\"r1\",\"search\":42}", "search");
    }

    @TestConfiguration
    static class LoopConfig {

        @Bean
        ChatOrchestrator chatOrchestrator() {
            ConverseModelClient model = (systemPrompt, history, tools) -> {
                throw new AssertionError("the model must not be called for an invalid request state");
            };
            McpToolGateway gateway = new McpToolGateway() {
                @Override
                public List<ToolDefinition> listTools(CallerContext caller) {
                    return List.of();
                }

                @Override
                public ToolResult callTool(String name, String argumentsJson, CallerContext caller) {
                    throw new AssertionError("no tool may run for an invalid request state");
                }
            };
            return new ToolUseLoopService(model, gateway, new ProposeQueryTool(new com.fasterxml.jackson.databind.ObjectMapper()), 8);
        }
    }
}
