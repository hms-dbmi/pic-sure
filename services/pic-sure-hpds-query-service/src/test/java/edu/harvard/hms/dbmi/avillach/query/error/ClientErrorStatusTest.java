package edu.harvard.hms.dbmi.avillach.query.error;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import edu.harvard.hms.dbmi.avillach.commons.error.PicsureExceptionAdvice;

/**
 * Pins the status, headers and body {@link GlobalExceptionHandler} gives each request error Spring MVC raises before a handler runs, and
 * for an exception no handler expects. Every response carries the {@code {errorType, message, requestId}} body.
 */
class ClientErrorStatusTest {

    private static final String REQUEST_ID = "probe-request";

    @RestController
    @RequestMapping("/probe")
    public static class ProbeController {

        public record Payload(String name) {
        }

        @PostMapping("/body")
        public Payload body(@RequestBody Payload payload) {
            return payload;
        }

        @GetMapping("/items/{id}")
        public String item(@PathVariable("id") int id) {
            return "item";
        }

        @GetMapping("/search")
        public String search(@RequestParam("q") String q) {
            return q;
        }

        @GetMapping("/missing")
        public String missing() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, "/probe/missing");
        }

        @GetMapping(path = "/page", produces = "text/html")
        public String page() {
            return "<p>page</p>";
        }

        @GetMapping("/boom")
        public String boom() {
            throw new IllegalStateException("internal detail");
        }

        @GetMapping("/checked")
        public String checked() throws IOException {
            throw new IOException("internal detail");
        }

    }

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller()).setControllerAdvice(new GlobalExceptionHandler()).build();

    @BeforeEach
    void setRequestId() {
        MDC.put("requestId", REQUEST_ID);
    }

    @AfterEach
    void clearRequestId() {
        MDC.remove("requestId");
    }

    @Test
    void unreadableJsonBodyIs400() throws Exception {
        mockMvc.perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON).content("{\"name\":"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorType").value("bad_request"))
            .andExpect(jsonPath("$.message").value(PicsureExceptionAdvice.BODY_UNREADABLE))
            .andExpect(jsonPath("$.requestId").value(REQUEST_ID));
    }

    @Test
    void missingBodyIs400() throws Exception {
        mockMvc.perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorType").value("bad_request"))
            .andExpect(jsonPath("$.message").value(PicsureExceptionAdvice.BODY_UNREADABLE))
            .andExpect(jsonPath("$.requestId").value(REQUEST_ID));
    }

    @Test
    void unsupportedMediaTypeIs415WithAccept() throws Exception {
        mockMvc.perform(post("/probe/body").contentType(MediaType.TEXT_PLAIN).content("name")).andExpect(status().isUnsupportedMediaType())
            .andExpect(header().string(HttpHeaders.ACCEPT, containsString("application/json")))
            .andExpect(jsonPath("$.errorType").value("unsupported_media_type"))
            .andExpect(jsonPath("$.message").value(PicsureExceptionAdvice.UNSUPPORTED_MEDIA_TYPE))
            .andExpect(jsonPath("$.requestId").value(REQUEST_ID));
    }

    @Test
    void pathTypeMismatchIs400WithoutEchoingTheValue() throws Exception {
        mockMvc.perform(get("/probe/items/abc")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorType").value("bad_request"))
            .andExpect(jsonPath("$.message").value(PicsureExceptionAdvice.invalidParameter("id")))
            .andExpect(jsonPath("$.requestId").value(REQUEST_ID)).andExpect(content().string(not(containsString("abc"))));
    }

    @Test
    void wrongMethodIs405WithAllow() throws Exception {
        mockMvc.perform(get("/probe/body")).andExpect(status().isMethodNotAllowed()).andExpect(header().string(HttpHeaders.ALLOW, "POST"))
            .andExpect(jsonPath("$.errorType").value("method_not_allowed"))
            .andExpect(jsonPath("$.message").value(PicsureExceptionAdvice.METHOD_NOT_ALLOWED))
            .andExpect(jsonPath("$.requestId").value(REQUEST_ID));
    }

    @Test
    void missingRequiredParameterIs400() throws Exception {
        mockMvc.perform(get("/probe/search")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorType").value("bad_request"))
            .andExpect(jsonPath("$.message").value(PicsureExceptionAdvice.missingParameter("q")))
            .andExpect(jsonPath("$.requestId").value(REQUEST_ID));
    }

    @Test
    void missingResourceIs404() throws Exception {
        mockMvc.perform(get("/probe/missing")).andExpect(status().isNotFound()).andExpect(jsonPath("$.errorType").value("not_found"))
            .andExpect(jsonPath("$.message").value(PicsureExceptionAdvice.NOT_FOUND)).andExpect(jsonPath("$.requestId").value(REQUEST_ID));
    }

    @Test
    void unmappedPathIs404() throws Exception {
        mockMvc.perform(get("/nope")).andExpect(status().isNotFound()).andExpect(jsonPath("$.errorType").value("not_found"))
            .andExpect(jsonPath("$.message").value(PicsureExceptionAdvice.NOT_FOUND)).andExpect(jsonPath("$.requestId").value(REQUEST_ID));
    }

    @Test
    void unacceptableMediaTypeIs406() throws Exception {
        mockMvc.perform(get("/probe/page").accept(MediaType.APPLICATION_JSON)).andExpect(status().isNotAcceptable())
            .andExpect(jsonPath("$.errorType").value("not_acceptable"))
            .andExpect(jsonPath("$.message").value(PicsureExceptionAdvice.NOT_ACCEPTABLE))
            .andExpect(jsonPath("$.requestId").value(REQUEST_ID));
    }

    @Test
    void unexpectedRuntimeExceptionIs500() throws Exception {
        mockMvc.perform(get("/probe/boom")).andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.errorType").value("internal_error"))
            .andExpect(jsonPath("$.message").value(PicsureExceptionAdvice.SERVER_ERROR))
            .andExpect(jsonPath("$.requestId").value(REQUEST_ID));
    }

    @Test
    void unexpectedCheckedExceptionIs500() throws Exception {
        mockMvc.perform(get("/probe/checked")).andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.errorType").value("internal_error"))
            .andExpect(jsonPath("$.message").value(PicsureExceptionAdvice.SERVER_ERROR))
            .andExpect(jsonPath("$.requestId").value(REQUEST_ID));
    }

    private static Object controller() {
        return new ProbeController();
    }
}
