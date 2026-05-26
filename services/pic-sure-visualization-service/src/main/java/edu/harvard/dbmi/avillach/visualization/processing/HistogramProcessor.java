package edu.harvard.dbmi.avillach.visualization.processing;

import edu.harvard.dbmi.avillach.visualization.model.ChartData;
import edu.harvard.dbmi.avillach.visualization.model.ChartType;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class HistogramProcessor implements ChartProcessor {

    private static final Set<String> SKIP_KEYS =
        Set.of("\\_consents\\", "\\_harmonized_consent\\", "\\_topmed_consents\\", "\\_parent_consents\\");

    private final BinningService binningService;

    public HistogramProcessor(BinningService binningService) {
        this.binningService = binningService;
    }

    @Override
    public ChartType chartType() {
        return ChartType.HISTOGRAM;
    }

    @Override
    public Map<String, Map<String, Integer>> preProcess(Map<String, Map<String, Integer>> crossCounts) {
        return binningService.binContinuousData(crossCounts);
    }

    @Override
    public List<ChartData> process(Map<String, Map<String, Integer>> crossCounts, boolean isObfuscated) {
        List<ChartData> charts = new ArrayList<>();

        for (Map.Entry<String, Map<String, Integer>> entry : crossCounts.entrySet()) {
            if (SKIP_KEYS.contains(entry.getKey())) continue;

            String title = getChartTitle(entry.getKey());
            String xAxisLabel = createXAxisLabel(title);

            Map<String, Integer> binnedData = new LinkedHashMap<>(entry.getValue());

            List<String> xValues = new ArrayList<>(binnedData.keySet());
            List<Integer> yValues = new ArrayList<>(binnedData.values());

            Map<String, Object> trace = new LinkedHashMap<>();
            trace.put("x", xValues);
            trace.put("y", yValues);
            trace.put("type", "bar");
            trace.put("name", xAxisLabel);

            Map<String, Object> layout = new LinkedHashMap<>();
            layout.put("xaxis", Map.of("title", xAxisLabel));
            layout.put("yaxis", Map.of("title", "Number of Participants"));
            layout.put("bargap", 0.05);

            charts.add(new ChartData("histogram", title, isObfuscated, List.of(trace), layout));
        }

        return charts;
    }
}
