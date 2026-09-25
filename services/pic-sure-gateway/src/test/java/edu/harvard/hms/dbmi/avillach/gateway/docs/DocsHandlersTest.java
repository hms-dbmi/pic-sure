package edu.harvard.hms.dbmi.avillach.gateway.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

/** The index with nothing registered is an empty JSON array, not an error and not null. */
class DocsHandlersTest {

    @Test
    void emptyRegistryIndexIsAnEmptyArray() throws Exception {
        ObjectMapper json = new ObjectMapper();
        DocsHandlers handlers = new DocsHandlers(
            new DocsProperties(true, true, "/picsure", List.of()), OpenApiDocumentFetcher.withTimeouts(100, 100, json), json
        );
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/openapi");
        MockHttpServletResponse response = new MockHttpServletResponse();

        ServerResponse served = handlers.index(ServerRequest.create(request, List.of()));
        served.writeTo(request, response, () -> List.of(new StringHttpMessageConverter()));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-cache");
        assertThat(response.getContentAsString()).isEqualTo("[]");
    }
}
