package edu.harvard.hms.dbmi.avillach.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.hms.dbmi.avillach.openapi.OpenApiConfiguration;
import edu.harvard.hms.dbmi.avillach.openapi.OpenApiDocumentAssertions;

/**
 * The live document is served unauthenticated, names this service, carries the bearer scheme, and covers every visible handler with a
 * summarised operation. An endpoint cannot vanish from the document, and the annotation pass cannot skip one, without failing here. This is
 * also PSAMA's first test to boot the full application context: an in-memory H2 schema stands in for MySQL, and {@code NON_KEYWORDS}
 * excuses the columns Hibernate would otherwise refuse because H2 reserves their names. Required authorities appear in a description only as
 * the sentence the shared customizer writes from {@code @PreAuthorize}, never as hand-written prose.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {"spring.datasource.url=jdbc:h2:mem:psama-openapi;MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER,VALUE,KEY",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect", "spring.jpa.hibernate.ddl-auto=create-drop",
        "APPLICATION_CLIENT_SECRET=openapi-test-placeholder-secret", "management.endpoints.web.exposure.include=none"}
)
@AutoConfigureMockMvc
class OpenApiDocumentTest {

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

        assertThat(document.path("info").path("title").asText()).isEqualTo("pic-sure-auth-services");
        assertThat(document.path("info").path("version").asText()).isNotBlank();
        assertThat(document.path("components").path("securitySchemes").has(OpenApiConfiguration.BEARER_SCHEME)).isTrue();
        assertThat(document.path("security").get(0).has(OpenApiConfiguration.BEARER_SCHEME)).isTrue();
        assertThat(document.path("paths").has("/swagger.json")).isFalse();
        assertThat(document.path("paths").has("/swagger.yaml")).isFalse();
        OpenApiDocumentAssertions.assertCovers(document, handlerMapping);
    }

    @Test
    void requiredAuthoritiesComeFromTheGuards() throws Exception {
        JsonNode paths =
            objectMapper.readTree(mockMvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString()).path("paths");

        assertThat(description(paths, "/user", "get")).isEqualTo("GET a list of existing users\n\nRequired authorities: ADMIN, SUPER_ADMIN.");
        assertThat(description(paths, "/accessRule", "post")).isEqualTo("POST a list of AccessRules\n\nRequired authorities: SUPER_ADMIN.");
        assertThat(description(paths, "/user", "post")).isEqualTo("POST a list of users\n\nRequired authorities: ADMIN.");
        assertThat(description(paths, "/user/me", "get")).isEqualTo("Retrieve information of current user");
        assertThat(description(paths, "/application", "get"))
            .isEqualTo("GET a list of existing Applications\n\nRequired authorities: ADMIN, SUPER_ADMIN.");
        paths.forEach(
            path -> path.forEach(
                operation -> assertThat(operation.path("description").asText()).doesNotContainIgnoringCase("requires")
                    .doesNotContainIgnoringCase("role restrictions")
            )
        );
    }

    @Test
    void applicationReadsDescribeTheTokenFreeShape() throws Exception {
        JsonNode document = objectMapper.readTree(mockMvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString());
        JsonNode paths = document.path("paths");
        String displayRef = "#/components/schemas/ApplicationForDisplay";

        assertThat(successSchemas(paths, "/application/{applicationId}")).isNotEmpty()
            .allSatisfy(schema -> assertThat(schema.path("$ref").asText()).isEqualTo(displayRef));
        assertThat(successSchemas(paths, "/application")).isNotEmpty()
            .allSatisfy(schema -> assertThat(schema.path("items").path("$ref").asText()).isEqualTo(displayRef));
        assertThat(document.path("components").path("schemas").path("ApplicationForDisplay").path("properties").has("token")).isFalse();
    }

    private static List<JsonNode> successSchemas(JsonNode paths, String path) {
        List<JsonNode> schemas = new ArrayList<>();
        paths.path(path).path("get").path("responses").path("200").path("content").forEach(media -> schemas.add(media.path("schema")));
        return schemas;
    }

    private static String description(JsonNode paths, String path, String method) {
        return paths.path(path).path(method).path("description").asText();
    }
}
