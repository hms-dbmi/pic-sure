package edu.harvard.hms.dbmi.avillach.ai.chat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import edu.harvard.hms.dbmi.avillach.ai.config.WebSecurityConfig;
import edu.harvard.hms.dbmi.avillach.ai.error.ValidationExceptionAdvice;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code picsure.ai.enabled=false} leaves no chat endpoint to call, so neither route exists. */
@WebMvcTest(ChatController.class)
@Import({ValidationExceptionAdvice.class, WebSecurityConfig.class})
@TestPropertySource(properties = "picsure.ai.enabled=false")
class ChatControllerDisabledTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void chatAnswers404WhenTheAssistantIsSwitchedOff() throws Exception {
        mockMvc.perform(
            post("/chat").contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"hi\",\"conversationId\":\"c\",\"requestId\":\"r\"}").header("Authorization", "Bearer x")
        ).andExpect(status().isNotFound());
    }

    @Test
    void fetchingATurnResultAnswers404Too() throws Exception {
        mockMvc.perform(get("/chat/r").header("Authorization", "Bearer x")).andExpect(status().isNotFound());
    }
}
