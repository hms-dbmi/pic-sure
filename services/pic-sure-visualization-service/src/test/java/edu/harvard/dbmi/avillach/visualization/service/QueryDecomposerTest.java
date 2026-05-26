package edu.harvard.dbmi.avillach.visualization.service;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class QueryDecomposerTest {

    private QueryDecomposer decomposer;

    @BeforeEach
    void setUp() {
        decomposer = new QueryDecomposer();
    }

    @Test
    void decompose_withCategoricalFilter_returnsCategoricalSubQuery() {
        PhenotypicFilter catFilter =
            new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\demographics\\race\\", Set.of("White", "Black"), null, null, null);
        Query query = new Query(List.of(), List.of(), catFilter, List.of(), null, null, null);

        List<QueryDecomposer.SubQueryDescriptor> result = decomposer.decompose(query);

        assertEquals(1, result.size());
        assertEquals(ResultType.CATEGORICAL_CROSS_COUNT, result.get(0).resultType());
        assertEquals("bar", result.get(0).chartType());
        assertEquals(List.of("\\demographics\\race\\"), result.get(0).query().select());
    }

    @Test
    void decompose_withNumericFilter_returnsContinuousSubQuery() {
        PhenotypicFilter numFilter = new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\measurements\\bmi\\", null, 18.0, 40.0, null);
        Query query = new Query(List.of(), List.of(), numFilter, List.of(), null, null, null);

        List<QueryDecomposer.SubQueryDescriptor> result = decomposer.decompose(query);

        assertEquals(1, result.size());
        assertEquals(ResultType.CONTINUOUS_CROSS_COUNT, result.get(0).resultType());
        assertEquals("histogram", result.get(0).chartType());
    }

    @Test
    void decompose_withBothFilterTypes_returnsTwoSubQueries() {
        PhenotypicFilter catFilter =
            new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\demographics\\race\\", Set.of("White"), null, null, null);
        PhenotypicFilter numFilter = new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\measurements\\bmi\\", null, 18.0, 40.0, null);
        PhenotypicSubquery subquery = new PhenotypicSubquery(null, List.of(catFilter, numFilter), Operator.AND);
        Query query = new Query(List.of(), List.of(), subquery, List.of(), null, null, null);

        List<QueryDecomposer.SubQueryDescriptor> result = decomposer.decompose(query);

        assertEquals(2, result.size());
    }

    @Test
    void decompose_withNoFilters_returnsEmpty() {
        Query query = new Query(List.of(), List.of(), null, List.of(), null, null, null);

        List<QueryDecomposer.SubQueryDescriptor> result = decomposer.decompose(query);

        assertTrue(result.isEmpty());
    }

    @Test
    void decompose_withRequiredFilter_treatsTypeAsUnknown() {
        PhenotypicFilter required = new PhenotypicFilter(PhenotypicFilterType.REQUIRED, "\\demographics\\AGE\\", null, null, null, null);
        Query query = new Query(List.of(), List.of(), required, List.of(), null, null, null);

        List<QueryDecomposer.SubQueryDescriptor> result = decomposer.decompose(query);

        assertEquals(2, result.size());
        assertEquals(ResultType.CATEGORICAL_CROSS_COUNT, result.get(0).resultType());
        assertEquals(ResultType.CONTINUOUS_CROSS_COUNT, result.get(1).resultType());
    }

    @Test
    void decompose_withSelectButNoFilters_fallsBackToSelectAsCategorical() {
        Query query = new Query(List.of("\\demographics\\race\\", "\\demographics\\sex\\"), List.of(), null, List.of(), null, null, null);

        List<QueryDecomposer.SubQueryDescriptor> result = decomposer.decompose(query);

        assertEquals(1, result.size());
        assertEquals(ResultType.CATEGORICAL_CROSS_COUNT, result.get(0).resultType());
        assertEquals(List.of("\\demographics\\race\\", "\\demographics\\sex\\"), result.get(0).query().select());
    }

    @Test
    void decompose_withFiltersAndSelect_ignoresSelectFallback() {
        PhenotypicFilter catFilter =
            new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\demographics\\race\\", Set.of("White"), null, null, null);
        Query query = new Query(List.of("\\some\\other\\path\\"), List.of(), catFilter, List.of(), null, null, null);

        List<QueryDecomposer.SubQueryDescriptor> result = decomposer.decompose(query);

        assertEquals(1, result.size());
        // Should use the filter path, not the select path
        assertEquals(List.of("\\demographics\\race\\"), result.get(0).query().select());
    }
}
