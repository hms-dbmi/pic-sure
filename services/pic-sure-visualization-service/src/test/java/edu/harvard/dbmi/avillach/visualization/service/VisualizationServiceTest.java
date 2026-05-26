package edu.harvard.dbmi.avillach.visualization.service;

import edu.harvard.dbmi.avillach.visualization.model.AccessType;
import edu.harvard.dbmi.avillach.visualization.model.HpdsAccessContext;
import edu.harvard.dbmi.avillach.visualization.model.VisualizationResponse;
import edu.harvard.dbmi.avillach.visualization.processing.BinningService;
import edu.harvard.dbmi.avillach.visualization.processing.CategoricalDistributionProcessor;
import edu.harvard.dbmi.avillach.visualization.processing.ContinuousDistributionProcessor;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VisualizationServiceTest {

    private static final UUID AUTHORIZED_UUID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final UUID OPEN_UUID = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");

    @Mock
    private HpdsClient hpdsClient;

    private VisualizationService service;

    @BeforeEach
    void setUp() {
        QueryDecomposer decomposer = new QueryDecomposer();
        ObfuscationParser obfuscationParser = new ObfuscationParser(10, 3);
        BinningService binningService = new BinningService();
        CategoricalDistributionProcessor categoricalProcessor = new CategoricalDistributionProcessor(7);
        ContinuousDistributionProcessor continuousProcessor = new ContinuousDistributionProcessor(binningService);
        service =
            new VisualizationService(decomposer, hpdsClient, obfuscationParser, categoricalProcessor, continuousProcessor, binningService);
    }

    @Test
    void generateDistributions_authorized_categoricalFilter() {
        PhenotypicFilter catFilter =
            new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\demographics\\race\\", Set.of("White", "Black"), null, null, null);
        Query query = new Query(List.of(), List.of(), catFilter, List.of(), null, null, null);

        Map<String, Map<String, Integer>> crossCounts = new LinkedHashMap<>();
        crossCounts.put("\\demographics\\race\\", new LinkedHashMap<>(Map.of("White", 45000, "Black", 12000)));
        when(hpdsClient.getAuthCrossCounts(any(), eq(ResultType.CATEGORICAL_CROSS_COUNT), eq(AUTHORIZED_UUID), any()))
            .thenReturn(crossCounts);

        VisualizationResponse response =
            service.generateDistributions(query, new HpdsAccessContext(AUTHORIZED_UUID, AccessType.AUTHORIZED), "Bearer token");

        assertFalse(response.categoricalData().isEmpty());
        assertEquals("Variable distribution of demographics: race", response.categoricalData().get(0).title());
        assertFalse(response.categoricalData().get(0).obfuscated());
    }

    @Test
    void generateDistributions_open_withObfuscation() {
        PhenotypicFilter catFilter =
            new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\demographics\\race\\", Set.of("White"), null, null, null);
        Query query = new Query(List.of(), List.of(), catFilter, List.of(), null, null, null);

        Map<String, Map<String, String>> openCrossCounts = new LinkedHashMap<>();
        openCrossCounts.put("\\demographics\\race\\", new LinkedHashMap<>(Map.of("White", "45000±3", "Other", "< 10")));
        when(hpdsClient.getOpenCrossCounts(any(), eq(ResultType.CATEGORICAL_CROSS_COUNT), eq(OPEN_UUID), any()))
            .thenReturn(openCrossCounts);

        VisualizationResponse response = service.generateDistributions(query, new HpdsAccessContext(OPEN_UUID, AccessType.OPEN), null);

        assertFalse(response.categoricalData().isEmpty());
        assertTrue(response.categoricalData().get(0).obfuscated());
    }

    @Test
    void generateDistributions_noFilters_returnsEmptyCharts() {
        Query query = new Query(List.of(), List.of(), null, List.of(), null, null, null);

        VisualizationResponse response =
            service.generateDistributions(query, new HpdsAccessContext(AUTHORIZED_UUID, AccessType.AUTHORIZED), "Bearer token");

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
        when(hpdsClient.getAuthCrossCounts(any(), eq(ResultType.CONTINUOUS_CROSS_COUNT), eq(AUTHORIZED_UUID), any()))
            .thenReturn(crossCounts);

        VisualizationResponse response =
            service.generateDistributions(query, new HpdsAccessContext(AUTHORIZED_UUID, AccessType.AUTHORIZED), "Bearer token");

        assertFalse(response.continuousData().isEmpty());
        int totalOutput = response.continuousData().get(0).continuousMap().values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(600, totalOutput);
    }

    @Test
    void generateDistributions_selectFallback_whenNoFilters() {
        Query query = new Query(List.of("\\demographics\\race\\"), List.of(), null, List.of(), null, null, null);

        Map<String, Map<String, Integer>> crossCounts = new LinkedHashMap<>();
        crossCounts.put("\\demographics\\race\\", new LinkedHashMap<>(Map.of("White", 100)));
        when(hpdsClient.getAuthCrossCounts(any(), eq(ResultType.CATEGORICAL_CROSS_COUNT), eq(AUTHORIZED_UUID), any()))
            .thenReturn(crossCounts);

        VisualizationResponse response =
            service.generateDistributions(query, new HpdsAccessContext(AUTHORIZED_UUID, AccessType.AUTHORIZED), "Bearer token");

        assertFalse(response.categoricalData().isEmpty());
        assertEquals("Variable distribution of demographics: race", response.categoricalData().get(0).title());
    }

    @Test
    void generateDistributions_requiredNumericFilter_skipsEmptyCategoricalAndReturnsHistogram() {
        PhenotypicFilter required = new PhenotypicFilter(PhenotypicFilterType.REQUIRED, "\\demographics\\AGE\\", null, null, null, null);
        Query query = new Query(List.of(), List.of(), required, List.of(), null, null, null);

        Map<String, Map<String, Integer>> emptyCategoricalCounts = new LinkedHashMap<>();
        emptyCategoricalCounts.put("\\demographics\\AGE\\", new LinkedHashMap<>());
        when(hpdsClient.getAuthCrossCounts(any(), eq(ResultType.CATEGORICAL_CROSS_COUNT), eq(AUTHORIZED_UUID), any()))
            .thenReturn(emptyCategoricalCounts);

        Map<String, Map<String, Integer>> continuousCounts = new LinkedHashMap<>();
        continuousCounts.put("\\demographics\\AGE\\", new LinkedHashMap<>(Map.of("18.0", 2, "19.0", 3)));
        when(hpdsClient.getAuthCrossCounts(any(), eq(ResultType.CONTINUOUS_CROSS_COUNT), eq(AUTHORIZED_UUID), any()))
            .thenReturn(continuousCounts);

        VisualizationResponse response =
            service.generateDistributions(query, new HpdsAccessContext(AUTHORIZED_UUID, AccessType.AUTHORIZED), "Bearer token");

        assertEquals(1, response.continuousData().size());
        assertTrue(response.categoricalData().isEmpty());
    }
}
