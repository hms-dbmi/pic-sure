package edu.harvard.hms.dbmi.avillach.query.aggregate;

import edu.harvard.dbmi.avillach.domain.ObfuscatedCount;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.query.config.AggregateProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Privacy-critical obfuscation for open (aggregate) requests.
 *
 * <p>Cross counts are keyed {@code \_studies_consents\}, {@code \_studies_consents\<study>\} and
 * {@code \_studies_consents\<study>\<consent>\}. Below the consent threshold a value is suppressed. Otherwise a consent group shows the
 * midpoint of its threshold-wide bucket, a study shows the bucket midpoint of its consent groups' sum, and the total is rounded up. Chart
 * buckets are suppressed below the chart threshold and rounded, and small cohorts get no chart.
 */
@Service
public class ObfuscationService {

    private static final String STUDIES_CONSENTS_KEY = "\\_studies_consents\\";
    private static final int MAX_CHART_BINS = 50;
    /** The consent threshold can only be raised; lower values would weaken open access below the agreed floor. */
    static final int MIN_CONSENT_THRESHOLD = 10;

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final VisualizationFormatter visualizationFormatter;

    private final int consentThreshold;
    // Half a consent bucket: the band around a bucket midpoint, and what a suppressed consent group adds to its study, with that band.
    private final int consentHalf;
    private final int chartThreshold;
    private final int chartHalf;
    private final int chartMinimumCohort;
    private final boolean crossCountTotalOnly;

    public ObfuscationService(AggregateProperties props, VisualizationFormatter visualizationFormatter) {
        this.visualizationFormatter = visualizationFormatter;
        int configured = props.getObfuscation().getConsentThreshold();
        if (configured < MIN_CONSENT_THRESHOLD) {
            logger.warn(
                "aggregate.obfuscation.consent-threshold={} is below the minimum of {}; using {}", configured, MIN_CONSENT_THRESHOLD,
                MIN_CONSENT_THRESHOLD
            );
        }
        this.consentThreshold = Math.max(configured, MIN_CONSENT_THRESHOLD);
        this.consentHalf = consentThreshold / 2;
        this.chartThreshold = props.getObfuscation().getChartThreshold();
        this.chartHalf = chartThreshold / 2;
        this.chartMinimumCohort = props.getObfuscation().getChartMinimumCohort();
        this.crossCountTotalOnly = props.getObfuscation().isCrossCountTotalOnly();
    }

    public Map<String, String> processCrossCounts(String entityString) throws JsonProcessingException {
        Map<String, String> crossCounts = objectMapper.readValue(entityString, new TypeReference<LinkedHashMap<String, String>>() {});
        return crossCounts == null ? null : obfuscateCrossCounts(crossCounts);
    }

    Map<String, String> obfuscateCrossCounts(Map<String, String> crossCounts) {
        // Parse each key's path once and reuse it below; the key set is walked three times otherwise.
        Map<String, List<String>> segmentsByKey = new LinkedHashMap<>();
        crossCounts.keySet().forEach(key -> segmentsByKey.put(key, studyConsentSegments(key)));

        Map<String, List<String>> consentKeysByStudy = new HashMap<>();
        segmentsByKey.forEach((key, segments) -> {
            if (segments != null && segments.size() == 2) {
                consentKeysByStudy.computeIfAbsent(segments.get(0), study -> new ArrayList<>()).add(key);
            }
        });

        Map<String, String> result = new LinkedHashMap<>();
        crossCounts.forEach((key, raw) -> {
            List<String> segments = segmentsByKey.get(key);
            if (segments != null && segments.isEmpty()) {
                result.put(key, obfuscateTotal(raw));
            } else if (segments != null && segments.size() == 1 && consentKeysByStudy.containsKey(segments.get(0))) {
                result.put(key, obfuscateStudy(segments.get(0), raw, consentKeysByStudy.get(segments.get(0)), crossCounts));
            } else {
                // Consent groups, studies with no consent groups, and anything unexpected.
                result.put(key, obfuscateConsentGroup(raw));
            }
        });

        // Everything above is still computed (study sums, mismatch warnings); when configured, only the total reaches the client so a
        // consent group cannot be reconstructed from its study and sibling counts.
        if (crossCountTotalOnly) {
            Map<String, String> totalOnly = new LinkedHashMap<>();
            result.forEach((key, value) -> {
                List<String> segments = segmentsByKey.get(key);
                if (segments != null && segments.isEmpty()) {
                    totalOnly.put(key, value);
                }
            });
            return totalOnly;
        }
        return result;
    }

    /** Path segments below {@code \_studies_consents\}, or null for a key outside it. Tolerates a missing trailing separator. */
    private static List<String> studyConsentSegments(String key) {
        String root = STUDIES_CONSENTS_KEY.substring(0, STUDIES_CONSENTS_KEY.length() - 1);
        if (!key.startsWith(root)) {
            return null;
        }
        String rest = key.substring(root.length());
        if (!rest.isEmpty() && rest.charAt(0) != '\\') {
            return null;
        }
        return Arrays.stream(rest.split("\\\\")).filter(segment -> !segment.isEmpty()).toList();
    }

    private String obfuscateConsentGroup(String raw) {
        Integer count = parse(raw);
        return isSuppressed(count) ? suppressedDisplay() : bucketMidpoint(count) + " ±" + consentHalf;
    }

    private String obfuscateStudy(String study, String raw, List<String> consentKeys, Map<String, String> crossCounts) {
        int sum = 0;
        int trueSum = 0;
        int suppressed = 0;
        boolean allParsed = true;
        for (String consentKey : consentKeys) {
            Integer count = parse(crossCounts.get(consentKey));
            if (count == null) {
                allParsed = false;
            } else {
                trueSum += count;
            }
            if (isSuppressed(count)) {
                suppressed++;
                sum += consentHalf;
            } else {
                sum += count;
            }
        }

        Integer studyCount = parse(raw);
        if (allParsed && studyCount != null && studyCount != trueSum) {
            logger.warn("HPDS count for study {} differs from the sum of its consent groups", study);
        }

        // Always obfuscated: the sum's bucket midpoint, banded for the bucket plus each suppressed group's estimate.
        if (sum < consentThreshold) {
            return suppressedDisplay();
        }
        return bucketMidpoint(sum) + " ±" + (consentHalf + suppressed * consentHalf);
    }

    /** COUNT case: takes the study-consents CROSS_COUNT and returns only its obfuscated total. A missing total is suppressed. */
    public String processCount(String crossCountEntityString) throws JsonProcessingException {
        Map<String, String> crossCounts = objectMapper.readValue(crossCountEntityString, new TypeReference<>() {});
        return obfuscateTotal(crossCounts == null ? null : crossCounts.get(STUDIES_CONSENTS_KEY));
    }

    private String obfuscateTotal(String raw) {
        Integer count = parse(raw);
        if (isSuppressed(count)) {
            return suppressedDisplay();
        }
        return roundUp(count, consentHalf) + " ±" + consentThreshold;
    }

    private boolean isSuppressed(Integer count) {
        return count == null || count < consentThreshold;
    }

    private String suppressedDisplay() {
        return "< " + consentThreshold;
    }

    /**
     * Most bins an open continuous chart may have: the cohort total divided by the chart threshold, between 1 and 50. Uses the total as
     * displayed (rounded), so the bin count reveals nothing more precise about the cohort size.
     */
    public int maxChartBins(Map<String, String> crossCounts) {
        Integer total = crossCounts == null ? null : parse(crossCounts.get(STUDIES_CONSENTS_KEY));
        if (total == null || chartThreshold <= 0) {
            return 1;
        }
        return Math.max(1, Math.min(roundUp(total, consentHalf) / chartThreshold, MAX_CHART_BINS));
    }

    /** True when the cohort is too small for a chart. Takes the raw backend cross count; a missing or unreadable total suppresses. */
    public boolean shouldSuppressChart(Map<String, String> crossCounts) {
        Integer total = crossCounts == null ? null : parse(crossCounts.get(STUDIES_CONSENTS_KEY));
        return total == null || total < chartMinimumCohort;
    }

    /**
     * CATEGORICAL case: null when either input is missing or the cohort is too small, otherwise bucketed and obfuscated.
     *
     * @param categoryValues every value each concept has in the dataset; a concept missing from it keeps its grouped bucket
     */
    public String processCategoricalCrossCounts(
        String categoricalEntityString, String crossCountEntityString, Map<String, Set<String>> categoryValues
    ) throws JsonProcessingException {
        if (categoricalEntityString == null || crossCountEntityString == null) {
            return null;
        }
        Map<String, String> crossCounts = objectMapper.readValue(crossCountEntityString, new TypeReference<>() {});
        if (shouldSuppressChart(crossCounts)) {
            return null;
        }

        Map<String, Map<String, Object>> categorical = objectMapper.readValue(categoricalEntityString, new TypeReference<>() {});
        if (categorical == null) {
            return categoricalEntityString;
        }
        for (Map.Entry<String, Map<String, Object>> entry : categorical.entrySet()) {
            if (visualizationFormatter.skipKey(entry.getKey())) continue;
            categorical.put(
                entry.getKey(), visualizationFormatter.processResults(entry.getValue(), chartThreshold, categoryValues.get(entry.getKey()))
            );
        }
        return objectMapper.writeValueAsString(obfuscateChartCounts(categorical));
    }

    public Map<String, Map<String, ObfuscatedCount>> obfuscateChartCounts(Map<String, Map<String, Object>> chartCounts) {
        Map<String, Map<String, ObfuscatedCount>> result = new LinkedHashMap<>();
        chartCounts.forEach((key, value) -> {
            Map<String, ObfuscatedCount> obfuscated = new LinkedHashMap<>();
            value.forEach((innerKey, innerValue) -> obfuscated.put(innerKey, obfuscateChartBucket(toInt(innerValue))));
            result.put(key, obfuscated);
        });
        return result;
    }

    /** Below the chart threshold: count 0 with band threshold-1, which is how consumers draw the hidden range. */
    ObfuscatedCount obfuscateChartBucket(int count) {
        if (count < chartThreshold) {
            return new ObfuscatedCount(0, "< " + chartThreshold, chartThreshold - 1);
        }
        int rounded = roundUp(count, chartHalf);
        return new ObfuscatedCount(rounded, rounded + " ±" + chartHalf, chartHalf);
    }

    /** Midpoint of the consent-threshold-wide bucket a count falls in: with a threshold of 10, 10..19 is 15 and 40..49 is 45. */
    private int bucketMidpoint(int count) {
        return (count / consentThreshold) * consentThreshold + consentHalf;
    }

    private static int roundUp(int count, int step) {
        if (step <= 0) {
            return count;
        }
        return ((count + step - 1) / step) * step;
    }

    private Integer parse(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            logger.warn("Cross count was not a number; treating it as suppressed");
            return null;
        }
    }

    /** Narrows Jackson's Object-typed Integer, Long, Double, or numeric String counts to int. */
    static int toInt(Object value) {
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) return Integer.parseInt(s);
        throw new IllegalArgumentException("Cross-count value was neither Number nor numeric String: " + value);
    }
}
