package edu.harvard.dbmi.avillach.visualization.processing;

import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class BinningService {

    private static final double THIRD = 1.0 / 3.0;

    public Map<String, Map<String, Integer>> binContinuousData(Map<String, Map<String, Integer>> continuousDataMap) {
        Map<String, Map<String, Integer>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Integer>> entry : continuousDataMap.entrySet()) {
            result.put(entry.getKey(), bucketData(entry.getValue()));
        }
        return result;
    }

    public Map<String, Integer> bucketData(Map<String, Integer> originalMap) {
        if (originalMap == null || originalMap.isEmpty()) {
            return new LinkedHashMap<>();
        }

        boolean isSameMinMax = originalMap.size() == 1;
        Map<Double, Integer> data = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : originalMap.entrySet()) {
            try {
                data.put(Double.parseDouble(entry.getKey()), entry.getValue());
            } catch (NumberFormatException e) {
                // skip non-numeric keys
            }
        }

        if (data.isEmpty()) {
            return new LinkedHashMap<>();
        }

        int numBins = calcNumBins(data);
        double min = data.keySet().stream().min(Double::compareTo).orElse(0.0);
        double max = data.keySet().stream().max(Double::compareTo).orElse(0.0);

        if (numBins <= 0) {
            numBins = 1;
        }

        double binSize = (max - min) / numBins;
        if (binSize <= 0.0) {
            binSize = 1.0;
        }

        Map<Integer, Integer> results = createBinsAndMergeCounts(data, numBins, min, binSize);

        Map<Integer, List<Double>> ranges = new HashMap<>();
        List<Integer> keysToAdd = new ArrayList<>();
        int bucketMax = results.keySet().stream().max(Integer::compareTo).orElse(0);

        for (Map.Entry<Integer, Integer> bucket : results.entrySet()) {
            int key = bucket.getKey();
            double rangeStart = min + (key * binSize);
            double rangeEnd = min + ((key + 1) * binSize);
            ranges.put(key, new ArrayList<>(List.of(rangeStart, rangeEnd)));

            if (key != bucketMax && !results.containsKey(key + 1)) {
                keysToAdd.add(key + 1);
                double gapStart = min + ((key + 1) * binSize);
                double gapEnd = min + ((key + 2) * binSize);
                ranges.put(key + 1, new ArrayList<>(List.of(gapStart, gapEnd)));
            }
        }

        Map<Integer, Integer> finalResults = results;
        keysToAdd.forEach(key -> finalResults.put(key, 0));

        if (!keysToAdd.isEmpty()) {
            results = finalResults.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e2, LinkedHashMap::new));
        } else {
            results = finalResults;
        }

        return createLabelsForBins(results, ranges, isSameMinMax);
    }

    private static int calcNumBins(Map<Double, Integer> countMap) {
        double[] keys = countMap.keySet().stream().mapToDouble(Double::doubleValue).toArray();
        DescriptiveStatistics da = new DescriptiveStatistics(keys);
        double smallestKey = da.getMin();
        double largestKey = da.getMax();
        if (smallestKey == largestKey) return 1;
        double binWidth = (3.5 * da.getStandardDeviation()) / Math.pow(countMap.size(), THIRD);
        return (int) Math.round((largestKey - smallestKey) / binWidth);
    }

    private static Map<Integer, Integer> createBinsAndMergeCounts(Map<Double, Integer> data, int numBins, double min, double binSize) {
        Map<Integer, Integer> results = new LinkedHashMap<>();
        for (Map.Entry<Double, Integer> entry : data.entrySet()) {
            int bin = (int) Math.floor((entry.getKey() - min) / binSize);
            if (bin < numBins) {
                results.merge(bin, entry.getValue(), Integer::sum);
            } else {
                // Value at the exact max — merge into last bin
                results.merge(numBins - 1, entry.getValue(), Integer::sum);
            }
        }
        return results;
    }

    private static Map<String, Integer> createLabelsForBins(
        Map<Integer, Integer> results, Map<Integer, List<Double>> ranges, boolean isSameMinMax
    ) {
        Map<String, Integer> finalMap = new LinkedHashMap<>();
        String label = "";
        for (Map.Entry<Integer, Integer> bucket : results.entrySet()) {
            double minForLabel = ranges.get(bucket.getKey()).stream().min(Double::compareTo).orElse(0.0);
            double maxForLabel = ranges.get(bucket.getKey()).stream().max(Double::compareTo).orElse(0.0);
            if (minForLabel == maxForLabel || isSameMinMax) {
                label = String.format("%.1f", maxForLabel);
            } else {
                label = String.format("%.1f", minForLabel) + " - " + String.format("%.1f", maxForLabel);
            }
            finalMap.put(label, bucket.getValue());
        }

        Integer lastCount = finalMap.get(label);
        if (lastCount != null && finalMap.size() > 1) {
            String newLabel = label;
            int hasDash = label.indexOf(" -");
            if (hasDash > 0) {
                newLabel = label.substring(0, hasDash);
            }
            finalMap.remove(label);
            finalMap.put(newLabel + " +", lastCount);
        }

        return finalMap;
    }
}
