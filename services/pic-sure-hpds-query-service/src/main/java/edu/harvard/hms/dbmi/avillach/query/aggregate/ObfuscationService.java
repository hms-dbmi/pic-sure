package edu.harvard.hms.dbmi.avillach.query.aggregate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.query.config.AggregateProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Pure obfuscation math ported from {@code edu.harvard.hms.dbmi.avillach.AggregateDataSharingResourceRS} (identical across the WAR's V1 and
 * V3 variants). This is the privacy-critical surface: the threshold/variance/salt math, the randomize() logic, and the "&lt; threshold"
 * suppression rule must match the WAR EXACTLY -- any divergence changes what counts get suppressed/perturbed, which is a privacy
 * regression.
 */
@Service
public class ObfuscationService {

    private static final String STUDIES_CONSENTS_KEY = "\\_studies_consents\\";

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final VisualizationFormatter visualizationFormatter;

    private final int threshold;
    private final int variance;
    private final String randomSalt;

    public ObfuscationService(AggregateProperties props, VisualizationFormatter visualizationFormatter) {
        this.visualizationFormatter = visualizationFormatter;
        this.threshold = props.getObfuscation().getThreshold();
        this.variance = props.getObfuscation().getVariance();
        String salt = props.getObfuscation().getSalt();
        this.randomSalt = (salt == null || salt.isBlank()) ? UUID.randomUUID().toString() : salt;
    }

    public int getThreshold() {
        return threshold;
    }

    /**
     * Generates a random variance for the request based on the passed entityString. The variance is between -variance and +variance: the
     * salt is appended to the entityString, hashed, and the hashcode is taken mod (variance * 2 + 1), shifted down by variance. Ported
     * verbatim from the WAR's generateRequestVariance().
     */
    int generateRequestVariance(String entityString) {
        return Math.abs((entityString + randomSalt).hashCode()) % (variance * 2 + 1) - variance;
    }

    ObfuscatedCount randomize(int crossCount, int requestVariance) {
        int randomized = Math.max(crossCount + requestVariance, threshold);
        return new ObfuscatedCount(randomized, randomized + " ±" + variance, variance);
    }

    /**
     * Core privacy floor: small (potentially identifiable) cohorts get hidden behind a "&lt; threshold" display. The value is encoded as
     * count 0 with variance threshold-1, so consumers rendering the uncertainty band [max(0, count - variance), count + variance] draw
     * 0..threshold-1 -- the true count lies somewhere in that band but we don't disclose where.
     *
     * <p>Returns empty when the value is at or above the threshold (caller should then call {@link #randomize}).
     */
    Optional<ObfuscatedCount> applyThresholdFloor(int actualCount) {
        if (actualCount < threshold) {
            return Optional.of(new ObfuscatedCount(0, "< " + threshold, threshold - 1));
        }
        return Optional.empty();
    }

    /**
     * String overload for callers (COUNT / CROSS_COUNT) that hold the value as a JSON string. Logs and returns empty on parse failure so
     * the caller can fall through to its untouched-value path.
     */
    Optional<ObfuscatedCount> applyThresholdFloor(String actualCount) {
        try {
            return applyThresholdFloor(Integer.parseInt(actualCount));
        } catch (NumberFormatException nfe) {
            logger.warn("Count was not a number! {}", actualCount);
            return Optional.empty();
        }
    }

    /** COUNT case: floor if below threshold, else variance-randomize; non-numeric passes through unchanged. */
    public String obfuscateCount(String entityString) {
        try {
            int count = Integer.parseInt(entityString);
            int requestVariance = generateRequestVariance(entityString);
            return applyThresholdFloor(count).map(ObfuscatedCount::display).orElseGet(() -> randomize(count, requestVariance).display());
        } catch (NumberFormatException nfe) {
            logger.warn("COUNT response was not a number! {}", entityString);
            return entityString;
        }
    }

    /** CROSS_COUNT case: floor-then-randomize each entry with one deterministic per-query variance. */
    public Map<String, String> processCrossCounts(String entityString) throws JsonProcessingException {
        Map<String, String> crossCounts = objectMapper.readValue(entityString, new TypeReference<>() {});
        int requestVariance = generateVarianceWithCrossCounts(crossCounts);
        return obfuscateCrossCounts(crossCounts, requestVariance);
    }

    private Map<String, String> obfuscateCrossCounts(Map<String, String> crossCounts, int requestVariance) {
        Set<String> obfuscatedKeys = new HashSet<>();
        if (crossCounts != null) {
            crossCounts.keySet().forEach(key -> {
                String crossCount = crossCounts.get(key);
                Optional<ObfuscatedCount> floored = applyThresholdFloor(crossCount);
                floored.ifPresent(x -> obfuscatedKeys.add(key));
                crossCounts.put(key, floored.map(ObfuscatedCount::display).orElse(crossCount));
            });
            crossCounts.keySet().forEach(key -> {
                String crossCount = crossCounts.get(key);
                if (!obfuscatedKeys.contains(key)) {
                    crossCounts.put(key, randomize(Integer.parseInt(crossCount), requestVariance).display());
                }
            });
        }
        return crossCounts;
    }

    /** Deterministic per-query variance from the sorted key:value\n join (so reruns match). */
    public int generateVarianceWithCrossCounts(Map<String, String> crossCounts) {
        List<Map.Entry<String, String>> entryList = new ArrayList<>(crossCounts.entrySet());
        entryList.sort(Map.Entry.comparingByKey());
        StringBuilder crossCountsString = new StringBuilder();
        entryList.forEach(e -> crossCountsString.append(e.getKey()).append(":").append(e.getValue()).append("\n"));
        return generateRequestVariance(crossCountsString.toString());
    }

    /**
     * Suppress continuous when the study-consents cross-count is below threshold or zero. The WAR ran this check on an already-obfuscated
     * map, so only the display forms "&lt; threshold" and "0" could appear; here the caller passes the RAW backend cross-count, so raw
     * numerics (e.g. "5") must be compared numerically or a below-threshold cohort's distribution leaks. Missing or unparseable counts fail
     * closed (suppress).
     */
    public boolean shouldSuppressContinuousCrossCounts(Map<String, String> crossCounts) {
        String v = crossCounts == null ? null : crossCounts.get(STUDIES_CONSENTS_KEY);
        if (v == null) {
            return true;
        }
        if (v.contains("< " + this.threshold)) {
            return true;
        }
        try {
            return Integer.parseInt(v.trim()) < this.threshold;
        } catch (NumberFormatException nfe) {
            logger.warn("Study-consents cross-count was not a number ({}); suppressing continuous response", v);
            return true;
        }
    }

    /** CATEGORICAL case: bucket via the formatter, then obfuscate. Null inputs short-circuit to null. */
    public String processCategoricalCrossCounts(String categoricalEntityString, String crossCountEntityString)
        throws JsonProcessingException {
        if (categoricalEntityString == null || crossCountEntityString == null) {
            return null;
        }
        Map<String, String> crossCounts = objectMapper.readValue(crossCountEntityString, new TypeReference<>() {});
        int generatedVariance = generateVarianceWithCrossCounts(crossCounts);

        Map<String, Map<String, Object>> categorical = objectMapper.readValue(categoricalEntityString, new TypeReference<>() {});
        if (categorical == null) {
            return categoricalEntityString;
        }
        for (Map.Entry<String, Map<String, Object>> entry : categorical.entrySet()) {
            if (visualizationFormatter.skipKey(entry.getKey())) continue;
            categorical.put(entry.getKey(), visualizationFormatter.processResults(entry.getValue()));
        }
        return objectMapper.writeValueAsString(obfuscateCrossCount(generatedVariance, categorical));
    }

    /** Obfuscate every value of a nested cross-count map. */
    public Map<String, Map<String, ObfuscatedCount>> obfuscateCrossCount(
        int generatedVariance, Map<String, Map<String, Object>> crossCount
    ) {
        Map<String, Map<String, ObfuscatedCount>> result = new LinkedHashMap<>();
        crossCount.forEach((key, value) -> {
            Map<String, ObfuscatedCount> obfuscated = new LinkedHashMap<>();
            value.forEach((innerKey, innerValue) -> {
                int count = toInt(innerValue);
                obfuscated.put(innerKey, applyThresholdFloor(count).orElseGet(() -> randomize(count, generatedVariance)));
            });
            result.put(key, obfuscated);
        });
        return result;
    }

    /** Jackson hands Object-typed counts (Integer/Long/Double/String); narrow to int, preserving the WAR's breadth. */
    static int toInt(Object value) {
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) return Integer.parseInt(s);
        throw new IllegalArgumentException("Cross-count value was neither Number nor numeric String: " + value);
    }
}
