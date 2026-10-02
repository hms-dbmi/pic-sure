package edu.harvard.hms.dbmi.avillach.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.StringSchema;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A served document carries each enum constant's description, as a bullet list in the enum schema's description and as the
 * {@code x-enum-descriptions} extension, wherever the enum appears: a field with its own description, a field without one, a list's items,
 * a bare return, a query parameter, and a component schema shared by reference.
 */
@SpringBootTest(classes = {OpenApiTestApplication.class, EnumDescriptionDocumentTest.ExportController.class})
@AutoConfigureMockMvc
class EnumDescriptionDocumentTest {

    @Schema(description = "The shape of an export")
    enum ExportFormat {
        @Schema(description = "One row per patient, comma separated")
        CSV, @Schema(description = "Avro in the PFB layout")
        PFB, UNDESCRIBED
    }

    enum Compression {
        @JsonProperty("gz") @Schema(description = "Gzip")
        GZIP, @Schema(description = "No compression")
        NONE
    }

    @Schema(description = "Where an export stands", enumAsRef = true)
    enum ExportState {
        @Schema(description = "Still being written")
        RUNNING, @Schema(description = "Ready to download")
        AVAILABLE
    }

    @Schema(description = "An export to start")
    record ExportRequest(
        @Schema(description = "The format wanted") ExportFormat format,
        @Schema(description = "Formats to fall back to") List<ExportFormat> fallbacks, Compression compression
    ) {
    }

    @Schema(description = "An export that was started")
    record ExportStatus(ExportState state) {
    }

    @RestController
    static class ExportController {
        @PostMapping("/exports")
        public ExportStatus start(@RequestBody ExportRequest request) {
            return new ExportStatus(ExportState.RUNNING);
        }

        @GetMapping("/exports/default-format")
        public ExportFormat defaultFormat(@RequestParam ExportFormat preferred) {
            return preferred;
        }
    }

    private static final String FORMAT_BULLETS =
        "- `CSV`: One row per patient, comma separated\n- `PFB`: Avro in the PFB layout\n- `UNDESCRIBED`";

    @Autowired
    private MockMvc mockMvc;

    private JsonNode document() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(body);
    }

    private static List<String> extension(JsonNode schema) {
        return new ObjectMapper()
            .convertValue(schema.path("x-enum-descriptions"), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
    }

    @Test
    void fieldWithItsOwnDescriptionKeepsItAndGainsTheList() throws Exception {
        JsonNode format = document().path("components").path("schemas").path("ExportRequest").path("properties").path("format");

        assertThat(format.path("description").asText()).isEqualTo("The format wanted\n\n" + FORMAT_BULLETS);
        assertThat(extension(format)).containsExactly("One row per patient, comma separated", "Avro in the PFB layout", "");
    }

    @Test
    void listItemsCarryTheEnumTypeDescriptionAndTheList() throws Exception {
        JsonNode items =
            document().path("components").path("schemas").path("ExportRequest").path("properties").path("fallbacks").path("items");

        assertThat(items.path("description").asText()).isEqualTo("The shape of an export\n\n" + FORMAT_BULLETS);
        assertThat(extension(items)).hasSize(3);
    }

    @Test
    void renamedConstantIsMatchedByItsWireValue() throws Exception {
        JsonNode compression = document().path("components").path("schemas").path("ExportRequest").path("properties").path("compression");

        assertThat(compression.path("enum").get(0).asText()).isEqualTo("gz");
        assertThat(compression.path("description").asText()).isEqualTo("- `gz`: Gzip\n- `NONE`: No compression");
        assertThat(extension(compression)).containsExactly("Gzip", "No compression");
    }

    @Test
    void sharedComponentIsDescribedOnce() throws Exception {
        JsonNode document = document();
        JsonNode state = document.path("components").path("schemas").path("ExportState");

        assertThat(document.path("components").path("schemas").path("ExportStatus").path("properties").path("state").path("$ref").asText())
            .isEqualTo("#/components/schemas/ExportState");
        assertThat(state.path("description").asText())
            .isEqualTo("Where an export stands\n\n- `RUNNING`: Still being written\n- `AVAILABLE`: Ready to download");
        assertThat(extension(state)).containsExactly("Still being written", "Ready to download");
    }

    @Test
    void bareReturnAndQueryParameterAreDescribed() throws Exception {
        JsonNode operation = document().path("paths").path("/exports/default-format").path("get");
        JsonNode returned = operation.path("responses").path("200").path("content").elements().next().path("schema");
        JsonNode parameter = operation.path("parameters").get(0).path("schema");

        assertThat(returned.path("description").asText()).isEqualTo("The shape of an export\n\n" + FORMAT_BULLETS);
        assertThat(parameter.path("description").asText()).isEqualTo("The shape of an export\n\n" + FORMAT_BULLETS);
    }

    @Test
    void servingTheDocumentTwiceDoesNotRepeatTheList() throws Exception {
        document();
        JsonNode format = document().path("components").path("schemas").path("ExportRequest").path("properties").path("format");

        assertThat(format.path("description").asText()).isEqualTo("The format wanted\n\n" + FORMAT_BULLETS);
    }

    @Test
    void customiserAppliedTwiceAppendsOnce() {
        StringSchema schema = new StringSchema();
        schema.setEnum(List.of("A", "B"));
        schema.addExtension(EnumConstantDescriptionConverter.EXTENSION, List.of("Alpha", ""));
        OpenAPI openApi = new OpenAPI().components(new Components().addSchemas("Letter", schema));
        EnumDescriptionCustomizer customizer = new EnumDescriptionCustomizer();

        customizer.customise(openApi);
        customizer.customise(openApi);

        assertThat(schema.getDescription()).isEqualTo("- `A`: Alpha\n- `B`");
    }
}
