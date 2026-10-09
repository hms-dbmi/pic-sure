package edu.harvard.hms.dbmi.avillach.commons.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

class PicsureErrorBodyAdviceTest {

    private static final String REQUEST_ID = "req-123";

    @RestController
    @RequestMapping("/probe")
    public static class ProbeController {

        @PostMapping("/body")
        public String body() {
            return "body";
        }

        @GetMapping("/denied")
        public String denied() {
            throw new PicsureException(HttpStatus.FORBIDDEN, "auth.forbidden", "nope");
        }

        @GetMapping("/boom")
        public String boom() {
            throw new IllegalStateException("internal detail");
        }
    }

    private final PicsureErrorBodyAdvice advice = new PicsureErrorBodyAdvice();

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController()).setControllerAdvice(advice).build();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void mapsExceptionToItsStatusWithAdditiveJsonBody() {
        MDC.put("requestId", REQUEST_ID);

        PicsureException exception = new PicsureException(HttpStatus.UNAUTHORIZED, "auth.missing_token", "No authorization header found.");
        ResponseEntity<Map<String, Object>> response = advice.handlePicsureException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("errorType", "auth.missing_token")
            .containsEntry("message", "No authorization header found.").containsEntry("requestId", REQUEST_ID);
    }

    @Test
    void requestIdIsNullWhenMdcIsEmpty() {
        PicsureException exception = new PicsureException(HttpStatus.FORBIDDEN, "auth.forbidden", "nope");
        ResponseEntity<Map<String, Object>> response = advice.handlePicsureException(exception);

        assertThat(response.getBody()).containsEntry("requestId", null);
    }

    @Test
    void picsureExceptionFromAHandlerKeepsItsStatusAndErrorType() throws Exception {
        MDC.put("requestId", REQUEST_ID);

        mockMvc.perform(get("/probe/denied")).andExpect(status().isForbidden()).andExpect(jsonPath("$.errorType").value("auth.forbidden"))
            .andExpect(jsonPath("$.message").value("nope")).andExpect(jsonPath("$.requestId").value(REQUEST_ID));
    }

    @Test
    void frameworkClientErrorUsesTheLowercasedStatusName() throws Exception {
        MDC.put("requestId", REQUEST_ID);

        mockMvc.perform(get("/probe/body")).andExpect(status().isMethodNotAllowed()).andExpect(header().string(HttpHeaders.ALLOW, "POST"))
            .andExpect(jsonPath("$.errorType").value("method_not_allowed"))
            .andExpect(jsonPath("$.message").value(PicsureExceptionAdvice.METHOD_NOT_ALLOWED))
            .andExpect(jsonPath("$.requestId").value(REQUEST_ID));
    }

    @Test
    void unmappedPathIsNotFound() throws Exception {
        mockMvc.perform(get("/nope")).andExpect(status().isNotFound()).andExpect(jsonPath("$.errorType").value("not_found"))
            .andExpect(jsonPath("$.message").value(PicsureExceptionAdvice.NOT_FOUND));
    }

    @Test
    void unexpectedExceptionIsInternalError() throws Exception {
        MDC.put("requestId", REQUEST_ID);

        mockMvc.perform(get("/probe/boom")).andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.errorType").value("internal_error"))
            .andExpect(jsonPath("$.message").value(PicsureExceptionAdvice.SERVER_ERROR))
            .andExpect(jsonPath("$.requestId").value(REQUEST_ID));
    }
}
