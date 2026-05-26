package edu.harvard.dbmi.avillach.visualization.processing;

import edu.harvard.dbmi.avillach.visualization.model.ChartData;
import edu.harvard.dbmi.avillach.visualization.model.ChartType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class BarChartProcessor implements ChartProcessor {

    private static final Set<String> SKIP_KEYS =
        Set.of("\\_consents\\", "\\_harmonized_consent\\", "\\_topmed_consents\\", "\\_parent_consents\\");
    private static final int MAX_LABEL_LENGTH = 45;

    private final int maxCategories;

    public BarChartProcessor(@Value("${chart.categorical.max-categories}") int maxCategories) {
        this.maxCategories = maxCategories;
    }

    @Override
    public ChartType chartType() {
        return ChartType.BAR;
    }

    @Override
    public Map<String, Map<String, Integer>> preProcess(Map<String, Map<String, Integer>> crossCounts) {
        Map<String, Map<String, Integer>> aggregated = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Integer>> entry : crossCounts.entrySet()) {
            aggregated.put(entry.getKey(), aggregateTopN(entry.getValue()));
        }
        return aggregated;
    }

    @Override
    public List<ChartData> process(Map<String, Map<String, Integer>> crossCounts, boolean isObfuscated) {
        List<ChartData> charts = new ArrayList<>();

        for (Map.Entry<String, Map<String, Integer>> entry : crossCounts.entrySet()) {
            if (SKIP_KEYS.contains(entry.getKey())) continue;

            Map<String, Integer> axisMap = new LinkedHashMap<>(entry.getValue());

            String title = getChartTitle(entry.getKey());
            String xAxisLabel = createXAxisLabel(title);

            List<String> xValues = new ArrayList<>(axisMap.keySet());
            List<Integer> yValues = new ArrayList<>(axisMap.values());

            Map<String, Object> trace = new LinkedHashMap<>();
            trace.put("x", xValues);
            trace.put("y", yValues);
            trace.put("type", "bar");
            trace.put("name", xAxisLabel);

            Map<String, Object> layout = new LinkedHashMap<>();
            layout.put("xaxis", Map.of("title", xAxisLabel));
            layout.put("yaxis", Map.of("title", "Number of Participants"));
            layout.put("bargap", 0.15);

            charts.add(new ChartData("bar", title, isObfuscated, List.of(trace), layout));
        }

        return charts;
    }

    Map<String, Integer> aggregateTopN(Map<String, Integer> axisMap) {
        Map<String, Integer> finalAxisMap = axisMap;
        if (axisMap.size() > maxCategories) {
            Supplier<Stream<Map.Entry<String, Integer>>> stream =
                () -> finalAxisMap.entrySet().stream().sorted(Collections.reverseOrder(Map.Entry.comparingByValue()));
            int otherSum = stream.get().skip(maxCategories).mapToInt(Map.Entry::getValue).sum();
            axisMap = stream.get().limit(maxCategories)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e2, LinkedHashMap::new));
            axisMap = limitKeySize(axisMap).entrySet().stream().sorted(Collections.reverseOrder(Map.Entry.comparingByValue()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e2, LinkedHashMap::new));
            axisMap.put("Other", otherSum);
        } else {
            axisMap = limitKeySize(finalAxisMap).entrySet().stream().sorted(Collections.reverseOrder(Map.Entry.comparingByValue()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e2, LinkedHashMap::new));
        }
        return axisMap;
    }

    private static Map<String, Integer> limitKeySize(Map<String, Integer> axisMap) {
        Map<String, Integer> newAxisMap = new LinkedHashMap<>();
        Set<String> keys = new HashSet<>();
        axisMap.forEach((key, value) -> {
            String adjustedKey = key.length() < MAX_LABEL_LENGTH ? key : createAdjustedKey(axisMap, keys, key);
            newAxisMap.put(adjustedKey, value);
            keys.add(adjustedKey);
        });
        return newAxisMap;
    }

    private static String createAdjustedKey(Map<String, Integer> axisMap, Set<String> keys, String key) {
        String keyPrefix = key.substring(0, MAX_LABEL_LENGTH);
        boolean prefixExists = axisMap.keySet().stream().anyMatch(k -> k.startsWith(keyPrefix));
        if (prefixExists) {
            int countFromEnd = 6;
            String proposedKey;
            do {
                proposedKey = String
                    .format("%s...%s", key.substring(0, MAX_LABEL_LENGTH - 3 - countFromEnd), key.substring(key.length() - countFromEnd));
                countFromEnd++;
            } while (keys.contains(proposedKey));
            return proposedKey;
        }
        return keyPrefix + "...";
    }

}
