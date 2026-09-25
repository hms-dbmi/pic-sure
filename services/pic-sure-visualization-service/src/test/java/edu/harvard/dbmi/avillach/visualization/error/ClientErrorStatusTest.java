package edu.harvard.dbmi.avillach.visualization.error;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;

import org.junit.jupiter.api.Test;
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

/**
 * Pins the status, headers and body {@link GlobalExceptionHandler} gives each request error Spring MVC raises before a handler runs, and
 * for an exception no handler expects. Every response carries the {@code {error}} body.
 */
class ClientErrorStatusTest {

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

    @Test
    void unreadableJsonBodyIs400() throws Exception {
        mockMvc.perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON).content("{\"name\":"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("Malformed request body"));
    }

    @Test
    void missingBodyIs400() throws Exception {
        mockMvc.perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Malformed request body"));
    }

    @Test
    void unsupportedMediaTypeIs415WithAccept() throws Exception {
        mockMvc.perform(post("/probe/body").contentType(MediaType.TEXT_PLAIN).content("name")).andExpect(status().isUnsupportedMediaType())
            .andExpect(header().string(HttpHeaders.ACCEPT, containsString("application/json")))
            .andExpect(jsonPath("$.error").value("Content-Type 'text/plain' is not supported."));
    }

    @Test
    void pathTypeMismatchIs400WithoutEchoingTheValue() throws Exception {
        mockMvc.perform(get("/probe/items/abc")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid value for 'id'")).andExpect(content().string(not(containsString("abc"))));
    }

    @Test
    void wrongMethodIs405WithAllow() throws Exception {
        mockMvc.perform(get("/probe/body")).andExpect(status().isMethodNotAllowed()).andExpect(header().string(HttpHeaders.ALLOW, "POST"))
            .andExpect(jsonPath("$.error").value("Method 'GET' is not supported."));
    }

    @Test
    void missingRequiredParameterIs400() throws Exception {
        mockMvc.perform(get("/probe/search")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Required parameter 'q' is not present."));
    }

    @Test
    void missingResourceIs404() throws Exception {
        mockMvc.perform(get("/probe/missing")).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("Not found"));
    }

    @Test
    void unmappedPathIs404() throws Exception {
        mockMvc.perform(get("/nope")).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").exists());
    }

    @Test
    void unexpectedRuntimeExceptionIs500() throws Exception {
        mockMvc.perform(get("/probe/boom")).andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error").value("Internal server error"));
    }

    @Test
    void unexpectedCheckedExceptionIs500() throws Exception {
        mockMvc.perform(get("/probe/checked")).andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error").value("Internal server error"));
    }

    private static Object controller() {
        return new ProbeController();
    }
}
