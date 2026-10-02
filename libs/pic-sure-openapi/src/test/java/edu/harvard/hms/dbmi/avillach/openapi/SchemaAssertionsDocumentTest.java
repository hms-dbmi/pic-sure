package edu.harvard.hms.dbmi.avillach.openapi;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;

/**
 * The schema assertions against a document springdoc really serves, so the helpers are proven on its actual output: the wildcard media type
 * of a handler with no {@code produces}, the generated name of a generic envelope, a text body, and a 204.
 */
@SpringBootTest(classes = {OpenApiTestApplication.class, SchemaAssertionsDocumentTest.RoleController.class})
@AutoConfigureMockMvc
class SchemaAssertionsDocumentTest {

    @Schema(description = "How far a role reaches")
    enum Reach {
        @Schema(description = "One study")
        STUDY, @Schema(description = "Every study")
        GLOBAL
    }

    @Schema(description = "A role to create or change")
    record RoleRequest(
        @Schema(description = "The role's name", example = "PIC-SURE Top Admin") String name,
        @Schema(description = "How far the role reaches") Reach reach
    ) {
    }

    @Schema(description = "A role as the API returns it")
    record RoleResponse(
        @Schema(description = "The role's id", example = "8694e3d4-5cb4-410f-8431-993445e6d3f6") UUID uuid,
        @Schema(description = "The role's name", example = "PIC-SURE Top Admin") String name,
        @Schema(description = "Names of the privileges the role grants", example = "[\"SUPER_ADMIN\", \"ADMIN\"]") List<String> privileges,
        @Schema(description = "How far the role reaches") Reach reach,
        @Schema(description = "Whether the role can be deleted") boolean removable,
        @Schema(description = "Free-form settings keyed by setting name") Map<String, String> settings,
        @Schema(description = "The role this one was copied from") RoleOrigin origin
    ) {
    }

    @Schema(description = "Where a role came from")
    record RoleOrigin(@Schema(description = "The source role's id", example = "8694e3d4-5cb4-410f-8431-993445e6d3f6") UUID uuid) {
    }

    record Undocumented(String name, @Schema(description = "A count") int count) {
    }

    @Schema(description = "A message with its payload")
    record Envelope<T>(
        @Schema(description = "What happened", example = "Roles saved") String message, @Schema(description = "The payload") T content
    ) {
    }

    @RestController
    static class RoleController {
        @GetMapping("/role")
        public List<RoleResponse> list() {
            return List.of();
        }

        @GetMapping("/role/names")
        public List<String> names() {
            return List.of();
        }

        @PostMapping("/role")
        public Envelope<List<RoleResponse>> create(@RequestBody List<RoleRequest> roles) {
            return null;
        }

        @PutMapping("/role/one")
        public ResponseEntity<RoleResponse> update(@RequestBody RoleRequest role) {
            return null;
        }

        @GetMapping(value = "/role/help", produces = MediaType.TEXT_HTML_VALUE)
        public String help() {
            return "";
        }

        @DeleteMapping("/role/one")
        @ApiResponse(responseCode = "204", description = "Deleted")
        public ResponseEntity<Void> delete() {
            return ResponseEntity.noContent().build();
        }

        @GetMapping("/role/undocumented")
        public Undocumented undocumented() {
            return null;
        }
    }

    @Autowired
    private MockMvc mockMvc;

    private JsonNode document;

    @BeforeEach
    void loadDocument() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        document = new ObjectMapper().readTree(body);
    }

    @Test
    void everyShapeTheServicesEmitIsRecognised() {
        OpenApiDocumentAssertions.assertBareArrayOf(document, "get", "/role", "200", "RoleResponse");
        OpenApiDocumentAssertions.assertBareArrayOfScalar(document, "get", "/role/names", "200", "string");
        OpenApiDocumentAssertions.assertRequestArrayOf(document, "post", "/role", "RoleRequest");
        OpenApiDocumentAssertions.assertEnvelope(document, "post", "/role", "200", "RoleResponse");
        OpenApiDocumentAssertions.assertRequestSchema(document, "put", "/role/one", "RoleRequest");
        OpenApiDocumentAssertions.assertResponseSchema(document, "put", "/role/one", "200", "RoleResponse");
        OpenApiDocumentAssertions.assertMediaType(document, "get", "/role/help", "200", "text/html", "string");
        OpenApiDocumentAssertions.assertNoResponseBody(document, "delete", "/role/one", "204");
        OpenApiDocumentAssertions.assertSchemaHasFields(document, "RoleResponse", "uuid", "name", "privileges");
        OpenApiDocumentAssertions.assertSchemaDocumented(document, "RoleRequest", "RoleResponse", "RoleOrigin");
    }

    @Test
    void bareArrayIsNotMistakenForAnEnvelopeOrASingleObject() {
        assertThatThrownBy(() -> OpenApiDocumentAssertions.assertEnvelope(document, "get", "/role", "200", "RoleResponse"))
            .isInstanceOf(AssertionError.class).hasMessageContaining("200 response of GET /role should be an object with message");
        assertThatThrownBy(() -> OpenApiDocumentAssertions.assertResponseSchema(document, "get", "/role", "200", "RoleResponse"))
            .isInstanceOf(AssertionError.class).hasMessageContaining("should be a $ref to RoleResponse");
        assertThatThrownBy(() -> OpenApiDocumentAssertions.assertBareArrayOf(document, "post", "/role", "200", "RoleResponse"))
            .isInstanceOf(AssertionError.class).hasMessageContaining("should be an array of RoleResponse");
    }

    @Test
    void undocumentedSchemaIsReportedMemberByMember() {
        assertThatThrownBy(() -> OpenApiDocumentAssertions.assertSchemaDocumented(document, "Undocumented"))
            .isInstanceOf(AssertionError.class).hasMessageContaining("Undocumented has no description")
            .hasMessageContaining("Undocumented.name has no description").hasMessageContaining("Undocumented.name has no example")
            .hasMessageContaining("Undocumented.count has no example");
    }
}
