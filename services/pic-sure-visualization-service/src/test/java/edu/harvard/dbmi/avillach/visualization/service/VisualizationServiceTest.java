package edu.harvard.dbmi.avillach.visualization.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import edu.harvard.dbmi.avillach.visualization.model.AccessType;
import edu.harvard.dbmi.avillach.visualization.model.ObfuscatedCount;
import edu.harvard.dbmi.avillach.visualization.model.VisualizationResponse;
import edu.harvard.dbmi.avillach.visualization.processing.BinningService;
import edu.harvard.dbmi.avillach.visualization.processing.CategoricalAggregationService;
import edu.harvard.dbmi.avillach.visualization.processing.CategoricalDistributionProcessor;
import edu.harvard.dbmi.avillach.visualization.processing.ContinuousDistributionProcessor;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class VisualizationServiceTest {

    private static final QueryServiceClient.GatewayIdentity IDENTITY =
        new QueryServiceClient.GatewayIdentity("u-1", "sub-1", "a@b", "ROLE_X", "PRIV_A");

    @Mock
    private QueryServiceClient queryServiceClient;

    private VisualizationService service;

    @BeforeEach
    void setUp() {
        QueryDecomposer decomposer = new QueryDecomposer();
        BinningService binningService = new BinningService();
        CategoricalAggregationService aggregationService = new CategoricalAggregationService(7);
        CategoricalDistributionProcessor categoricalProcessor = new CategoricalDistributionProcessor();
        ContinuousDistributionProcessor continuousProcessor = new ContinuousDistributionProcessor();
        service = new VisualizationService(
            decomposer, queryServiceClient, categoricalProcessor, continuousProcessor, binningService, aggregationService
        );
    }

    @Test
    void generateDistributions_authorized_categoricalFilter() {
        PhenotypicFilter catFilter =
            new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\demographics\\race\\", Set.of("White", "Black"), null, null, null);
        Query query = new Query(List.of(), List.of(), catFilter, List.of(), null, null, null);

        Map<String, Map<String, Integer>> crossCounts = new LinkedHashMap<>();
        crossCounts.put("\\demographics\\race\\", new LinkedHashMap<>(Map.of("White", 45000, "Black", 12000)));
        when(queryServiceClient.getAuthCrossCounts(any(), eq(ResultType.CATEGORICAL_CROSS_COUNT), any(), any(), any()))
            .thenReturn(crossCounts);

        VisualizationResponse response = service.generateDistributions(query, AccessType.AUTHORIZED, IDENTITY, null);

        assertFalse(response.categoricalData().isEmpty());
        assertEquals("demographics: race", response.categoricalData().get(0).title());
        assertFalse(response.categoricalData().get(0).obfuscated());
    }

    @Test
    void generateDistributions_open_withObfuscation() {
        PhenotypicFilter catFilter =
            new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\demographics\\race\\", Set.of("White"), null, null, null);
        Query query = new Query(List.of(), List.of(), catFilter, List.of(), null, null, null);

        Map<String, Map<String, ObfuscatedCount>> openCrossCounts = new LinkedHashMap<>();
        openCrossCounts.put(
            "\\demographics\\race\\",
            new LinkedHashMap<>(Map.of("White", new ObfuscatedCount(45000, "45000±3", 3), "Other", new ObfuscatedCount(0, "< 10", 9)))
        );
        when(queryServiceClient.getOpenCrossCounts(any(), eq(ResultType.CATEGORICAL_CROSS_COUNT), any(), any(), any()))
            .thenReturn(openCrossCounts);

        VisualizationResponse response = service.generateDistributions(query, AccessType.OPEN, IDENTITY, null);

        assertFalse(response.categoricalData().isEmpty());
        assertTrue(response.categoricalData().get(0).obfuscated());
    }

    @Test
    void generateDistributions_open_setsObfuscatedFromAccessTypeRegardlessOfValues() {
        PhenotypicFilter catFilter =
            new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\demographics\\race\\", Set.of("White"), null, null, null);
        Query query = new Query(List.of(), List.of(), catFilter, List.of(), null, null, null);

        // All values look like plain integers — no markers at all.
        Map<String, Map<String, ObfuscatedCount>> openCrossCounts = new LinkedHashMap<>();
        openCrossCounts.put(
            "\\demographics\\race\\",
            new LinkedHashMap<>(Map.of("White", new ObfuscatedCount(45000, "45000"), "Black", new ObfuscatedCount(12000, "12000")))
        );
        when(queryServiceClient.getOpenCrossCounts(any(), eq(ResultType.CATEGORICAL_CROSS_COUNT), any(), any(), any()))
            .thenReturn(openCrossCounts);

        VisualizationResponse response = service.generateDistributions(query, AccessType.OPEN, IDENTITY, null);

        assertTrue(
            response.categoricalData().get(0).obfuscated(), "OPEN access must set obfuscated=true even if values contain no markers"
        );
    }

    @Test
    void generateDistributions_noFilters_returnsEmptyCharts() {
        Query query = new Query(List.of(), List.of(), null, List.of(), null, null, null);

        VisualizationResponse response = service.generateDistributions(query, AccessType.AUTHORIZED, IDENTITY, null);

        assertTrue(response.categoricalData().isEmpty());
        assertTrue(response.continuousData().isEmpty());
    }

    @Test
    void generateDistributions_authorized_continuousFilter_binsData() {
        PhenotypicFilter numFilter = new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\measurements\\bmi\\", null, 18.0, 40.0, null);
        Query query = new Query(List.of(), List.of(), numFilter, List.of(), null, null, null);

        Map<String, Integer> rawValues = new LinkedHashMap<>();
        rawValues.put("18.0", 100);
        rawValues.put("22.0", 200);
        rawValues.put("26.0", 150);
        rawValues.put("30.0", 100);
        rawValues.put("35.0", 50);
        Map<String, Map<String, Integer>> crossCounts = new LinkedHashMap<>();
        crossCounts.put("\\measurements\\bmi\\", rawValues);
        when(queryServiceClient.getAuthCrossCounts(any(), eq(ResultType.CONTINUOUS_CROSS_COUNT), any(), any(), any()))
            .thenReturn(crossCounts);

        VisualizationResponse response = service.generateDistributions(query, AccessType.AUTHORIZED, IDENTITY, null);

        assertFalse(response.continuousData().isEmpty());
        int totalOutput = response.continuousData().get(0).continuousMap().values().stream().mapToInt(ObfuscatedCount::count).sum();
        assertEquals(600, totalOutput);
    }

    @Test
    void generateDistributions_selectFallback_whenNoFilters() {
        Query query = new Query(List.of("\\demographics\\race\\"), List.of(), null, List.of(), null, null, null);

        Map<String, Map<String, Integer>> crossCounts = new LinkedHashMap<>();
        crossCounts.put("\\demographics\\race\\", new LinkedHashMap<>(Map.of("White", 100)));
        when(queryServiceClient.getAuthCrossCounts(any(), eq(ResultType.CATEGORICAL_CROSS_COUNT), any(), any(), any()))
            .thenReturn(crossCounts);

        VisualizationResponse response = service.generateDistributions(query, AccessType.AUTHORIZED, IDENTITY, null);

        assertFalse(response.categoricalData().isEmpty());
        assertEquals("demographics: race", response.categoricalData().get(0).title());
    }

    @Test
    void generateDistributions_requiredNumericFilter_skipsEmptyCategoricalAndReturnsHistogram() {
        PhenotypicFilter required = new PhenotypicFilter(PhenotypicFilterType.REQUIRED, "\\demographics\\AGE\\", null, null, null, null);
        Query query = new Query(List.of(), List.of(), required, List.of(), null, null, null);

        Map<String, Map<String, Integer>> emptyCategoricalCounts = new LinkedHashMap<>();
        emptyCategoricalCounts.put("\\demographics\\AGE\\", new LinkedHashMap<>());
        when(queryServiceClient.getAuthCrossCounts(any(), eq(ResultType.CATEGORICAL_CROSS_COUNT), any(), any(), any()))
            .thenReturn(emptyCategoricalCounts);

        Map<String, Map<String, Integer>> continuousCounts = new LinkedHashMap<>();
        continuousCounts.put("\\demographics\\AGE\\", new LinkedHashMap<>(Map.of("18.0", 2, "19.0", 3)));
        when(queryServiceClient.getAuthCrossCounts(any(), eq(ResultType.CONTINUOUS_CROSS_COUNT), any(), any(), any()))
            .thenReturn(continuousCounts);

        VisualizationResponse response = service.generateDistributions(query, AccessType.AUTHORIZED, IDENTITY, null);

        assertEquals(1, response.continuousData().size());
        assertTrue(response.categoricalData().isEmpty());
    }

    @Test
    void generateDistributions_authorized_categoricalWithManyCategories_aggregatesToOther() {
        PhenotypicFilter catFilter =
            new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\demographics\\race\\", Set.of("A", "B"), null, null, null);
        Query query = new Query(List.of(), List.of(), catFilter, List.of(), null, null, null);

        Map<String, Integer> manyCategories = new LinkedHashMap<>();
        for (int i = 1; i <= 9; i++) {
            manyCategories.put("Cat" + i, 100 - i * 5);
        }
        Map<String, Map<String, Integer>> crossCounts = new LinkedHashMap<>();
        crossCounts.put("\\demographics\\race\\", manyCategories);
        when(queryServiceClient.getAuthCrossCounts(any(), eq(ResultType.CATEGORICAL_CROSS_COUNT), any(), any(), any()))
            .thenReturn(crossCounts);

        VisualizationResponse response = service.generateDistributions(query, AccessType.AUTHORIZED, IDENTITY, null);

        assertFalse(response.categoricalData().isEmpty());
        assertTrue(
            response.categoricalData().get(0).categoricalMap().containsKey("Other"),
            "VisualizationService should run CategoricalAggregationService on AUTH categorical data"
        );
    }

    @Test
    void generateDistributions_authorized_nullCountInResponse_skipsNullEntries() {
        PhenotypicFilter catFilter =
            new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\demographics\\race\\", Set.of("White"), null, null, null);
        Query query = new Query(List.of(), List.of(), catFilter, List.of(), null, null, null);

        Map<String, Integer> values = new LinkedHashMap<>();
        values.put("White", 45000);
        values.put("Black", null);
        Map<String, Map<String, Integer>> crossCounts = new LinkedHashMap<>();
        crossCounts.put("\\demographics\\race\\", values);
        when(queryServiceClient.getAuthCrossCounts(any(), eq(ResultType.CATEGORICAL_CROSS_COUNT), any(), any(), any()))
            .thenReturn(crossCounts);

        VisualizationResponse response = service.generateDistributions(query, AccessType.AUTHORIZED, IDENTITY, null);

        assertFalse(response.categoricalData().isEmpty());
        Map<String, ObfuscatedCount> race = response.categoricalData().get(0).categoricalMap();
        assertEquals(new ObfuscatedCount(45000, "45000"), race.get("White"));
        assertFalse(race.containsKey("Black"), "Null counts must be skipped, not crash");
    }
}
