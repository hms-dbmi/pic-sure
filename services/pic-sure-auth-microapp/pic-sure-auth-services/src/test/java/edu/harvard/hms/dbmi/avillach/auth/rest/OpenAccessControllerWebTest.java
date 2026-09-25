package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.hms.dbmi.avillach.auth.exceptions.GlobalExceptionHandler;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.authorization.AuthorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone MockMvc tests of which HTTP verbs {@code /open/validate} answers, run through the {@link GlobalExceptionHandler} advice the
 * service registers. The gateway's {@code PsamaClient#validateOpenAccess} is the only caller in the codebase and always POSTs.
 */
class OpenAccessControllerWebTest {

    private static final String GATEWAY_BODY = "{\"request\":{\"Target Service\":\"/hpds/open/v3/query/sync\"}}";

    private AuthorizationService authorizationService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        authorizationService = mock(AuthorizationService.class);
        when(authorizationService.openAccessRequestIsValid(any())).thenReturn(true);
        OpenAccessController controller = new OpenAccessController(authorizationService, true);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void postRunsTheValidation() throws Exception {
        mockMvc.perform(post("/open/validate").contentType(MediaType.APPLICATION_JSON).content(GATEWAY_BODY)).andExpect(status().isOk())
            .andExpect(content().string("true"));
    }

    /**
     * Every other verb is turned away before the handler runs, so it never evaluates the open-access rules or answers with their verdict.
     * The status is not pinned here: the catch-all advice currently turns the method-not-supported error into a 500, and it becomes a 405
     * once that advice leaves Spring's MVC exceptions to their standard statuses.
     */
    @Test
    void otherVerbsNeverReachTheValidation() throws Exception {
        List<MockHttpServletRequestBuilder> others =
            List.of(get("/open/validate"), put("/open/validate"), patch("/open/validate"), delete("/open/validate"));
        for (MockHttpServletRequestBuilder other : others) {
            MvcResult result = mockMvc.perform(other.contentType(MediaType.APPLICATION_JSON).content(GATEWAY_BODY)).andReturn();
            String verb = result.getRequest().getMethod();
            assertNotEquals(200, result.getResponse().getStatus(), verb);
            assertNotEquals("true", result.getResponse().getContentAsString(), verb);
        }
        verifyNoInteractions(authorizationService);
    }
}
