package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import edu.harvard.hms.dbmi.avillach.ai.config.WebSecurityConfig;
import edu.harvard.hms.dbmi.avillach.ai.error.ValidationExceptionAdvice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The chat endpoints need the {@code ASSISTED_SEARCH} privilege the gateway forwards in {@code X-User-Privileges}. A caller without it, an
 * open-access caller and a caller with no identity all get 403, and the orchestrator is never reached. The 404 when the assistant is
 * switched off is in {@link ChatControllerDisabledTest}.
 */
@WebMvcTest(ChatController.class)
@TestPropertySource(properties = "picsure.ai.enabled=true")
@Import(
    {ValidationExceptionAdvice.class, WebSecurityConfig.class, InMemoryTurnResultStore.class, ChatControllerAuthorizationTest.Config.class}
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ChatControllerAuthorizationTest {

    private static final String BODY = "{\"message\":\"hi\",\"conversationId\":\"c1\",\"requestId\":\"r1\"}";
    private static final String TOKEN = "Bearer jwt-a";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AtomicInteger orchestratorCalls;

    @BeforeEach
    void resetCalls() {
        orchestratorCalls.set(0);
    }

    private static MockHttpServletRequestBuilder chat() {
        return post("/chat").contentType(MediaType.APPLICATION_JSON).content(BODY).header("Authorization", TOKEN);
    }

    private static MockHttpServletRequestBuilder asUser(MockHttpServletRequestBuilder request, String privileges) {
        return request.header("X-User-Id", "user-a").header("X-Picsure-Access-Type", "authorized").header("X-User-Privileges", privileges);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ASSISTED_SEARCH", "PIC_SURE_ANY_QUERY,ASSISTED_SEARCH"})
    void aCallerHoldingTheAssistedSearchPrivilegeGets200(String privileges) throws Exception {
        mockMvc.perform(asUser(chat(), privileges)).andExpect(status().isOk());
        assertThat(orchestratorCalls.get()).isEqualTo(1);
    }

    @Test
    void aCallerWithoutThePrivilegeGets403() throws Exception {
        mockMvc.perform(asUser(chat(), "PIC_SURE_ANY_QUERY,NAMED_DATASET")).andExpect(status().isForbidden());
        assertThat(orchestratorCalls.get()).isZero();
    }

    @Test
    void aCallerWithNoPrivilegesAtAllGets403() throws Exception {
        mockMvc.perform(asUser(chat(), "")).andExpect(status().isForbidden());
        mockMvc.perform(chat().header("X-User-Id", "user-a")).andExpect(status().isForbidden());
        assertThat(orchestratorCalls.get()).isZero();
    }

    @Test
    void anOpenAccessCallerGets403() throws Exception {
        mockMvc.perform(chat().header("X-User-Id", "OPEN_ACCESS:localhost").header("X-Picsure-Access-Type", "open"))
            .andExpect(status().isForbidden());
        assertThat(orchestratorCalls.get()).isZero();
    }

    @Test
    void aRequestWithNoIdentityHeadersGets403() throws Exception {
        mockMvc.perform(chat()).andExpect(status().isForbidden());
        assertThat(orchestratorCalls.get()).isZero();
    }

    @Test
    void aClientCannotGrantItselfThePrivilegeThroughTheBody() throws Exception {
        mockMvc.perform(
            asUser(post("/chat").contentType(MediaType.APPLICATION_JSON).header("Authorization", TOKEN), "NAMED_DATASET")
                .content("{\"message\":\"hi\",\"conversationId\":\"c1\",\"requestId\":\"r1\",\"privileges\":[\"ASSISTED_SEARCH\"]}")
        ).andExpect(status().isForbidden());
        assertThat(orchestratorCalls.get()).isZero();
    }

    @Test
    void fetchingAReplyNeedsThePrivilegeToo() throws Exception {
        mockMvc.perform(asUser(chat(), "ASSISTED_SEARCH")).andExpect(status().isOk());

        mockMvc.perform(asUser(get("/chat/r1").header("Authorization", TOKEN), "ASSISTED_SEARCH")).andExpect(status().isOk());
        mockMvc.perform(asUser(get("/chat/r1").header("Authorization", TOKEN), "NAMED_DATASET")).andExpect(status().isForbidden());
        mockMvc.perform(get("/chat/r1").header("Authorization", TOKEN)).andExpect(status().isForbidden());
    }

    @TestConfiguration
    static class Config {

        @Bean
        AtomicInteger orchestratorCalls() {
            return new AtomicInteger();
        }

        @Bean
        ChatOrchestrator chatOrchestrator(AtomicInteger orchestratorCalls) {
            return (request, caller) -> {
                orchestratorCalls.incrementAndGet();
                return ChatResponse.textOnly("ok");
            };
        }
    }
}
