package edu.harvard.dbmi.avillach.visualization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.hms.dbmi.avillach.openapi.OpenApiConfiguration;
import edu.harvard.hms.dbmi.avillach.openapi.OpenApiDocumentAssertions;

/**
 * The live document is served unauthenticated, names this service, carries the bearer scheme, and covers every visible handler with a
 * summarised operation. An endpoint cannot vanish from the document, and the annotation pass cannot skip one, without failing here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiDocumentTest {

    private static final String SCHEMA_REF_PREFIX = "#/components/schemas/";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void documentCoversEveryVisibleHandler() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)).andReturn().getResponse().getContentAsString();
        JsonNode document = objectMapper.readTree(body);

        assertThat(document.path("info").path("title").asText()).isEqualTo("pic-sure-visualization-service");
        assertThat(document.path("info").path("version").asText()).isNotBlank();
        assertThat(document.path("components").path("securitySchemes").has(OpenApiConfiguration.BEARER_SCHEME)).isTrue();
        assertThat(document.path("security").get(0).has(OpenApiConfiguration.BEARER_SCHEME)).isTrue();
        OpenApiDocumentAssertions.assertCovers(document, handlerMapping);
    }

    @Test
    void binningHandlerExchangesTypedRecords() throws Exception {
        JsonNode document = document();

        OpenApiDocumentAssertions.assertRequestSchema(document, "post", "/bin/continuous", "ContinuousBinningRequest");
        OpenApiDocumentAssertions.assertResponseSchema(document, "post", "/bin/continuous", "200", "ContinuousBinningResponse");
        OpenApiDocumentAssertions.assertSchemaHasFields(document, "ContinuousBinningResponse", "bins");
        OpenApiDocumentAssertions.assertSchemaDocumented(document, "ContinuousBinningResponse");
        assertThat(
            schema(document, "ContinuousBinningResponse").path("properties").path("bins").path("additionalProperties")
                .path("additionalProperties").path("type").asText()
        ).isEqualTo("integer");
    }

    @Test
    void binningV3RouteExchangesTheSameRecords() throws Exception {
        JsonNode document = document();

        OpenApiDocumentAssertions.assertRequestSchema(document, "post", "/v3/bin/continuous", "ContinuousBinningRequest");
        OpenApiDocumentAssertions.assertResponseSchema(document, "post", "/v3/bin/continuous", "200", "ContinuousBinningResponse");
    }

    private JsonNode document() throws Exception {
        return objectMapper
            .readTree(mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static JsonNode schema(JsonNode document, String name) {
        return document.path("components").path("schemas").path(name);
    }
}
