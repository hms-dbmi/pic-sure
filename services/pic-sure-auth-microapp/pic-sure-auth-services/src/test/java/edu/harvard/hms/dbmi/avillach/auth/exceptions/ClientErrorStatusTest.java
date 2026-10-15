package edu.harvard.hms.dbmi.avillach.auth.exceptions;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
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
 * for an exception no handler expects. Every response carries the {@code {message, content}} body. The controller runs behind a
 * method-security proxy, so a denied {@code @PreAuthorize} reaches the advice the way it does in the service.
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

        @PreAuthorize("hasAnyAuthority('ADMIN', 'SUPER_ADMIN')")
        @GetMapping("/admin")
        public String admin() {
            return "admin";
        }

    }

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller()).setControllerAdvice(new GlobalExceptionHandler()).build();

    @Test
    void unreadableJsonBodyIs400() throws Exception {
        mockMvc.perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON).content("{\"name\":"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Malformed request body"))
            .andExpect(jsonPath("$.content").value("The request body could not be parsed."));
    }

    @Test
    void missingBodyIs400() throws Exception {
        mockMvc.perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Malformed request body"))
            .andExpect(jsonPath("$.content").value("The request body could not be parsed."));
    }

    @Test
    void unsupportedMediaTypeIs415WithAccept() throws Exception {
        mockMvc.perform(post("/probe/body").contentType(MediaType.TEXT_PLAIN).content("name")).andExpect(status().isUnsupportedMediaType())
            .andExpect(header().string(HttpHeaders.ACCEPT, containsString("application/json")))
            .andExpect(jsonPath("$.message").value("Unsupported Media Type"))
            .andExpect(jsonPath("$.content").value("This endpoint does not accept the request's content type."));
    }

    @Test
    void pathTypeMismatchIs400WithoutEchoingTheValue() throws Exception {
        mockMvc.perform(get("/probe/items/abc")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Invalid value for parameter 'id'"))
            .andExpect(jsonPath("$.content").value("Expected a int.")).andExpect(content().string(not(containsString("abc"))));
    }

    @Test
    void wrongMethodIs405WithAllow() throws Exception {
        mockMvc.perform(get("/probe/body")).andExpect(status().isMethodNotAllowed()).andExpect(header().string(HttpHeaders.ALLOW, "POST"))
            .andExpect(jsonPath("$.message").value("Method Not Allowed"))
            .andExpect(jsonPath("$.content").value("This endpoint does not support the request method."));
    }

    @Test
    void missingRequiredParameterIs400() throws Exception {
        mockMvc.perform(get("/probe/search")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Bad Request"))
            .andExpect(jsonPath("$.content").value("The request is missing a required value or contains an invalid one."));
    }

    @Test
    void missingResourceIs404() throws Exception {
        mockMvc.perform(get("/probe/missing")).andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Not Found"))
            .andExpect(jsonPath("$.content").value("The requested resource does not exist."));
    }

    @Test
    void unmappedPathIs404() throws Exception {
        mockMvc.perform(get("/nope")).andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Not Found"))
            .andExpect(jsonPath("$.content").value("The requested resource does not exist."));
    }

    @Test
    void unacceptableMediaTypeIs406() throws Exception {
        mockMvc.perform(get("/probe/page").accept(MediaType.APPLICATION_JSON)).andExpect(status().isNotAcceptable())
            .andExpect(jsonPath("$.message").value("Not Acceptable"))
            .andExpect(jsonPath("$.content").value("This endpoint cannot produce any of the accepted media types."));
    }

    @Test
    void unexpectedRuntimeExceptionIs500() throws Exception {
        mockMvc.perform(get("/probe/boom")).andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.message").value("An error occurred while processing your request"))
            .andExpect(jsonPath("$.content").value("internal detail"));
    }

    @Test
    void unexpectedCheckedExceptionIs500() throws Exception {
        mockMvc.perform(get("/probe/checked")).andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
            .andExpect(jsonPath("$.content").value("Please contact the system administrator with the time this error occurred."));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void deniedAdminCallIs403WithTheExistingBody() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("caller", null, List.of(new SimpleGrantedAuthority("PRIV_NOT_AN_ADMIN")))
        );

        mockMvc.perform(get("/probe/admin")).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.message").value("You do not have permission to perform this operation"))
            .andExpect(jsonPath("$.content").value("Access Denied"));
    }

    private static Object controller() {
        ProxyFactory factory = new ProxyFactory(new ProbeController());
        factory.setProxyTargetClass(true);
        factory.addAdvisor(AuthorizationManagerBeforeMethodInterceptor.preAuthorize());
        return factory.getProxy();
    }
}
