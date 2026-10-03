package edu.harvard.hms.dbmi.avillach.mcp.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.GenomicFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Operator;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilterType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicSubquery;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.mcp.tool.CountTool;
import edu.harvard.hms.dbmi.avillach.mcp.tool.CrossCountTool;
import edu.harvard.hms.dbmi.avillach.mcp.tool.ToolFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Covers the strict binder and the mapping onto the real v3 {@link Query}: every field the model must not set is rejected by name with a
 * short message, nested subqueries and genomic filters survive the round trip, and the tool's result type always wins.
 */
class QueryBinderTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    static final String NESTED_SAMPLE = """
        {"query":{"select":["\\\\phs1\\\\sex\\\\"],
         "phenotypicClause":{"operator":"AND","phenotypicClauses":[
           {"phenotypicFilterType":"FILTER","conceptPath":"\\\\phs1\\\\sex\\\\","values":["Female"]},
           {"operator":"OR","phenotypicClauses":[
             {"phenotypicFilterType":"FILTER","conceptPath":"\\\\phs1\\\\age\\\\","min":40,"max":65.5},
             {"phenotypicFilterType":"REQUIRED","conceptPath":"\\\\phs1\\\\bmi\\\\"}]}]},
         "genomicFilters":[{"key":"Gene_with_variant","values":["APOE"]},{"key":"Variant_frequency_as_text","min":0.1,"max":0.5}]}}""";

    static Map<String, Object> args(String json) throws Exception {
        return JSON.readValue(json, new TypeReference<>() {});
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        delimiter = '|',
        value = {
            "not on a filter|{\"query\":{\"phenotypicClause\":{\"phenotypicFilterType\":\"REQUIRED\",\"conceptPath\":\"\\\\a\\\\\",\"not\":true}}}|not",
            "not on a subquery|{\"query\":{\"phenotypicClause\":{\"operator\":\"AND\",\"not\":true,\"phenotypicClauses\":[{\"phenotypicFilterType\":\"REQUIRED\",\"conceptPath\":\"\\\\a\\\\\"}]}}}|not",
            "not on a nested filter|{\"query\":{\"phenotypicClause\":{\"operator\":\"AND\",\"phenotypicClauses\":[{\"phenotypicFilterType\":\"REQUIRED\",\"conceptPath\":\"\\\\a\\\\\",\"not\":false}]}}}|not",
            "expectedResultType|{\"query\":{\"expectedResultType\":\"DATAFRAME\"}}|expectedResultType",
            "authorizationFilters|{\"query\":{\"authorizationFilters\":[{\"conceptPath\":\"\\\\_consents\\\\\",\"values\":[\"phs1.c1\"]}]}}|authorizationFilters",
            "empty authorizationFilters|{\"query\":{\"authorizationFilters\":[]}}|authorizationFilters",
            "picsureId|{\"query\":{\"picsureId\":\"3f0e4a4e-7b8f-4a57-9c55-5c1a0d6f2b11\"}}|picsureId",
            "id|{\"query\":{\"id\":\"3f0e4a4e-7b8f-4a57-9c55-5c1a0d6f2b11\"}}|id",
            "top-level expectedResultType|{\"query\":{},\"expectedResultType\":\"DATAFRAME\"}|expectedResultType"}
    )
    void rejectsEachRemovedFieldByName(String label, String json, String field) throws Exception {
        assertThatThrownBy(() -> QueryBinder.bind(args(json), CountTool.Input.class)).isInstanceOf(ToolFailure.class)
            .hasMessage("Field '" + field + "' is not part of this tool's input.").hasNoCause();
    }

    @Test
    void crossCountInputRejectsNotToo() throws Exception {
        String json =
            """
                {"resultType":"CROSS_COUNT","query":{"phenotypicClause":{"phenotypicFilterType":"REQUIRED","conceptPath":"\\\\a\\\\","not":true}}}""";
        assertThatThrownBy(() -> QueryBinder.bind(args(json), CrossCountTool.Input.class))
            .hasMessage("Field 'not' is not part of this tool's input.");
    }

    @Test
    void nestedSubqueriesAndGenomicFiltersRoundTripIntoQuery() throws Exception {
        CountTool.Input input = QueryBinder.bind(args(NESTED_SAMPLE), CountTool.Input.class);

        Query query = input.query().toQuery(ResultType.CROSS_COUNT);

        assertThat(query.expectedResultType()).isEqualTo(ResultType.CROSS_COUNT);
        assertThat(query.select()).containsExactly("\\phs1\\sex\\");
        assertThat(query.authorizationFilters()).isEmpty();
        assertThat(query.picsureId()).isNull();
        assertThat(query.id()).isNull();
        PhenotypicSubquery root = (PhenotypicSubquery) query.phenotypicClause();
        assertThat(root.operator()).isEqualTo(Operator.AND);
        assertThat(root.not()).isNull();
        assertThat(root.phenotypicClauses().get(0))
            .isEqualTo(new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\phs1\\sex\\", Set.of("Female"), null, null, null));
        PhenotypicSubquery nested = (PhenotypicSubquery) root.phenotypicClauses().get(1);
        assertThat(nested.operator()).isEqualTo(Operator.OR);
        assertThat(nested.not()).isNull();
        assertThat(nested.phenotypicClauses()).containsExactly(
            new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\phs1\\age\\", null, 40.0, 65.5, null),
            new PhenotypicFilter(PhenotypicFilterType.REQUIRED, "\\phs1\\bmi\\", null, null, null, null)
        );
        assertThat(query.genomicFilters()).containsExactly(
            new GenomicFilter("Gene_with_variant", List.of("APOE"), null, null),
            new GenomicFilter("Variant_frequency_as_text", null, 0.1f, 0.5f)
        );
        assertThat(query.allFilters()).hasSize(3).allSatisfy(f -> assertThat(f.not()).isNull());
    }

    @Test
    void countDropsSelectAndAlwaysSendsCount() throws Exception {
        Query query = QueryBinder.bind(args(NESTED_SAMPLE), CountTool.Input.class).query().toQuery(ResultType.COUNT);

        assertThat(query.expectedResultType()).isEqualTo(ResultType.COUNT);
        assertThat(query.select()).isEmpty();
    }

    @Test
    void emptyArgumentsBindToAnInputWithNoQuery() {
        assertThat(QueryBinder.bind(null, CountTool.Input.class).query()).isNull();
        assertThat(QueryBinder.bind(Map.of(), CountTool.Input.class).query()).isNull();
    }

    @Test
    void anUndeducibleClauseIsNamedAsSuch() throws Exception {
        assertThatThrownBy(
            () -> QueryBinder.bind(args("{\"query\":{\"phenotypicClause\":{\"conceptpath\":\"x\"}}}"), CountTool.Input.class)
        ).hasMessageStartingWith("Each phenotypic clause must be a filter");
        assertThatThrownBy(() -> QueryBinder.bind(args("{\"query\":{\"phenotypicClause\":{}}}"), CountTool.Input.class))
            .hasMessageStartingWith("Each phenotypic clause must be a filter");
    }

    @Test
    void aBadEnumValueListsTheAllowedValues() throws Exception {
        assertThatThrownBy(
            () -> QueryBinder.bind(
                args("{\"query\":{\"phenotypicClause\":{\"phenotypicFilterType\":\"NOPE\",\"conceptPath\":\"\\\\a\\\\\"}}}"),
                CountTool.Input.class
            )
        ).hasMessage("Field 'phenotypicFilterType' must be one of REQUIRED, FILTER, ANY_RECORD_OF.");
        assertThatThrownBy(() -> QueryBinder.bind(args("{\"resultType\":\"COUNT\",\"query\":{}}"), CrossCountTool.Input.class))
            .hasMessage("Field 'resultType' must be one of CROSS_COUNT, CATEGORICAL_CROSS_COUNT, CONTINUOUS_CROSS_COUNT.");
        assertThatThrownBy(() -> QueryBinder.bind(args("{\"resultType\":\"DATAFRAME\",\"query\":{}}"), CrossCountTool.Input.class))
            .hasMessageContaining("must be one of CROSS_COUNT, CATEGORICAL_CROSS_COUNT, CONTINUOUS_CROSS_COUNT");
    }

    @Test
    void aWrongTypeNamesTheField() throws Exception {
        assertThatThrownBy(() -> QueryBinder.bind(args("{\"query\":{\"select\":{\"a\":1}}}"), CountTool.Input.class))
            .hasMessage("Field 'select' has the wrong type.");
    }

    @Test
    void anUnknownFieldNameIsStrippedOfControlCharactersAndCapped() {
        String name = "x\n".repeat(100);
        assertThatThrownBy(() -> QueryBinder.bind(Map.of(name, 1), CountTool.Input.class)).satisfies(e -> {
            assertThat(e.getMessage()).doesNotContain("\n");
            assertThat(e.getMessage()).contains("'" + "x".repeat(QueryBinder.MAX_FIELD_NAME_LENGTH) + "'");
        });
    }

    @Test
    void incompleteClausesFailWhenMapped() throws Exception {
        QueryInput missingPath = QueryBinder
            .bind(args("{\"query\":{\"phenotypicClause\":{\"phenotypicFilterType\":\"REQUIRED\"}}}"), CountTool.Input.class).query();
        assertThatThrownBy(() -> missingPath.toQuery(ResultType.COUNT)).isInstanceOf(ToolFailure.class)
            .hasMessage("Argument 'conceptPath' is required.");

        QueryInput emptySubquery = QueryBinder
            .bind(args("{\"query\":{\"phenotypicClause\":{\"operator\":\"OR\",\"phenotypicClauses\":[]}}}"), CountTool.Input.class).query();
        assertThatThrownBy(() -> emptySubquery.toQuery(ResultType.COUNT))
            .hasMessage("Field 'phenotypicClauses' must hold at least one clause.");

        QueryInput noOperator = QueryBinder.bind(
            args(
                "{\"query\":{\"phenotypicClause\":{\"phenotypicClauses\":[{\"phenotypicFilterType\":\"REQUIRED\",\"conceptPath\":\"\\\\a\\\\\"}]}}}"
            ), CountTool.Input.class
        ).query();
        assertThatThrownBy(() -> noOperator.toQuery(ResultType.COUNT)).hasMessage("Field 'operator' is required on every subquery.");

        QueryInput noKey =
            QueryBinder.bind(args("{\"query\":{\"genomicFilters\":[{\"values\":[\"APOE\"]}]}}"), CountTool.Input.class).query();
        assertThatThrownBy(() -> noKey.toQuery(ResultType.COUNT)).hasMessage("Argument 'key' is required.");
    }
}
