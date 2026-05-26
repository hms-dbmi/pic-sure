package edu.harvard.dbmi.avillach.visualization.service;

import edu.harvard.dbmi.avillach.visualization.model.AccessType;
import edu.harvard.dbmi.avillach.visualization.model.VisualizationResponse;
import edu.harvard.dbmi.avillach.visualization.processing.BarChartProcessor;
import edu.harvard.dbmi.avillach.visualization.processing.BinningService;
import edu.harvard.dbmi.avillach.visualization.processing.ChartProcessorRegistry;
import edu.harvard.dbmi.avillach.visualization.processing.HistogramProcessor;
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

    @Mock
    private HpdsClient hpdsClient;

    private VisualizationService service;

    @BeforeEach
    void setUp() {
        QueryDecomposer decomposer = new QueryDecomposer();
        ObfuscationParser obfuscationParser = new ObfuscationParser(10, 3);
        BinningService binningService = new BinningService();
        BarChartProcessor barProcessor = new BarChartProcessor(7);
        HistogramProcessor histogramProcessor = new HistogramProcessor(binningService);
        ChartProcessorRegistry registry = new ChartProcessorRegistry(List.of(barProcessor, histogramProcessor));
        service = new VisualizationService(decomposer, hpdsClient, obfuscationParser, registry, binningService);
    }

    @Test
    void handleQuerySync_authorized_categoricalFilter() {
        PhenotypicFilter catFilter =
            new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\demographics\\race\\", List.of("White", "Black"), null, null, null);
        Query query = new Query(List.of(), List.of(), catFilter, List.of(), null, null, null);

        Map<String, Map<String, Integer>> crossCounts = new LinkedHashMap<>();
        crossCounts.put("\\demographics\\race\\", new LinkedHashMap<>(Map.of("White", 45000, "Black", 12000)));
        when(hpdsClient.getAuthCrossCounts(any(), eq(ResultType.CATEGORICAL_CROSS_COUNT), any())).thenReturn(crossCounts);

        VisualizationResponse response = service.handleQuerySync(query, AccessType.AUTHORIZED, "Bearer token");

        assertFalse(response.charts().isEmpty());
        assertEquals("bar", response.charts().get(0).chartType());
        assertFalse(response.charts().get(0).isObfuscated());
    }

    @Test
    void handleQuerySync_open_withObfuscation() {
        PhenotypicFilter catFilter =
            new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\demographics\\race\\", List.of("White"), null, null, null);
        Query query = new Query(List.of(), List.of(), catFilter, List.of(), null, null, null);

        Map<String, Map<String, String>> openCrossCounts = new LinkedHashMap<>();
        openCrossCounts.put("\\demographics\\race\\", new LinkedHashMap<>(Map.of("White", "45000±3", "Other", "< 10")));
        when(hpdsClient.getOpenCrossCounts(any(), eq(ResultType.CATEGORICAL_CROSS_COUNT), any())).thenReturn(openCrossCounts);

        VisualizationResponse response = service.handleQuerySync(query, AccessType.OPEN, null);

        assertFalse(response.charts().isEmpty());
        assertTrue(response.charts().get(0).isObfuscated());
    }

    @Test
    void handleQuerySync_noFilters_returnsEmptyCharts() {
        Query query = new Query(List.of(), List.of(), null, List.of(), null, null, null);

        VisualizationResponse response = service.handleQuerySync(query, AccessType.AUTHORIZED, "Bearer token");

        assertTrue(response.charts().isEmpty());
    }

    @Test
    void handleQuerySync_authorized_continuousFilter_binsData() {
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
        when(hpdsClient.getAuthCrossCounts(any(), eq(ResultType.CONTINUOUS_CROSS_COUNT), any())).thenReturn(crossCounts);

        VisualizationResponse response = service.handleQuerySync(query, AccessType.AUTHORIZED, "Bearer token");

        assertFalse(response.charts().isEmpty());
        assertEquals("histogram", response.charts().get(0).chartType());
        @SuppressWarnings("unchecked")
        List<Integer> yValues = (List<Integer>) response.charts().get(0).traces().get(0).get("y");
        int totalOutput = yValues.stream().mapToInt(Integer::intValue).sum();
        assertEquals(600, totalOutput);
    }

    @Test
    void handleQuerySync_selectFallback_whenNoFilters() {
        Query query = new Query(List.of("\\demographics\\race\\"), List.of(), null, List.of(), null, null, null);

        Map<String, Map<String, Integer>> crossCounts = new LinkedHashMap<>();
        crossCounts.put("\\demographics\\race\\", new LinkedHashMap<>(Map.of("White", 100)));
        when(hpdsClient.getAuthCrossCounts(any(), eq(ResultType.CATEGORICAL_CROSS_COUNT), any())).thenReturn(crossCounts);

        VisualizationResponse response = service.handleQuerySync(query, AccessType.AUTHORIZED, "Bearer token");

        assertFalse(response.charts().isEmpty());
        assertEquals("bar", response.charts().get(0).chartType());
    }
}
