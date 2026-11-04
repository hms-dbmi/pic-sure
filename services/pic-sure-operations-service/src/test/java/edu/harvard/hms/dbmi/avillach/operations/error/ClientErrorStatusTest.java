package edu.harvard.hms.dbmi.avillach.operations.error;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import edu.harvard.hms.dbmi.avillach.commons.error.PicsureErrorBodyAdvice;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureExceptionAdvice;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUserResolver;
import edu.harvard.hms.dbmi.avillach.commons.request.RequestIdFilter;
import edu.harvard.hms.dbmi.avillach.operations.configuration.ConfigurationService;

/**
 * Pins the status, headers and body the {@link PicsureErrorBodyAdvice} imported by
 * {@link edu.harvard.hms.dbmi.avillach.operations.OperationsApplication} gives real operations endpoints for each request error Spring MVC
 * raises before a handler runs, for an exception no handler expects, and for a
 * {@link edu.harvard.hms.dbmi.avillach.commons.error.PicsureException}. Every response carries the {@code {errorType, message, requestId}}
 * body, never Spring Boot's {@code {timestamp, status, error, path}}.
 *
 * <p>Runs the full context, so the request id filter and the gateway privileges filter run as deployed. {@link ConfigurationService} is
 * mocked so a handler can be made to fail with an exception the advice does not expect.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ClientErrorStatusTest {

    private static final String REQUEST_ID = "probe-request";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ConfigurationService configurationService;

    @Test
    void wrongMethodIs405WithAllow() throws Exception {
        expectCommonsBody(perform(post("/configuration")), "method_not_allowed", PicsureExceptionAdvice.METHOD_NOT_ALLOWED)
            .andExpect(status().isMethodNotAllowed()).andExpect(header().string(HttpHeaders.ALLOW, containsString("GET")));
    }

    @Test
    void unsupportedMediaTypeIs415WithAccept() throws Exception {
        expectCommonsBody(
            perform(asSuperAdmin(post("/configuration/admin")).contentType(MediaType.TEXT_PLAIN).content("name")), "unsupported_media_type",
            PicsureExceptionAdvice.UNSUPPORTED_MEDIA_TYPE
        ).andExpect(status().isUnsupportedMediaType()).andExpect(header().string(HttpHeaders.ACCEPT, containsString("application/json")));
    }

    @Test
    void unreadableJsonBodyIs400() throws Exception {
        expectCommonsBody(
            perform(asSuperAdmin(post("/configuration/admin")).contentType(MediaType.APPLICATION_JSON).content("{\"name\":")),
            "bad_request", PicsureExceptionAdvice.BODY_UNREADABLE
        ).andExpect(status().isBadRequest());
    }

    @Test
    void missingBodyIs400() throws Exception {
        expectCommonsBody(
            perform(asSuperAdmin(post("/configuration/admin")).contentType(MediaType.APPLICATION_JSON)), "bad_request",
            PicsureExceptionAdvice.BODY_UNREADABLE
        ).andExpect(status().isBadRequest());
    }

    @Test
    void pathTypeMismatchIs400WithoutEchoingTheValue() throws Exception {
        expectCommonsBody(
            perform(asSuperAdmin(delete("/configuration/admin/{id}", "bogus-identifier"))), "bad_request",
            PicsureExceptionAdvice.invalidParameter("id")
        ).andExpect(status().isBadRequest()).andExpect(content().string(not(containsString("bogus-identifier"))));
    }

    @Test
    void unexpectedExceptionIs500WithoutItsMessage() throws Exception {
        when(configurationService.getConfigurations(any())).thenThrow(new IllegalStateException("internal detail"));

        expectCommonsBody(perform(get("/configuration")), "internal_error", PicsureExceptionAdvice.SERVER_ERROR)
            .andExpect(status().isInternalServerError()).andExpect(content().string(not(containsString("internal detail"))));
    }

    @Test
    void picsureExceptionKeepsItsStatusAndErrorType() throws Exception {
        UUID pathId = UUID.randomUUID();

        expectCommonsBody(
            perform(
                asSuperAdmin(patch("/configuration/admin/{id}", pathId)).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"uuid\":\"" + UUID.randomUUID() + "\",\"value\":\"false\"}")
            ), "bad_request", "UUID cannot be changed"
        ).andExpect(status().isBadRequest());
    }

    private ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header(RequestIdFilter.HEADER, REQUEST_ID));
    }

    private static MockHttpServletRequestBuilder asSuperAdmin(MockHttpServletRequestBuilder request) {
        return request.header(GatewayUserResolver.HEADER_USER_ID, "auth0|abc")
            .header(GatewayUserResolver.HEADER_USER_PRIVILEGES, "SUPER_ADMIN");
    }

    private static ResultActions expectCommonsBody(ResultActions result, String errorType, String message) throws Exception {
        return result.andExpect(jsonPath("$.errorType").value(errorType)).andExpect(jsonPath("$.message").value(message))
            .andExpect(jsonPath("$.requestId").value(REQUEST_ID)).andExpect(jsonPath("$.timestamp").doesNotExist())
            .andExpect(jsonPath("$.path").doesNotExist()).andExpect(jsonPath("$.status").doesNotExist());
    }
}
