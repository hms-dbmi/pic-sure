package edu.harvard.hms.dbmi.avillach.query.aggregate;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Formats categorical aggregate results for visualization. It is self-contained so the query service needs no separate visualization
 * utility dependency.
 */
@Component
public class VisualizationFormatter {

    private static final String CONSENTS_KEY = "\\_consents\\";
    private static final String HARMONIZED_CONSENT_KEY = "\\_harmonized_consent\\";
    private static final String TOPMED_CONSENTS_KEY = "\\_topmed_consents\\";
    private static final String PARENT_CONSENTS_KEY = "\\_parent_consents\\";
    private static final int MAX_X_LABEL_LINE_LENGTH = 45;
    private static final boolean LIMITED = true;
    private static final int LIMIT_SIZE = 7;

    public boolean skipKey(String key) {
        return key.equals(CONSENTS_KEY) || key.equals(HARMONIZED_CONSENT_KEY) || key.equals(TOPMED_CONSENTS_KEY)
            || key.equals(PARENT_CONSENTS_KEY);
    }

    public Map<String, Object> processResults(Map<String, Object> axisMap) {
        Map<String, Integer> converted = new HashMap<>();
        for (Map.Entry<String, Object> entry : axisMap.entrySet()) {
            if (entry.getValue() instanceof Integer i) {
                converted.put(entry.getKey(), i);
            }
        }
        return new HashMap<>(doProcessResults(converted));
    }

    private Map<String, Integer> doProcessResults(Map<String, Integer> axisMap) {
        Map<String, Integer> finalAxisMap = axisMap;
        if (LIMITED && axisMap.size() > (LIMIT_SIZE + 1)) {
            Supplier<Stream<Map.Entry<String, Integer>>> stream =
                () -> finalAxisMap.entrySet().stream().sorted(Collections.reverseOrder(Map.Entry.comparingByValue()));
            Integer otherSum = stream.get().skip(LIMIT_SIZE).mapToInt(Map.Entry::getValue).sum();
            axisMap = stream.get().limit(LIMIT_SIZE)
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

    private Map<String, Integer> limitKeySize(Map<String, Integer> axisMap) {
        if (axisMap == null) throw new IllegalArgumentException("axisMap cannot be null");
        Map<String, Integer> newAxisMap = new HashMap<>();
        HashSet<String> keys = new HashSet<>();
        axisMap.forEach((key, value) -> {
            String adjustedKey = key.length() < MAX_X_LABEL_LINE_LENGTH ? key : createAdjustedKey(axisMap, keys, key);
            newAxisMap.put(adjustedKey, value);
            keys.add(adjustedKey);
        });
        return newAxisMap;
    }

    private String createAdjustedKey(Map<String, Integer> axisMap, HashSet<String> keys, String key) {
        String keyPrefix = key.substring(0, MAX_X_LABEL_LINE_LENGTH);
        return isKeyPrefixInAxisMap(axisMap, keyPrefix) ? generateUniqueKey(keys, key) : appendEllipsis(keyPrefix);
    }

    private boolean isKeyPrefixInAxisMap(Map<String, Integer> axisMap, String keyPrefix) {
        return axisMap.keySet().stream().anyMatch(k -> k.startsWith(keyPrefix));
    }

    private String generateUniqueKey(HashSet<String> keys, String key) {
        int countFromEnd = 6;
        String proposedKey;
        do {
            proposedKey = String.format(
                "%s...%s", key.substring(0, MAX_X_LABEL_LINE_LENGTH - 3 - countFromEnd), key.substring(key.length() - countFromEnd)
            );
            countFromEnd++;
        } while (keys.contains(proposedKey));
        return proposedKey;
    }

    private String appendEllipsis(String keyPrefixAdjusted) {
        return String.format("%s...", keyPrefixAdjusted);
    }
}
