package edu.harvard.hms.dbmi.avillach.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;

/** The explicit properties win over every other title and version source, and the served document carries the bearer scheme. */
@SpringBootTest(
    classes = OpenApiTestApplication.class,
    properties = {"picsure.openapi.title=Configured title", "picsure.openapi.version=7.7.7", "spring.application.name=ignored-name"}
)
@AutoConfigureMockMvc
class OpenApiConfigurationPropertiesTest {

    @Autowired
    private OpenAPI openApi;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void propertiesOverrideEveryOtherSource() {
        assertThat(openApi.getInfo().getTitle()).isEqualTo("Configured title");
        assertThat(openApi.getInfo().getVersion()).isEqualTo("7.7.7");
    }

    @Test
    void beanCarriesTheBearerSchemeAndDocumentWideRequirement() {
        SecurityScheme scheme = openApi.getComponents().getSecuritySchemes().get(OpenApiConfiguration.BEARER_SCHEME);
        assertThat(scheme.getType()).isEqualTo(SecurityScheme.Type.HTTP);
        assertThat(scheme.getScheme()).isEqualTo("bearer");
        assertThat(scheme.getBearerFormat()).isEqualTo("JWT");
        assertThat(openApi.getSecurity()).singleElement().satisfies(req -> assertThat(req).containsKey(OpenApiConfiguration.BEARER_SCHEME));
    }

    @Test
    void servedDocumentReflectsTheBean() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode document = objectMapper.readTree(body);
        assertThat(document.path("info").path("title").asText()).isEqualTo("Configured title");
        assertThat(document.path("components").path("securitySchemes").has(OpenApiConfiguration.BEARER_SCHEME)).isTrue();
        assertThat(document.path("security").get(0).has(OpenApiConfiguration.BEARER_SCHEME)).isTrue();
    }
}
