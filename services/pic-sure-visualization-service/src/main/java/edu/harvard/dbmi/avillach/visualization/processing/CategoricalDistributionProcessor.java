package edu.harvard.dbmi.avillach.visualization.processing;

import edu.harvard.dbmi.avillach.visualization.model.CategoricalDistributionData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class CategoricalDistributionProcessor {

    private static final int MAX_LABEL_LENGTH = 45;

    private final int maxCategories;

    public CategoricalDistributionProcessor(
        @Value("${distribution.categorical.max-categories:${chart.categorical.max-categories:7}}") int maxCategories
    ) {
        this.maxCategories = maxCategories;
    }

    public List<CategoricalDistributionData> process(
        Map<String, Map<String, Integer>> crossCounts, boolean obfuscated, boolean aggregateCategories
    ) {
        List<CategoricalDistributionData> distributions = new ArrayList<>();

        for (Map.Entry<String, Map<String, Integer>> entry : crossCounts.entrySet()) {
            if (DistributionMetadata.SKIP_KEYS.contains(entry.getKey()) || entry.getValue().isEmpty()) {
                continue;
            }

            Map<String, Integer> categoricalMap =
                aggregateCategories ? aggregateTopN(entry.getValue()) : new LinkedHashMap<>(entry.getValue());
            String title = DistributionMetadata.titleFor(entry.getKey());
            distributions.add(
                new CategoricalDistributionData(
                    entry.getKey(), title, false, categoricalMap, obfuscated, DistributionMetadata.xAxisLabelFor(title),
                    "Number of Participants", null, null
                )
            );
        }

        return distributions;
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
