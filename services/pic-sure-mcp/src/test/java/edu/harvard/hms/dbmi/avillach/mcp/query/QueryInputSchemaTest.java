package edu.harvard.hms.dbmi.avillach.mcp.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.mcp.tool.CountResult;
import edu.harvard.hms.dbmi.avillach.mcp.tool.CountTool;
import edu.harvard.hms.dbmi.avillach.mcp.tool.CrossCountResult;
import edu.harvard.hms.dbmi.avillach.mcp.tool.CrossCountTool;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson2.DefaultJsonSchemaValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springaicommunity.mcp.method.tool.utils.JsonSchemaGenerator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Checks the input schemas generated from the query tools' root input records: {@code $defs} sit at the root, every reference resolves, no
 * removed field appears, and the MCP SDK's own validator accepts a nested sample and rejects an incomplete one.
 */
class QueryInputSchemaTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final JsonSchemaValidator VALIDATOR = new DefaultJsonSchemaValidator();

    @ParameterizedTest
    @ValueSource(classes = {CountTool.Input.class, CrossCountTool.Input.class, CountResult.class, CrossCountResult.class})
    void defsSitAtTheRootAndEveryReferenceResolves(Class<?> type) throws Exception {
        JsonNode schema = JSON.readTree(JsonSchemaGenerator.generateFromClass(type));

        List<String> refs = new ArrayList<>();
        collectRefs(schema, refs, true);
        for (String ref : refs) {
            assertThat(ref).startsWith("#/$defs/");
            assertThat(schema.at(ref.substring(1)).isObject()).as(ref).isTrue();
        }
    }

    @Test
    void inputSchemasHaveTheirClauseDefsAtTheRoot() throws Exception {
        JsonNode schema = JSON.readTree(JsonSchemaGenerator.generateFromClass(CountTool.Input.class));

        assertThat(schema.path("$defs").has("Filter")).isTrue();
        assertThat(schema.path("$defs").has("Subquery")).isTrue();
        assertThat(schema.path("required")).extracting(JsonNode::asText).containsExactly("query");
        JsonNode query = schema.path("properties").path("query");
        assertThat(query.has("$defs")).isFalse();
        assertThat(query.path("properties").fieldNames()).toIterable()
            .containsExactlyInAnyOrder("select", "phenotypicClause", "genomicFilters");
        assertThat(query.path("required").isMissingNode() || query.path("required").isEmpty()).isTrue();
        assertThat(schema.path("$defs").path("Filter").path("required")).extracting(JsonNode::asText)
            .containsExactlyInAnyOrder("phenotypicFilterType", "conceptPath");
        assertThat(schema.path("$defs").path("Subquery").path("required")).extracting(JsonNode::asText)
            .containsExactlyInAnyOrder("operator", "phenotypicClauses");
    }

    @ParameterizedTest
    @ValueSource(classes = {CountTool.Input.class, CrossCountTool.Input.class})
    void noRemovedFieldAppearsAnywhereInTheInputSchema(Class<?> type) throws Exception {
        JsonNode schema = JSON.readTree(JsonSchemaGenerator.generateFromClass(type));

        List<String> names = new ArrayList<>();
        collectPropertyNames(schema, names);
        assertThat(names).doesNotContain("not", "expectedResultType", "authorizationFilters", "picsureId", "id");
        assertThat(schema.toString()).doesNotContain("\"not\"");
    }

    @Test
    void crossCountResultTypeIsLimitedToTheThreeCrossCounts() throws Exception {
        JsonNode schema = JSON.readTree(JsonSchemaGenerator.generateFromClass(CrossCountTool.Input.class));

        assertThat(schema.path("properties").path("resultType").path("enum")).extracting(JsonNode::asText)
            .containsExactly("CROSS_COUNT", "CATEGORICAL_CROSS_COUNT", "CONTINUOUS_CROSS_COUNT");
        assertThat(schema.path("required")).extracting(JsonNode::asText).containsExactlyInAnyOrder("query", "resultType");
    }

    @Test
    void theSdkValidatorAcceptsTheNestedSample() throws Exception {
        Map<String, Object> schema = JSON.readValue(JsonSchemaGenerator.generateFromClass(CountTool.Input.class), new TypeReference<>() {});

        JsonSchemaValidator.ValidationResponse response = VALIDATOR.validate(schema, QueryBinderTest.args(QueryBinderTest.NESTED_SAMPLE));

        assertThat(response.valid()).as(response.errorMessage()).isTrue();
    }

    @Test
    void theSdkValidatorRejectsANestedFilterWithoutAConceptPath() throws Exception {
        Map<String, Object> schema = JSON.readValue(JsonSchemaGenerator.generateFromClass(CountTool.Input.class), new TypeReference<>() {});
        String broken = """
            {"query":{"phenotypicClause":{"operator":"AND","phenotypicClauses":[
              {"operator":"OR","phenotypicClauses":[{"phenotypicFilterType":"REQUIRED"}]}]}}}""";

        assertThat(VALIDATOR.validate(schema, QueryBinderTest.args(broken)).valid()).isFalse();
    }

    private static void collectRefs(JsonNode node, List<String> refs, boolean root) {
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> {
                if ("$ref".equals(e.getKey())) {
                    refs.add(e.getValue().asText());
                } else {
                    if (!root) {
                        assertThat(e.getKey()).as("nested $defs").isNotEqualTo("$defs");
                    }
                    collectRefs(e.getValue(), refs, false);
                }
            });
        } else if (node.isArray()) {
            node.forEach(child -> collectRefs(child, refs, false));
        }
    }

    private static void collectPropertyNames(JsonNode node, List<String> names) {
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> {
                if ("properties".equals(e.getKey())) {
                    e.getValue().fieldNames().forEachRemaining(names::add);
                }
                collectPropertyNames(e.getValue(), names);
            });
        } else if (node.isArray()) {
            node.forEach(child -> collectPropertyNames(child, names));
        }
    }
}
