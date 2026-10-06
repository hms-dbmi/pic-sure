package edu.harvard.hms.dbmi.avillach.ai.chat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import edu.harvard.hms.dbmi.avillach.ai.config.WebSecurityConfig;
import edu.harvard.hms.dbmi.avillach.commons.error.GatewayExceptionAdvice;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Story 2's two "never touch Bedrock/MCP on a rejected request" ACs: a missing/malformed {@code Authorization} header is rejected with 401,
 * and a missing required field is rejected with 400 and a field-level error -- both before {@link ChatOrchestrator#handle} runs at all,
 * verified here by a {@link ChatOrchestrator} test double that fails the test if it is ever called.
 */
@WebMvcTest(ChatController.class)
@Import({GatewayExceptionAdvice.class, WebSecurityConfig.class, ChatControllerAuthValidationTest.OrchestratorConfig.class})
class ChatControllerAuthValidationTest {

    @Autowired
    private MockMvc mockMvc;

    private static final String VALID_BODY = "{\"message\":\"hi\",\"conversationId\":\"c1\",\"requestId\":\"r1\"}";

    @Test
    void missingAuthorizationHeaderIsRejectedWith401() throws Exception {
        mockMvc.perform(post("/chat").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY)).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.errorType").value("unauthorized"));
    }

    @Test
    void nonBearerAuthorizationHeaderIsRejectedWith401() throws Exception {
        mockMvc.perform(
            post("/chat").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY).header("Authorization", "Basic dXNlcjpwYXNz")
        ).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.errorType").value("unauthorized"));
    }

    @Test
    void missingRequiredFieldIsRejectedWith400AndAFieldLevelError() throws Exception {
        String missingMessage = "{\"message\":\"\",\"conversationId\":\"c1\",\"requestId\":\"r1\"}";

        mockMvc
            .perform(
                post("/chat").contentType(MediaType.APPLICATION_JSON).content(missingMessage).header("Authorization", "Bearer test-jwt")
            ).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorType").value("validation_error"))
            .andExpect(jsonPath("$.fieldErrors.message").exists());
    }

    @TestConfiguration
    static class OrchestratorConfig {

        @Bean
        ChatOrchestrator chatOrchestrator() {
            return (request, caller) -> {
                throw new AssertionError("ChatOrchestrator must not be called for a rejected request");
            };
        }
    }
}
