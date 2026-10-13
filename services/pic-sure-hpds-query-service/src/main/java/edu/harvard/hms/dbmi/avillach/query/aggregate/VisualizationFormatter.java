package edu.harvard.hms.dbmi.avillach.query.aggregate;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

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
    private static final int LIMIT_SIZE = 7;
    /** Distinct from any plausible real category, so it cannot overwrite one. */
    public static final String GROUPED_KEY = "Other (grouped)";

    public boolean skipKey(String key) {
        return key.equals(CONSENTS_KEY) || key.equals(HARMONIZED_CONSENT_KEY) || key.equals(TOPMED_CONSENTS_KEY)
            || key.equals(PARENT_CONSENTS_KEY);
    }

    /**
     * Groups an open categorical chart's categories. A category below {@code foldBelow}, or beyond the {@value #LIMIT_SIZE} largest, is
     * folded into {@link #GROUPED_KEY}, which is present so its presence reveals nothing. The one exception: when every value the concept
     * can have ({@code possibleValues}, from dataset-wide metadata) is already named, the bucket is necessarily empty and is left out,
     * which anyone could work out from public information. Named categories are ordered by label, not count, so their order reveals nothing
     * about counts that obfuscation rounds together.
     */
    public Map<String, Object> processResults(Map<String, Object> axisMap, int foldBelow) {
        return processResults(axisMap, foldBelow, null);
    }

    public Map<String, Object> processResults(Map<String, Object> axisMap, int foldBelow, Set<String> possibleValues) {
        Map<String, Integer> named = new HashMap<>();
        int grouped = 0;
        for (Map.Entry<String, Object> entry : axisMap.entrySet()) {
            if (!(entry.getValue() instanceof Number number)) {
                continue;
            }
            int count = number.intValue();
            if (count < foldBelow || GROUPED_KEY.equals(entry.getKey())) {
                grouped += count;
            } else {
                named.put(entry.getKey(), count);
            }
        }

        List<Map.Entry<String, Integer>> largest =
            named.entrySet().stream().sorted(Collections.reverseOrder(Map.Entry.comparingByValue())).toList();
        Map<String, Integer> kept = new HashMap<>();
        for (int i = 0; i < largest.size(); i++) {
            if (i < LIMIT_SIZE) {
                kept.put(largest.get(i).getKey(), largest.get(i).getValue());
            } else {
                grouped += largest.get(i).getValue();
            }
        }

        Map<String, Object> result = new LinkedHashMap<>(new TreeMap<>(limitKeySize(kept)));
        boolean everyValueNamed = possibleValues != null && !possibleValues.isEmpty() && kept.keySet().containsAll(possibleValues);
        if (grouped > 0 || !everyValueNamed) {
            result.put(GROUPED_KEY, grouped);
        }
        return result;
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
