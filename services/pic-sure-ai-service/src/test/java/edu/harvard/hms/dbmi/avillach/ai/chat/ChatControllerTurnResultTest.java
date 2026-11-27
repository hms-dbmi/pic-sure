package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
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
@TestPropertySource(properties = "picsure.ai.enabled=true")
@Import(
    {ValidationExceptionAdvice.class, WebSecurityConfig.class, InMemoryTurnResultStore.class, ChatControllerTurnResultTest.Counting.class}
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ChatControllerTurnResultTest {

    private static final String TOKEN = "Bearer jwt-a";
    private static final HttpHeaders ASSISTANT = new HttpHeaders();

    static {
        ASSISTANT.add("X-User-Id", "user-a");
        ASSISTANT.add("X-User-Privileges", "ASSISTED_SEARCH");
    }

    private static final String BODY = "{\"message\":\"hi\",\"conversationId\":\"c1\",\"requestId\":\"r1\"}";

    private static final String BODY_FINGERPRINT = RequestFingerprint.of(new ChatRequest("hi", "c1", "r1", null, null, null));

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CountingOrchestrator orchestrator;

    @Autowired
    private TurnResultStore results;

    private ResultActions send(String token) throws Exception {
        return mockMvc
            .perform(post("/chat").contentType(MediaType.APPLICATION_JSON).content(BODY).header("Authorization", token).headers(ASSISTANT));
    }

    private ResultActions fetch(String token, String requestId) throws Exception {
        return mockMvc.perform(get("/chat/" + requestId).header("Authorization", token).headers(ASSISTANT));
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
    void anUnknownRequestIdIsNotFound() throws Exception {
        fetch(TOKEN, "never-sent").andExpect(status().isNotFound());
    }

    @Test
    void aTurnStillRunningIsReportedAsSuchRatherThanRunTwice() throws Exception {
        results.claim(new TurnResultStore.Key(new CallerContext(TOKEN, null, null).owner(), "r1"), BODY_FINGERPRINT);

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

    @Test
    void aRequestIdReusedForADifferentMessageIsRefusedNotAnswered() throws Exception {
        send(TOKEN).andExpect(status().isOk());

        mockMvc.perform(
            post("/chat").contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"something else\",\"conversationId\":\"c1\",\"requestId\":\"r1\"}").header("Authorization", TOKEN)
                .headers(ASSISTANT)
        ).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errorType").value("request_id_reused"));
        assertEquals(1, orchestrator.calls.get());
    }

    @Test
    void theSameMessageUnderANewRequestIdIsRefusedWhileTheFirstIsRunning() throws Exception {
        results.claim(new TurnResultStore.Key(new CallerContext(TOKEN, null, null).owner(), "r1"), BODY_FINGERPRINT);

        mockMvc.perform(
            post("/chat").contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"hi\",\"conversationId\":\"c1\",\"requestId\":\"r2\"}").header("Authorization", TOKEN)
                .headers(ASSISTANT)
        ).andExpect(status().isConflict()).andExpect(jsonPath("$.errorType").value("duplicate_in_progress"));
        assertEquals(0, orchestrator.calls.get());
    }

    @Test
    void aTurnThatThrowsAnErrorIsReleasedSoTheRetryRuns() throws Exception {
        orchestrator.errorNext = true;
        try {
            send(TOKEN);
        } catch (Exception | Error expected) {
            // the servlet container wraps or rethrows it; only the stored state matters here
        }
        fetch(TOKEN, "r1").andExpect(status().isNotFound());

        send(TOKEN).andExpect(status().isOk());
    }

    /** Answers each call with a reply numbering it, so a reply that was not run again is visible. */
    static final class CountingOrchestrator implements ChatOrchestrator {

        final AtomicInteger calls = new AtomicInteger();
        volatile boolean failNext;
        volatile boolean errorNext;

        @Override
        public ChatResponse handle(ChatRequest request, CallerContext caller) {
            int call = calls.incrementAndGet();
            if (errorNext) {
                errorNext = false;
                throw new StackOverflowError("simulated");
            }
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
