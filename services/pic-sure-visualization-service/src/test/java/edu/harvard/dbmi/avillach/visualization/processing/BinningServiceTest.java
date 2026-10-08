package edu.harvard.dbmi.avillach.visualization.processing;

import edu.harvard.dbmi.avillach.visualization.model.BinRange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BinningServiceTest {

    private BinningService binningService;

    @BeforeEach
    void setUp() {
        binningService = new BinningService();
    }

    @Test
    void bucketData_emptyMap_returnsEmpty() {
        Map<String, Integer> result = binningService.bucketData(Map.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void bucketData_singleValue_returnsSingleBin() {
        Map<String, Integer> input = new LinkedHashMap<>();
        input.put("25.0", 100);

        Map<String, Integer> result = binningService.bucketData(input);

        assertEquals(1, result.size());
        assertEquals(100, result.values().iterator().next());
    }

    @Test
    void bucketData_multipleValues_createsBins() {
        Map<String, Integer> input = new LinkedHashMap<>();
        input.put("10.0", 5);
        input.put("20.0", 10);
        input.put("30.0", 15);
        input.put("40.0", 20);
        input.put("50.0", 25);
        input.put("60.0", 30);
        input.put("70.0", 35);
        input.put("80.0", 40);
        input.put("90.0", 45);
        input.put("100.0", 50);

        Map<String, Integer> result = binningService.bucketData(input);

        assertFalse(result.isEmpty());
        // Last bin should end with "+"
        String lastKey = result.keySet().stream().reduce((first, second) -> second).orElse("");
        assertTrue(lastKey.endsWith("+"));
        // Total counts should be preserved
        int totalInput = input.values().stream().mapToInt(Integer::intValue).sum();
        int totalOutput = result.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(totalInput, totalOutput);
    }

    @Test
    void bucketData_nullMap_returnsEmpty() {
        Map<String, Integer> result = binningService.bucketData(null);
        assertTrue(result.isEmpty());
    }

    @Test
    void binContinuousData_processeseMultipleEntries() {
        Map<String, Map<String, Integer>> input = new LinkedHashMap<>();
        Map<String, Integer> bmiData = new LinkedHashMap<>();
        bmiData.put("18.0", 100);
        bmiData.put("25.0", 200);
        bmiData.put("30.0", 150);
        input.put("\\measurements\\bmi\\", bmiData);

        Map<String, Map<String, Integer>> result = binningService.binContinuousData(input);

        assertTrue(result.containsKey("\\measurements\\bmi\\"));
        assertFalse(result.get("\\measurements\\bmi\\").isEmpty());
    }

    @Test
    void bucketData_stringKeysThatParseToSameDouble_mergesIntoSingleEntry() {
        // "5.0" and "5.0000" both parse to Double 5.0, so the LinkedHashMap<Double, Integer>
        // will have only one entry (second overwrites first). This tests that single-value
        // behavior works correctly after dedup.
        Map<String, Integer> input = new LinkedHashMap<>();
        input.put("5.0", 10);
        input.put("5.0000", 20);

        Map<String, Integer> result = binningService.bucketData(input);

        assertNotNull(result);
        assertEquals(1, result.size());
        // Second value (20) overwrites first (10) in the Double-keyed map
        assertEquals(20, result.values().iterator().next());
    }

    @Test
    void bucketData_singleUniqueValue_returnsSingleBin() {
        Map<String, Integer> input = new LinkedHashMap<>();
        input.put("10.0", 50);

        Map<String, Integer> result = binningService.bucketData(input);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(50, result.values().iterator().next());
    }

    @Test
    void bucketData_fractionalRange_producesCorrectBins() {
        // Range 0.0 to 2.0 with many data points at fractional values.
        // With the old integer binSize, ceil(2.0/3) = 1, which would produce
        // bins [0,1), [1,2), [2,3) — oversized and wrong boundaries.
        // With double binSize = 0.667, bins are properly fractional.
        Map<String, Integer> input = new LinkedHashMap<>();
        for (int i = 0; i <= 20; i++) {
            input.put(String.valueOf(i * 0.1), 10);
        }

        Map<String, Integer> result = binningService.bucketData(input);

        assertFalse(result.isEmpty());
        // Total counts preserved
        int totalInput = input.values().stream().mapToInt(Integer::intValue).sum();
        int totalOutput = result.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(totalInput, totalOutput);
        // Bin boundaries should be fractional, not integer-rounded
        for (String label : result.keySet()) {
            if (label.contains(" - ")) {
                String[] parts = label.split(" - ");
                double lower = Double.parseDouble(parts[0]);
                double upper = Double.parseDouble(parts[1]);
                assertTrue(upper > lower, "Bin range should have upper > lower: " + label);
            }
        }
    }

    @Test
    void bucketData_allValuesAtZero_returnsSingleBin() {
        Map<String, Integer> input = new LinkedHashMap<>();
        input.put("0.0", 500);

        Map<String, Integer> result = binningService.bucketData(input);

        assertFalse(result.isEmpty(), "A dataset with values at 0.0 should produce a bin, not empty");
        assertEquals(1, result.size());
        assertEquals(500, result.values().iterator().next());
    }

    @Test
    void bucketData_unsortedInput_returnsBinsInAscendingOrder() {
        // HPDS does not guarantee value order; bins must come back sorted regardless,
        // and the "+" relabel must land on the highest bin, not the last-encountered one.
        Map<String, Integer> input = new LinkedHashMap<>();
        input.put("90.0", 45);
        input.put("10.0", 5);
        input.put("50.0", 25);
        input.put("100.0", 50);
        input.put("30.0", 15);
        input.put("70.0", 35);
        input.put("20.0", 10);
        input.put("80.0", 40);
        input.put("40.0", 20);
        input.put("60.0", 30);

        Map<String, Integer> result = binningService.bucketData(input);

        double previousLower = Double.NEGATIVE_INFINITY;
        for (String label : result.keySet()) {
            double lower = Double.parseDouble(label.split(" ")[0]);
            assertTrue(lower > previousLower, "Bins must be in ascending order, got: " + result.keySet());
            previousLower = lower;
        }
        String lastKey = result.keySet().stream().reduce((first, second) -> second).orElse("");
        assertTrue(lastKey.endsWith("+"), "Highest bin should carry the + label, got: " + lastKey);
        int totalInput = input.values().stream().mapToInt(Integer::intValue).sum();
        int totalOutput = result.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(totalInput, totalOutput);
    }

    @Test
    void bucketData_multiBinGap_fillsEveryEmptyBinWithZero() {
        // A dense cluster plus one far outlier leaves a run of several empty bins between them;
        // each must render as a zero bar rather than being silently omitted.
        Map<String, Integer> input = new LinkedHashMap<>();
        for (int i = 0; i <= 100; i++) {
            input.put(String.valueOf(i * 0.1), 10);
        }
        input.put("100.0", 10);

        Map<String, Integer> result = binningService.bucketData(input);

        long zeroBins = result.values().stream().filter(v -> v == 0).count();
        assertTrue(zeroBins >= 2, "Runs of empty bins should be zero-filled, got: " + result);
        int totalInput = input.values().stream().mapToInt(Integer::intValue).sum();
        int totalOutput = result.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(totalInput, totalOutput);
    }

    @Test
    void bucketData_collidingRoundedLabels_mergesCountsInsteadOfDropping() {
        // Many distinct values in a tiny range produce bins whose %.1f-rounded labels collide;
        // counts must merge rather than the earlier bin being overwritten.
        Map<String, Integer> input = new LinkedHashMap<>();
        for (int i = 0; i <= 40; i++) {
            input.put(String.valueOf(1.0 + i * 0.001), 10);
        }

        Map<String, Integer> result = binningService.bucketData(input);

        int totalInput = input.values().stream().mapToInt(Integer::intValue).sum();
        int totalOutput = result.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(totalInput, totalOutput, "Counts must never be lost to label collisions: " + result);
    }

    @Test
    void bucketData_singleNumericValueAmongNonNumericKeys_labelsAsSingleValue() {
        // isSameMinMax must be computed after non-numeric keys are dropped
        Map<String, Integer> input = new LinkedHashMap<>();
        input.put("not-a-number", 5);
        input.put("25.0", 100);

        Map<String, Integer> result = binningService.bucketData(input);

        assertEquals(1, result.size());
        assertEquals("25.0", result.keySet().iterator().next(), "Single data point should not be labeled as a range");
        assertEquals(100, result.values().iterator().next());
    }

    // ---- supplied range ----

    private static Map<String, Integer> ages(int from, int to) {
        Map<String, Integer> input = new LinkedHashMap<>();
        for (int age = from; age <= to; age++) {
            input.put(age + ".0", 3);
        }
        return input;
    }

    @Test
    void bucketData_withRange_spansTheRangeNotTheObservedValues() {
        Map<String, Integer> result = binningService.bucketData(ages(30, 40), new BinRange(18, 65), null);

        String first = result.keySet().iterator().next();
        assertTrue(first.startsWith("18.0"), first);
        assertEquals(0, result.get(first), "bins below the observed values are still emitted, empty");
        assertEquals(33, result.values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void bucketData_withRange_usesMoreBinsThanTheObservedSpread() {
        int observedBins = binningService.bucketData(ages(30, 40)).size();
        int rangeBins = binningService.bucketData(ages(30, 40), new BinRange(18, 65), null).size();
        assertTrue(rangeBins > observedBins, observedBins + " vs " + rangeBins);
    }

    @Test
    void bucketData_withRange_singleValueIsOneBinOverTheRange() {
        Map<String, Integer> result = binningService.bucketData(Map.of("25.0", 100), new BinRange(18, 65), null);
        assertEquals(Map.of("18.0 - 65.0", 100), result);
    }

    @Test
    void bucketData_withRange_valuesOutsideTheRangeGoToTheEndBins() {
        Map<String, Integer> input = ages(30, 40);
        input.put("10.0", 1);
        input.put("70.0", 2);

        Map<String, Integer> result = binningService.bucketData(input, new BinRange(18, 65), null);

        assertEquals(36, result.values().stream().mapToInt(Integer::intValue).sum());
        assertTrue(result.keySet().iterator().next().startsWith("18.0"));
    }

    @Test
    void binContinuousData_appliesRangesPerConcept() {
        Map<String, Map<String, Integer>> data = new LinkedHashMap<>();
        data.put("\\age\\", ages(30, 40));
        data.put("\\bmi\\", ages(30, 40));

        var result = binningService.binContinuousData(data, Map.of("\\age\\", new BinRange(18, 65)), null);

        assertTrue(result.get("\\age\\").keySet().iterator().next().startsWith("18.0"));
        assertTrue(result.get("\\bmi\\").keySet().iterator().next().startsWith("30.0"));
    }

    @Test
    void bucketData_maxBinsCapsTheBinCount() {
        Map<String, Integer> uncapped = binningService.bucketData(ages(30, 40), new BinRange(0, 1000), null);
        Map<String, Integer> capped = binningService.bucketData(ages(30, 40), new BinRange(0, 1000), 5);

        assertTrue(uncapped.size() > 5, "uncapped: " + uncapped.size());
        assertEquals(5, capped.size());
        assertTrue(capped.keySet().iterator().next().startsWith("0.0"));
        assertEquals(33, capped.values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void bucketData_maxBinsAboveTheFormulaChangesNothing() {
        assertEquals(binningService.bucketData(ages(30, 40)), binningService.bucketData(ages(30, 40), null, 50));
    }

    @Test
    void bucketData_withRange_lastBinKeepsItsUpperBound() {
        Map<String, Integer> result = binningService.bucketData(ages(30, 40), new BinRange(18, 65), 5);

        String last = result.keySet().stream().reduce((a, b) -> b).orElseThrow();
        assertTrue(last.endsWith(" - 65.0"), last);
        assertFalse(result.keySet().stream().anyMatch(label -> label.endsWith("+")));
    }

    @Test
    void bucketData_withoutRange_lastBinIsStillOpenEnded() {
        String last = binningService.bucketData(ages(30, 40)).keySet().stream().reduce((a, b) -> b).orElseThrow();
        assertTrue(last.endsWith(" +"), last);
    }
}
