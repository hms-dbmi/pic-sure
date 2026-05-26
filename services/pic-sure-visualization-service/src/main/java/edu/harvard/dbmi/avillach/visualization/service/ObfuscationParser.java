package edu.harvard.dbmi.avillach.visualization.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class ObfuscationParser {

    private static final Logger logger = LoggerFactory.getLogger(ObfuscationParser.class);
    private static final String HARMONIZED_CONSENT_KEY = "\\_harmonized_consent\\";

    private final String thresholdMarker;
    private final String varianceMarker;
    private final int thresholdReplacement;

    public ObfuscationParser(@Value("${obfuscation.threshold}") int threshold, @Value("${obfuscation.variance}") int variance) {
        this.thresholdMarker = "< " + threshold;
        this.varianceMarker = "±" + variance;
        this.thresholdReplacement = threshold - 1;
    }

    public boolean isObfuscated(Map<String, Map<String, String>> crossCounts) {
        for (Map.Entry<String, Map<String, String>> entry : crossCounts.entrySet()) {
            for (String value : entry.getValue().values()) {
                if (value.contains(thresholdMarker) || value.contains(varianceMarker)) {
                    return true;
                }
            }
        }
        return false;
    }

    public Map<String, Map<String, Integer>> clean(Map<String, Map<String, String>> crossCounts) {
        Map<String, Map<String, Integer>> cleaned = new HashMap<>();
        String thresholdReplacementStr = String.valueOf(thresholdReplacement);

        crossCounts.forEach((key, value) -> {
            if (HARMONIZED_CONSENT_KEY.equals(key)) {
                return;
            }
            Map<String, Integer> parsed = new HashMap<>();
            value.forEach((subKey, subValue) -> {
                String cleanedValue = subValue;
                if (cleanedValue.contains(thresholdMarker)) {
                    cleanedValue = cleanedValue.replace(thresholdMarker, thresholdReplacementStr);
                } else if (cleanedValue.contains(varianceMarker)) {
                    cleanedValue = cleanedValue.replace(varianceMarker, "");
                }
                try {
                    parsed.put(subKey, Integer.parseInt(cleanedValue.trim()));
                } catch (NumberFormatException e) {
                    logger.warn("Could not parse obfuscated value '{}' for key '{}', skipping", subValue, subKey);
                }
            });
            cleaned.put(key, parsed);
        });

        return cleaned;
    }
}
