package edu.harvard.hms.dbmi.avillach.openapi;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** An OpenAPI 3.1 document lists a nullable property's type as an array; the example requirement still applies to it. */
class SchemaDocumentedNullableTest {

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
}
