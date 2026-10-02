package edu.harvard.hms.dbmi.avillach.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

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
 * excuses the columns Hibernate would otherwise refuse because H2 reserves their names. Required authorities appear in a description only
 * as the sentence the shared customizer writes from {@code @PreAuthorize}, never as hand-written prose. The cache inspection controller is
 * switched on so its operations are covered too.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {"spring.datasource.url=jdbc:h2:mem:psama-openapi;MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER,VALUE,KEY",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect", "spring.jpa.hibernate.ddl-auto=create-drop",
        "APPLICATION_CLIENT_SECRET=openapi-test-placeholder-secret", "management.endpoints.web.exposure.include=none",
        "app.cache.inspect.enabled=true"}
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

        assertThat(description(paths, "/user", "get"))
            .isEqualTo("GET a list of existing users\n\nRequired authorities: ADMIN, SUPER_ADMIN.");
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
    void adminWritesDocumentTheirRequestRecords() throws Exception {
        JsonNode document = objectMapper.readTree(mockMvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString());
        Map<String, String> records = Map.ofEntries(
            Map.entry("/user post", "UserCreateRequest"), Map.entry("/user put", "UserUpdateRequest"),
            Map.entry("/role post", "RoleCreateRequest"), Map.entry("/role put", "RoleUpdateRequest"),
            Map.entry("/privilege post", "PrivilegeCreateRequest"), Map.entry("/privilege put", "PrivilegeUpdateRequest"),
            Map.entry("/connection post", "ConnectionCreateRequest"), Map.entry("/connection put", "ConnectionUpdateRequest"),
            Map.entry("/mapping post", "UserMetadataMappingCreateRequest"), Map.entry("/mapping put", "UserMetadataMappingUpdateRequest"),
            Map.entry("/accessRule post", "AccessRuleCreateRequest"), Map.entry("/accessRule put", "AccessRuleUpdateRequest"),
            Map.entry("/application post", "ApplicationCreateRequest"), Map.entry("/application put", "ApplicationUpdateRequest")
        );

        records.forEach((operation, record) -> {
            String[] pathAndMethod = operation.split(" ");
            JsonNode body = document.path("paths").path(pathAndMethod[0]).path(pathAndMethod[1]).path("requestBody").path("content")
                .path(MediaType.APPLICATION_JSON_VALUE).path("schema");
            assertThat(body.path("items").path("$ref").asText()).as(operation).isEqualTo("#/components/schemas/" + record);
        });
        JsonNode userUpdate = document.path("components").path("schemas").path("UserUpdateRequest").path("properties");
        assertThat(userUpdate.has("email")).isTrue();
        List<String> loginOwned = List.of("subject", "token", "passport", "acceptedTOS", "matched", "auth0metadata");
        loginOwned.forEach(field -> assertThat(userUpdate.has(field)).as("UserUpdateRequest must not document %s", field).isFalse());
    }

    @Test
    void applicationEndpointsDocumentTheirFrozenShapes() throws Exception {
        JsonNode document = document();

        OpenApiDocumentAssertions.assertResponseSchema(document, "get", "/application/{applicationId}", "200", "ApplicationResponse");
        OpenApiDocumentAssertions.assertBareArrayOf(document, "get", "/application", "200", "ApplicationResponse");
        OpenApiDocumentAssertions.assertBareArrayOf(document, "post", "/application", "200", "ApplicationResponse");
        OpenApiDocumentAssertions.assertBareArrayOf(document, "put", "/application", "200", "ApplicationResponse");
        OpenApiDocumentAssertions.assertBareArrayOf(document, "delete", "/application/{applicationId}", "200", "ApplicationResponse");
        OpenApiDocumentAssertions
            .assertResponseSchema(document, "get", "/application/refreshToken/{applicationId}", "200", "ApplicationTokenResponse");
        OpenApiDocumentAssertions.assertSchemaHasFields(document, "ApplicationResponse", "uuid", "name");
        OpenApiDocumentAssertions.assertSchemaHasFields(document, "ApplicationTokenResponse", "token");
        OpenApiDocumentAssertions.assertSchemaDocumented(document, "ApplicationResponse", "ApplicationTokenResponse");
        assertThat(document.path("components").path("schemas").path("ApplicationResponse").path("properties").has("token")).isFalse();
    }

    @Test
    void connectionEndpointsDocumentTheirFrozenShapes() throws Exception {
        JsonNode document = document();

        OpenApiDocumentAssertions.assertResponseSchema(document, "get", "/connection/{connectionId}", "200", "ConnectionResponse");
        OpenApiDocumentAssertions.assertBareArrayOf(document, "get", "/connection", "200", "ConnectionResponse");
        OpenApiDocumentAssertions.assertEnvelope(document, "post", "/connection", "200", "ConnectionResponse");
        OpenApiDocumentAssertions.assertBareArrayOf(document, "put", "/connection", "200", "ConnectionResponse");
        OpenApiDocumentAssertions.assertBareArrayOf(document, "delete", "/connection/{connectionId}", "200", "ConnectionResponse");
        OpenApiDocumentAssertions
            .assertSchemaHasFields(document, "ConnectionResponse", "uuid", "id", "label", "subPrefix", "requiredFields");
        OpenApiDocumentAssertions.assertSchemaDocumented(document, "ConnectionResponse", "PicSureResponseBodyListConnectionResponse");
        JsonNode requiredFields =
            document.path("components").path("schemas").path("ConnectionResponse").path("properties").path("requiredFields");
        assertThat(requiredFields.path("type").asText()).isEqualTo("string");
    }

    @Test
    void mappingEndpointsDocumentTheirFrozenShapes() throws Exception {
        JsonNode document = document();

        OpenApiDocumentAssertions.assertResponseSchema(document, "get", "/mapping/{connectionId}", "200", "ConnectionResponse");
        OpenApiDocumentAssertions.assertBareArrayOf(document, "get", "/mapping", "200", "UserMetadataMappingResponse");
        OpenApiDocumentAssertions.assertBareArrayOf(document, "post", "/mapping", "200", "UserMetadataMappingResponse");
        OpenApiDocumentAssertions.assertBareArrayOf(document, "put", "/mapping", "200", "UserMetadataMappingResponse");
        OpenApiDocumentAssertions.assertBareArrayOf(document, "delete", "/mapping/{mappingId}", "200", "UserMetadataMappingResponse");
        OpenApiDocumentAssertions.assertSchemaHasFields(
            document, "UserMetadataMappingResponse", "uuid", "connection", "generalMetadataJsonPath", "auth0MetadataJsonPath"
        );
        OpenApiDocumentAssertions.assertSchemaDocumented(document, "UserMetadataMappingResponse");
    }

    @Test
    void accessRuleEndpointsDocumentTheirFrozenShapes() throws Exception {
        JsonNode document = document();

        OpenApiDocumentAssertions.assertResponseSchema(document, "get", "/accessRule/{accessRuleId}", "200", "AccessRuleResponse");
        OpenApiDocumentAssertions.assertBareArrayOf(document, "get", "/accessRule", "200", "AccessRuleResponse");
        OpenApiDocumentAssertions.assertBareArrayOf(document, "post", "/accessRule", "200", "AccessRuleResponse");
        OpenApiDocumentAssertions.assertBareArrayOf(document, "put", "/accessRule", "200", "AccessRuleResponse");
        OpenApiDocumentAssertions.assertBareArrayOf(document, "delete", "/accessRule/{accessRuleId}", "200", "AccessRuleResponse");
        OpenApiDocumentAssertions.assertResponseSchema(document, "get", "/accessRule/allTypes", "200", "AccessRuleTypes");
        OpenApiDocumentAssertions.assertSchemaHasFields(
            document, "AccessRuleResponse", "uuid", "name", "description", "type", "rule", "value", "gates", "gateAnyRelation",
            "evaluateOnlyByGates", "subAccessRule", "checkMapNode", "checkMapKeyOnly"
        );
        OpenApiDocumentAssertions.assertSchemaHasFields(document, "AccessRuleTypes", "types");
        OpenApiDocumentAssertions.assertSchemaDocumented(document, "AccessRuleResponse", "AccessRuleTypes");
        JsonNode accessRule = document.path("components").path("schemas").path("AccessRuleResponse").path("properties");
        assertThat(accessRule.has("mergedValues")).isFalse();
        assertThat(accessRule.has("mergedName")).isFalse();
    }

    @Test
    void privilegeEndpointsDocumentTheirFrozenShapes() throws Exception {
        JsonNode document = document();

        OpenApiDocumentAssertions.assertResponseSchema(document, "get", "/privilege/{privilegeId}", "200", "PrivilegeResponse");
        OpenApiDocumentAssertions.assertBareArrayOf(document, "get", "/privilege", "200", "PrivilegeResponse");
        OpenApiDocumentAssertions.assertBareArrayOf(document, "post", "/privilege", "200", "PrivilegeResponse");
        OpenApiDocumentAssertions.assertBareArrayOf(document, "put", "/privilege", "200", "PrivilegeResponse");
        OpenApiDocumentAssertions.assertBareArrayOf(document, "delete", "/privilege/{privilegeId}", "200", "PrivilegeResponse");
        OpenApiDocumentAssertions
            .assertSchemaHasFields(document, "PrivilegeResponse", "uuid", "name", "description", "application", "accessRules");
        OpenApiDocumentAssertions.assertSchemaHasFields(document, "ApplicationResponse", "uuid", "name");
        OpenApiDocumentAssertions.assertSchemaDocumented(document, "PrivilegeResponse", "ApplicationResponse");
        JsonNode privilege = document.path("components").path("schemas").path("PrivilegeResponse").path("properties");
        assertThat(privilege.path("application").path("$ref").asText()).isEqualTo("#/components/schemas/ApplicationResponse");
        assertThat(privilege.path("accessRules").path("items").path("$ref").asText()).isEqualTo("#/components/schemas/AccessRuleResponse");
        assertThat(document.path("components").path("schemas").path("ApplicationResponse").path("properties").has("token")).isFalse();
    }

    private JsonNode document() throws Exception {
        return objectMapper.readTree(mockMvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString());
    }

    private static String description(JsonNode paths, String path, String method) {
        return paths.path(path).path(method).path("description").asText();
    }
}
