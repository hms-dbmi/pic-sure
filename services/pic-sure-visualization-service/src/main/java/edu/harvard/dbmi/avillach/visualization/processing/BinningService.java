package edu.harvard.dbmi.avillach.visualization.processing;

import edu.harvard.dbmi.avillach.visualization.model.BinRange;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class BinningService {

    private static final double THIRD = 1.0 / 3.0;

    public Map<String, Map<String, Integer>> binContinuousData(Map<String, Map<String, Integer>> continuousDataMap) {
        return binContinuousData(continuousDataMap, Map.of(), null);
    }

    public Map<String, Map<String, Integer>> binContinuousData(
        Map<String, Map<String, Integer>> continuousDataMap, Map<String, BinRange> ranges, Integer maxBins
    ) {
        Map<String, Map<String, Integer>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Integer>> entry : continuousDataMap.entrySet()) {
            BinRange range = ranges == null ? null : ranges.get(entry.getKey());
            result.put(entry.getKey(), bucketData(entry.getValue(), range, maxBins));
        }
        return result;
    }

    public Map<String, Integer> bucketData(Map<String, Integer> originalMap) {
        return bucketData(originalMap, null, null);
    }

    /** Bins across {@code range} when given, otherwise across the observed values, using at most {@code maxBins} bins when given. */
    public Map<String, Integer> bucketData(Map<String, Integer> originalMap, BinRange range, Integer maxBins) {
        if (originalMap == null || originalMap.isEmpty()) {
            return new LinkedHashMap<>();
        }

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

        double min = range != null ? range.min() : data.keySet().stream().min(Double::compareTo).orElse(0.0);
        double max = range != null ? range.max() : data.keySet().stream().max(Double::compareTo).orElse(0.0);
        boolean isSameMinMax = min == max;

        int numBins = calcNumBins(data, min, max);
        if (maxBins != null && maxBins > 0) {
            numBins = Math.min(numBins, maxBins);
        }
        if (numBins <= 0) {
            numBins = 1;
        }

        double binSize = (max - min) / numBins;
        if (binSize <= 0.0) {
            binSize = 1.0;
        }

        Map<Integer, Integer> counts = createBinsAndMergeCounts(data, numBins, min, binSize);

        // Every bin in the range is emitted, empty or not, so the chart does not reveal where the data stops.
        Map<Integer, Integer> results = new LinkedHashMap<>();
        Map<Integer, List<Double>> ranges = new HashMap<>();
        for (int key = 0; key < numBins; key++) {
            double rangeStart = min + (key * binSize);
            double rangeEnd = min + ((key + 1) * binSize);
            ranges.put(key, new ArrayList<>(List.of(rangeStart, rangeEnd)));
            results.put(key, counts.getOrDefault(key, 0));
        }

        return createLabelsForBins(results, ranges, isSameMinMax, range == null);
    }

    /** Bin width comes from the spread of the observed values; the number of bins is how many of those fit in {@code min..max}. */
    private static int calcNumBins(Map<Double, Integer> countMap, double min, double max) {
        if (min == max || countMap.size() < 2) return 1;
        double[] keys = countMap.keySet().stream().mapToDouble(Double::doubleValue).toArray();
        DescriptiveStatistics da = new DescriptiveStatistics(keys);
        double binWidth = (3.5 * da.getStandardDeviation()) / Math.pow(countMap.size(), THIRD);
        if (binWidth <= 0.0) return 1;
        return (int) Math.round((max - min) / binWidth);
    }

    private static Map<Integer, Integer> createBinsAndMergeCounts(Map<Double, Integer> data, int numBins, double min, double binSize) {
        Map<Integer, Integer> results = new LinkedHashMap<>();
        for (Map.Entry<Double, Integer> entry : data.entrySet()) {
            int bin = (int) Math.floor((entry.getKey() - min) / binSize);
            // Values at the exact max, or outside a supplied range, go into the nearest end bin.
            bin = Math.max(0, Math.min(bin, numBins - 1));
            results.merge(bin, entry.getValue(), Integer::sum);
        }
        return results;
    }

    private static Map<String, Integer> createLabelsForBins(
        Map<Integer, Integer> results, Map<Integer, List<Double>> ranges, boolean isSameMinMax, boolean openEndedLastBin
    ) {
        Map<String, Integer> finalMap = new LinkedHashMap<>();
        String label = "";
        for (Map.Entry<Integer, Integer> bucket : results.entrySet()) {
            double minForLabel = ranges.get(bucket.getKey()).stream().min(Double::compareTo).orElse(0.0);
            double maxForLabel = ranges.get(bucket.getKey()).stream().max(Double::compareTo).orElse(0.0);
            if (minForLabel == maxForLabel || isSameMinMax) {
                label = String.format("%.1f", minForLabel);
            } else {
                label = String.format("%.1f", minForLabel) + " - " + String.format("%.1f", maxForLabel);
            }
            // Adjacent bins can round to the same %.1f label; merge so counts aren't dropped
            finalMap.merge(label, bucket.getValue(), Integer::sum);
        }

        Integer lastCount = finalMap.get(label);
        // Only a chart over its observed values is open-ended; a supplied range has a real upper bound.
        if (openEndedLastBin && lastCount != null && finalMap.size() > 1) {
            String newLabel = label;
            int hasDash = label.indexOf(" -");
            if (hasDash > 0) {
                newLabel = label.substring(0, hasDash);
            }
            finalMap.remove(label);
            finalMap.merge(newLabel + " +", lastCount, Integer::sum);
        }

        return finalMap;
    }
}
