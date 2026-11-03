package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import edu.harvard.hms.dbmi.avillach.ai.config.WebSecurityConfig;
import edu.harvard.hms.dbmi.avillach.ai.error.ValidationExceptionAdvice;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A {@code requestId} runs at most once per caller, and its reply can be fetched again by the same caller only ({@code PLAN.md}'s "Timeouts
 * &amp; partial results").
 */
@WebMvcTest(ChatController.class)
@Import(
    {ValidationExceptionAdvice.class, WebSecurityConfig.class, InMemoryTurnResultStore.class, ChatControllerTurnResultTest.Counting.class}
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ChatControllerTurnResultTest {

    private static final String TOKEN = "Bearer jwt-a";
    private static final String BODY = "{\"message\":\"hi\",\"conversationId\":\"c1\",\"requestId\":\"r1\"}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CountingOrchestrator orchestrator;

    @Autowired
    private TurnResultStore results;

    private ResultActions send(String token) throws Exception {
        return mockMvc.perform(post("/chat").contentType(MediaType.APPLICATION_JSON).content(BODY).header("Authorization", token));
    }

    private ResultActions fetch(String token, String requestId) throws Exception {
        return mockMvc.perform(get("/chat/" + requestId).header("Authorization", token));
    }

    @Test
    void sendingARequestIdAgainReturnsTheStoredReplyWithoutRunningTheTurnAgain() throws Exception {
        String first = send(TOKEN).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String second = send(TOKEN).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertEquals(first, second);
        assertEquals(1, orchestrator.calls.get());
    }

    @Test
    void theReplyCanBeFetchedByItsRequestId() throws Exception {
        String sent = send(TOKEN).andReturn().getResponse().getContentAsString();

        fetch(TOKEN, "r1").andExpect(status().isOk()).andExpect(content().json(sent));
    }

    @Test
    void anotherTokenCannotFetchTheReplyOrReuseIt() throws Exception {
        send(TOKEN).andExpect(status().isOk());

        fetch("Bearer jwt-b", "r1").andExpect(status().isNotFound()).andExpect(jsonPath("$.errorType").value("turn_not_found"));
        send("Bearer jwt-b").andExpect(status().isOk());
        assertEquals(2, orchestrator.calls.get(), "the other caller's turn runs on its own");
    }

    @Test
    void anOpenAccessSessionCanFetchItsReplyAfterItsTokenIsRefreshed() throws Exception {
        String session = "8694e3d4-5cb4-410f-8431-993445e6d3f6";
        String sent = mockMvc
            .perform(
                post("/chat").contentType(MediaType.APPLICATION_JSON).content(BODY).header("Authorization", sessionToken(session, 1))
                    .header("X-User-Id", "OPEN_ACCESS:localhost").header("X-Picsure-Access-Type", "open")
            ).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        mockMvc.perform(
            get("/chat/r1").header("Authorization", sessionToken(session, 500)).header("X-User-Id", "OPEN_ACCESS:localhost")
                .header("X-Picsure-Access-Type", "open")
        ).andExpect(status().isOk()).andExpect(content().json(sent));
        mockMvc.perform(
            get("/chat/r1").header("Authorization", sessionToken("1b4e28ba-2fa1-11d2-883f-0016d3cca427", 1))
                .header("X-User-Id", "OPEN_ACCESS:localhost").header("X-Picsure-Access-Type", "open")
        ).andExpect(status().isNotFound());
    }

    private static String sessionToken(String sessionId, int issuedAt) {
        String payload = "{\"sub\":\"" + sessionId + "\",\"iat\":" + issuedAt + "}";
        return "Bearer picsure_s_eyJhbGciOiJIUzI1NiJ9."
            + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".sig";
    }

    @Test
    void anUnknownRequestIdIsNotFound() throws Exception {
        fetch(TOKEN, "never-sent").andExpect(status().isNotFound());
    }

    @Test
    void aTurnStillRunningIsReportedAsSuchRatherThanRunTwice() throws Exception {
        results.claim(new TurnResultStore.Key(new CallerContext(TOKEN, null, null).owner(), "r1"));

        fetch(TOKEN, "r1").andExpect(status().isAccepted());
        send(TOKEN).andExpect(status().isConflict()).andExpect(jsonPath("$.errorType").value("turn_in_progress"));
        assertEquals(0, orchestrator.calls.get());
    }

    @Test
    void aFailedTurnRunsAgainWhenSentAgain() throws Exception {
        orchestrator.failNext = true;
        send(TOKEN).andExpect(status().isServiceUnavailable());
        fetch(TOKEN, "r1").andExpect(status().isNotFound());

        send(TOKEN).andExpect(status().isOk());
        assertEquals(2, orchestrator.calls.get());
    }

    /** Answers each call with a reply numbering it, so a reply that was not run again is visible. */
    static final class CountingOrchestrator implements ChatOrchestrator {

        final AtomicInteger calls = new AtomicInteger();
        volatile boolean failNext;

        @Override
        public ChatResponse handle(ChatRequest request, CallerContext caller) {
            int call = calls.incrementAndGet();
            if (failNext) {
                failNext = false;
                throw new PicsureException(HttpStatus.SERVICE_UNAVAILABLE, "model_unavailable", "The AI model is currently unavailable.");
            }
            return ChatResponse.textOnly("reply " + call);
        }
    }

    @TestConfiguration
    static class Counting {

        @Bean
        CountingOrchestrator chatOrchestrator() {
            return new CountingOrchestrator();
        }
    }
}
