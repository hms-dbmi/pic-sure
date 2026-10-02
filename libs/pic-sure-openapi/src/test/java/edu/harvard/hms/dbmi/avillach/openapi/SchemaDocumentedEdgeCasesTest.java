package edu.harvard.hms.dbmi.avillach.openapi;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Edge cases of the schema convention check: an OpenAPI 3.1 document lists a nullable property's type as an array and the example
 * requirement still applies to it, and every enum must describe each of its values.
 */
class SchemaDocumentedEdgeCasesTest {

    @Test
    void nullableScalarStillNeedsAnExample() throws Exception {
        JsonNode document = new ObjectMapper().readTree("""
            {"openapi":"3.1.0","components":{"schemas":{"Thing":{"type":"object","description":"A thing","properties":{
              "name":{"type":["string","null"],"description":"The name"},
              "tags":{"type":["array","null"],"description":"The tags","items":{"type":"string"}},
              "ok":{"type":["string","null"],"description":"Fine","example":"x"}}}}}}
            """);
        assertThatThrownBy(() -> OpenApiDocumentAssertions.assertSchemaDocumented(document, "Thing")).isInstanceOf(AssertionError.class)
            .hasMessageContaining("Thing.name has no example").hasMessageContaining("Thing.tags has no example").satisfies(error -> {
                if (error.getMessage().contains("Thing.ok")) {
                    throw new AssertionError("ok was flagged: " + error.getMessage());
                }
            });
    }

    @Test
    void enumNeedsADescriptionForEveryValue() throws Exception {
        JsonNode document = new ObjectMapper().readTree("""
            {"openapi":"3.0.1","components":{"schemas":{"Kind":{"type":"object","description":"A kind","properties":{
              "mode":{"type":"string","description":"The mode","enum":["A","B"],"x-enum-descriptions":["Alpha",""]},
              "modes":{"type":"array","description":"The modes","items":{"type":"string","enum":["A"]}},
              "ok":{"type":"string","description":"Fine","enum":["A"],"x-enum-descriptions":["Alpha"]}}}}}}
            """);
        assertThatThrownBy(() -> OpenApiDocumentAssertions.assertSchemaDocumented(document, "Kind")).isInstanceOf(AssertionError.class)
            .hasMessageContaining("Kind.mode has no description for enum value B")
            .hasMessageContaining("Kind.modes items lists enum values without x-enum-descriptions").satisfies(error -> {
                if (error.getMessage().contains("Kind.ok")) {
                    throw new AssertionError("ok was flagged: " + error.getMessage());
                }
            });
    }
}
