package edu.harvard.hms.dbmi.avillach.openapi;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

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

    @Test
    void refPropertyNeedsADescriptionUnderOpenApi31() throws Exception {
        JsonNode document = new ObjectMapper().readTree(REF_DOCUMENT.formatted("3.1.0"));
        assertThatThrownBy(() -> OpenApiDocumentAssertions.assertSchemaDocumented(document, "Thing")).isInstanceOf(AssertionError.class)
            .hasMessageContaining("Thing.owner has no description").satisfies(error -> {
                if (error.getMessage().contains("Thing.home")) {
                    throw new AssertionError("home was flagged: " + error.getMessage());
                }
            });
    }

    @Test
    void refPropertyIsExemptUnderOpenApi30() throws Exception {
        JsonNode document = new ObjectMapper().readTree(REF_DOCUMENT.formatted("3.0.1"));
        OpenApiDocumentAssertions.assertSchemaDocumented(document, "Thing");
    }

    @Test
    void exemptScalarMustHaveNoExample() throws Exception {
        JsonNode document = new ObjectMapper().readTree(EXEMPT_DOCUMENT.formatted(""));
        OpenApiDocumentAssertions.assertSchemaDocumented(document, Set.of("Thing.spread"), "Thing");
    }

    @Test
    void exemptScalarWithAnExampleFails() throws Exception {
        JsonNode document = new ObjectMapper().readTree(EXEMPT_DOCUMENT.formatted(",\"example\":3"));
        assertThatThrownBy(() -> OpenApiDocumentAssertions.assertSchemaDocumented(document, Set.of("Thing.spread"), "Thing"))
            .isInstanceOf(AssertionError.class).hasMessageContaining("Thing.spread is exempt from the example rule but has an example");
    }

    @Test
    void exemptionThatMatchesNoScalarFails() throws Exception {
        JsonNode document = new ObjectMapper().readTree(EXEMPT_DOCUMENT.formatted(""));
        assertThatThrownBy(() -> OpenApiDocumentAssertions.assertSchemaDocumented(document, Set.of("Thing.gone"), "Thing"))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("Thing.gone is exempt from the example rule but is not a scalar property of a checked schema")
            .hasMessageContaining("Thing.spread has no example");
    }

    private static final String EXEMPT_DOCUMENT = """
        {"openapi":"3.1.0","components":{"schemas":{"Thing":{"type":"object","description":"A thing","properties":{
          "spread":{"type":["integer","null"],"description":"The spread"%s},
          "name":{"type":"string","description":"The name","example":"x"}}}}}}
        """;

    private static final String REF_DOCUMENT = """
        {"openapi":"%s","components":{"schemas":{
          "Owner":{"type":"object","description":"An owner"},
          "Thing":{"type":"object","description":"A thing","properties":{
            "owner":{"$ref":"#/components/schemas/Owner"},
            "home":{"$ref":"#/components/schemas/Owner","description":"Where it lives"}}}}}}
        """;
}
