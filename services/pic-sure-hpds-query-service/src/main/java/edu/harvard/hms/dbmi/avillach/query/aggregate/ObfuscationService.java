package edu.harvard.hms.dbmi.avillach.query.aggregate;

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

/**
 * Privacy-critical obfuscation for open (aggregate) v1 and v3 requests.
 *
 * <p>Cross counts are keyed {@code \_studies_consents\}, {@code \_studies_consents\<study>\} and
 * {@code \_studies_consents\<study>\<consent>\}. Consent groups are suppressed below the consent threshold, a study is the sum of its
 * consent groups, and the total is rounded. Chart buckets are suppressed below the chart threshold and rounded, and small cohorts get no
 * chart.
 */
@Service
public class ObfuscationService {

    private static final String STUDIES_CONSENTS_KEY = "\\_studies_consents\\";

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final VisualizationFormatter visualizationFormatter;

    private final int consentThreshold;
    // A suppressed consent group (0..consentThreshold-1) counts toward its study as the middle of that range, with a band covering it.
    private final int suppressedEstimate;
    private final int suppressedVariance;
    private final int maxVariance;
    private final int roundingStep;
    private final int chartThreshold;
    private final int chartVariance;
    private final int chartMinimumCohort;

    public ObfuscationService(AggregateProperties props, VisualizationFormatter visualizationFormatter) {
        this.visualizationFormatter = visualizationFormatter;
        this.consentThreshold = props.getObfuscation().getConsentThreshold();
        this.suppressedEstimate = Math.max(consentThreshold - 1, 0) / 2;
        this.suppressedVariance = Math.max(consentThreshold - 1, 0) - suppressedEstimate;
        this.maxVariance = 5 * suppressedVariance;
        this.roundingStep = consentThreshold;
        this.chartThreshold = 2 * consentThreshold;
        this.chartVariance = consentThreshold;
        this.chartMinimumCohort = props.getObfuscation().getChartMinimumCohort();
    }

    public Map<String, String> processCrossCounts(String entityString) throws JsonProcessingException {
        Map<String, String> crossCounts = objectMapper.readValue(entityString, new TypeReference<LinkedHashMap<String, String>>() {});
        return crossCounts == null ? null : obfuscateCrossCounts(crossCounts);
    }

    Map<String, String> obfuscateCrossCounts(Map<String, String> crossCounts) {
        Map<String, List<String>> consentKeysByStudy = new HashMap<>();
        crossCounts.keySet().forEach(key -> {
            List<String> segments = studyConsentSegments(key);
            if (segments != null && segments.size() == 2) {
                consentKeysByStudy.computeIfAbsent(segments.get(0), study -> new ArrayList<>()).add(key);
            }
        });

        Map<String, String> result = new LinkedHashMap<>();
        crossCounts.forEach((key, raw) -> {
            List<String> segments = studyConsentSegments(key);
            if (segments != null && segments.isEmpty()) {
                result.put(key, obfuscateTotal(raw));
            } else if (segments != null && segments.size() == 1 && consentKeysByStudy.containsKey(segments.get(0))) {
                result.put(key, obfuscateStudy(segments.get(0), raw, consentKeysByStudy.get(segments.get(0)), crossCounts));
            } else {
                // Consent groups, studies with no consent groups, and anything unexpected.
                result.put(key, obfuscateConsentGroup(raw));
            }
        });
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
        return isSuppressed(count) ? suppressedDisplay() : Integer.toString(count);
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
                sum += suppressedEstimate;
            } else {
                sum += count;
            }
        }

        Integer studyCount = parse(raw);
        if (allParsed && studyCount != null && studyCount != trueSum) {
            logger.warn("HPDS count for study {} differs from the sum of its consent groups", study);
        }

        if (suppressed == 0) {
            return Integer.toString(sum);
        }
        return sum + " ±" + Math.min(suppressed * suppressedVariance, maxVariance);
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
        return roundUp(count) + " ±" + maxVariance;
    }

    private boolean isSuppressed(Integer count) {
        return count == null || count < consentThreshold;
    }

    private String suppressedDisplay() {
        return "< " + consentThreshold;
    }

    /** True when the cohort is too small for a chart. Takes the raw backend cross count; a missing or unreadable total suppresses. */
    public boolean shouldSuppressChart(Map<String, String> crossCounts) {
        Integer total = crossCounts == null ? null : parse(crossCounts.get(STUDIES_CONSENTS_KEY));
        return total == null || total < chartMinimumCohort;
    }

    /** CATEGORICAL case: null when either input is missing or the cohort is too small, otherwise bucketed and obfuscated. */
    public String processCategoricalCrossCounts(String categoricalEntityString, String crossCountEntityString)
        throws JsonProcessingException {
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
            categorical.put(entry.getKey(), visualizationFormatter.processResults(entry.getValue()));
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
        int rounded = roundUp(count);
        return new ObfuscatedCount(rounded, rounded + " ±" + chartVariance, chartVariance);
    }

    private int roundUp(int count) {
        if (roundingStep <= 0) {
            return count;
        }
        return ((count + roundingStep - 1) / roundingStep) * roundingStep;
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
