package edu.harvard.hms.dbmi.avillach.commons.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Pins the status, headers, title and detail {@link PicsureExceptionAdvice} gives each request error Spring MVC raises and each exception
 * no handler expects. This is the one test that spells out the shared client-visible wording; service tests refer to the constants.
 */
class PicsureExceptionAdviceTest {

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

        @GetMapping("/header")
        public String header(@RequestHeader("X-Probe") String probe) {
            return probe;
        }

        @GetMapping("/missing")
        public String missing() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, "/probe/missing");
        }

        @GetMapping(path = "/page", produces = "text/html")
        public String page() {
            return "<p>page</p>";
        }

        @PostMapping("/upload")
        public String upload() {
            throw new MaxUploadSizeExceededException(1024);
        }

        @GetMapping("/slow")
        public String slow() {
            throw new AsyncRequestTimeoutException();
        }

        @GetMapping("/boom")
        public String boom() {
            throw new IllegalStateException("internal detail");
        }

        @GetMapping("/checked")
        public String checked() throws IOException {
            throw new IOException("internal detail");
        }

        @GetMapping("/denied")
        public String denied() {
            throw new AccessDeniedException("Access Denied");
        }

        @GetMapping("/unauthenticated")
        public String unauthenticated() {
            throw new BadCredentialsException("Bad credentials");
        }

    }

    /** Renders the title and detail the base class hands its subclasses, so the test reads them back unchanged. */
    @RestControllerAdvice
    static class ProbeAdvice extends PicsureExceptionAdvice {

        @Override
        protected Object errorBody(HttpStatusCode status, String title, String detail) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", status.value());
            body.put("title", title);
            body.put("detail", detail);
            return body;
        }
    }

    private final ProbeAdvice advice = new ProbeAdvice();

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController()).setControllerAdvice(advice).build();

    @Test
    void wordingConstantsReadAsPublished() {
        assertThat(PicsureExceptionAdvice.BODY_UNREADABLE).isEqualTo("The request body could not be parsed.");
        assertThat(PicsureExceptionAdvice.missingParameter("q")).isEqualTo("Required parameter 'q' is missing.");
        assertThat(PicsureExceptionAdvice.invalidParameter("id")).isEqualTo("Invalid value for parameter 'id'.");
        assertThat(PicsureExceptionAdvice.NOT_FOUND).isEqualTo("The requested resource does not exist.");
        assertThat(PicsureExceptionAdvice.METHOD_NOT_ALLOWED).isEqualTo("This endpoint does not support the request method.");
        assertThat(PicsureExceptionAdvice.NOT_ACCEPTABLE).isEqualTo("This endpoint cannot produce any of the accepted media types.");
        assertThat(PicsureExceptionAdvice.PAYLOAD_TOO_LARGE).isEqualTo("The request is too large.");
        assertThat(PicsureExceptionAdvice.UNSUPPORTED_MEDIA_TYPE).isEqualTo("This endpoint does not accept the request's content type.");
        assertThat(PicsureExceptionAdvice.CLIENT_ERROR).isEqualTo("The request could not be completed.");
        assertThat(PicsureExceptionAdvice.SERVER_ERROR)
            .isEqualTo("An unexpected error occurred. Please contact the system administrator with the time this error occurred.");
    }

    @Test
    void unreadableJsonBodyIs400() throws Exception {
        mockMvc.perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON).content("{\"name\":"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.title").value("Bad Request"))
            .andExpect(jsonPath("$.detail").value("The request body could not be parsed."));
    }

    @Test
    void missingBodyIs400() throws Exception {
        mockMvc.perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title").value("Bad Request"))
            .andExpect(jsonPath("$.detail").value("The request body could not be parsed."));
    }

    @Test
    void missingRequiredParameterIs400NamingIt() throws Exception {
        mockMvc.perform(get("/probe/search")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.title").value("Bad Request"))
            .andExpect(jsonPath("$.detail").value("Required parameter 'q' is missing."));
    }

    @Test
    void pathTypeMismatchIs400NamingOnlyTheParameter() throws Exception {
        mockMvc.perform(get("/probe/items/abc")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.title").value("Bad Request"))
            .andExpect(jsonPath("$.detail").value("Invalid value for parameter 'id'."))
            .andExpect(content().string(not(containsString("abc"))));
    }

    @Test
    void typeMismatchWithoutAPropertyNameReadsAsAGenericClientError() throws Exception {
        ResponseEntity<Object> response =
            advice.handleException(new TypeMismatchException("abc", Integer.class), new ServletWebRequest(new MockHttpServletRequest()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isInstanceOfSatisfying(
            Map.class,
            body -> assertThat(body).containsEntry("title", "Bad Request").containsEntry("detail", "The request could not be completed.")
        );
    }

    @Test
    void otherClientErrorIs400WithGenericDetail() throws Exception {
        mockMvc.perform(get("/probe/header")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.title").value("Bad Request"))
            .andExpect(jsonPath("$.detail").value("The request could not be completed."));
    }

    @Test
    void missingResourceIs404WithoutThePath() throws Exception {
        mockMvc.perform(get("/probe/missing")).andExpect(status().isNotFound()).andExpect(jsonPath("$.title").value("Not Found"))
            .andExpect(jsonPath("$.detail").value("The requested resource does not exist."))
            .andExpect(content().string(not(containsString("/probe/missing"))));
    }

    @Test
    void unmappedPathIs404WithoutThePath() throws Exception {
        mockMvc.perform(get("/nope")).andExpect(status().isNotFound()).andExpect(jsonPath("$.title").value("Not Found"))
            .andExpect(jsonPath("$.detail").value("The requested resource does not exist."))
            .andExpect(content().string(not(containsString("nope"))));
    }

    @Test
    void wrongMethodIs405WithAllow() throws Exception {
        mockMvc.perform(get("/probe/body")).andExpect(status().isMethodNotAllowed()).andExpect(header().string(HttpHeaders.ALLOW, "POST"))
            .andExpect(jsonPath("$.title").value("Method Not Allowed"))
            .andExpect(jsonPath("$.detail").value("This endpoint does not support the request method."));
    }

    @Test
    void unacceptableMediaTypeIs406() throws Exception {
        mockMvc.perform(get("/probe/page").accept(MediaType.APPLICATION_JSON)).andExpect(status().isNotAcceptable())
            .andExpect(jsonPath("$.title").value("Not Acceptable"))
            .andExpect(jsonPath("$.detail").value("This endpoint cannot produce any of the accepted media types."));
    }

    @Test
    void oversizedUploadIs413() throws Exception {
        mockMvc.perform(post("/probe/upload")).andExpect(status().isPayloadTooLarge())
            .andExpect(jsonPath("$.title").value("Payload Too Large")).andExpect(jsonPath("$.detail").value("The request is too large."));
    }

    @Test
    void unsupportedMediaTypeIs415WithAccept() throws Exception {
        mockMvc.perform(post("/probe/body").contentType(MediaType.TEXT_PLAIN).content("name")).andExpect(status().isUnsupportedMediaType())
            .andExpect(header().string(HttpHeaders.ACCEPT, containsString("application/json")))
            .andExpect(jsonPath("$.title").value("Unsupported Media Type"))
            .andExpect(jsonPath("$.detail").value("This endpoint does not accept the request's content type."));
    }

    @Test
    void frameworkServerErrorKeepsItsStatusWithFixedDetail() throws Exception {
        mockMvc.perform(get("/probe/slow")).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.title").value("Service Unavailable")).andExpect(
                jsonPath("$.detail")
                    .value("An unexpected error occurred. Please contact the system administrator with the time this error occurred.")
            );
    }

    @Test
    void unexpectedRuntimeExceptionIs500WithoutItsMessage() throws Exception {
        mockMvc.perform(get("/probe/boom")).andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.title").value("Internal Server Error"))
            .andExpect(
                jsonPath("$.detail")
                    .value("An unexpected error occurred. Please contact the system administrator with the time this error occurred.")
            ).andExpect(content().string(not(containsString("internal detail"))));
    }

    @Test
    void accessDeniedIsLeftForTheSecurityFilterChain() {
        assertThatThrownBy(() -> mockMvc.perform(get("/probe/denied"))).hasCauseInstanceOf(AccessDeniedException.class);
    }

    @Test
    void authenticationFailureIsLeftForTheSecurityFilterChain() {
        assertThatThrownBy(() -> mockMvc.perform(get("/probe/unauthenticated"))).hasCauseInstanceOf(BadCredentialsException.class);
    }

    @Test
    void unexpectedCheckedExceptionIs500WithoutItsMessage() throws Exception {
        mockMvc.perform(get("/probe/checked")).andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.title").value("Internal Server Error"))
            .andExpect(
                jsonPath("$.detail")
                    .value("An unexpected error occurred. Please contact the system administrator with the time this error occurred.")
            ).andExpect(content().string(not(containsString("internal detail"))));
    }
}
