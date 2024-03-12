package edu.harvard.dbmi.avillach.util;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class VisualizationUtil {

    protected static final String CONSENTS_KEY = "\\_consents\\";
    protected static final String HARMONIZED_CONSENT_KEY = "\\_harmonized_consent\\";
    protected static final String TOPMED_CONSENTS_KEY = "\\_topmed_consents\\";
    protected static final String PARENT_CONSENTS_KEY = "\\_parent_consents\\";
    private static final int MAX_X_LABEL_LINE_LENGTH = 45;
    private static final boolean LIMITED = true;
    private static final int LIMIT_SIZE = 7;

    public static boolean skipKey(String key) {
        return key.equals(CONSENTS_KEY) ||
                key.equals(HARMONIZED_CONSENT_KEY) ||
                key.equals(TOPMED_CONSENTS_KEY) ||
                key.equals(PARENT_CONSENTS_KEY);
    }

    public static Map<String, Object> processResults(Map<String, Object> axisMap) {
        Map<String, Integer> convertedAxisMap = new HashMap<>();
        for (Map.Entry<String, Object> entry : axisMap.entrySet()) {
            if (entry.getValue() instanceof Integer) {
                convertedAxisMap.put(entry.getKey(), (Integer) entry.getValue());
            }
        }
        Map<String, Integer> stringIntegerMap = doProcessResults(convertedAxisMap);
        return new HashMap<>(stringIntegerMap);
    }

    /**
     * Sorts the map and if there is more than the LIMIT_SIZE then we also get the greatest 7 categories and then combines
     * the others into an "other" category. Also replace long column names with shorter version.
     *
     * @param axisMap - Map of the categories and their counts
     * @return Map<String, Integer> - sorted map of the categories and their counts with the "other" category added if necessary
     */
    public static Map<String, Integer> doProcessResults(Map<String, Integer> axisMap) {
        Map<String, Integer> finalAxisMap = axisMap;
        if (LIMITED && axisMap.size() > (LIMIT_SIZE + 1)) {
            //Create Other bar and sort
            Supplier<Stream<Map.Entry<String, Integer>>> stream = () -> finalAxisMap.entrySet().stream().sorted(Collections.reverseOrder(Map.Entry.comparingByValue()));
            Integer otherSum = stream.get().skip(LIMIT_SIZE).mapToInt(Map.Entry::getValue).sum();
            axisMap = stream.get().limit(LIMIT_SIZE).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e2, LinkedHashMap::new));
            axisMap = limitKeySize(axisMap).entrySet()
                    .stream()
                    .sorted(Collections.reverseOrder(Map.Entry.comparingByValue()))
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            Map.Entry::getValue,
                            (e1, e2) -> e2,
                            LinkedHashMap::new));
            axisMap.put("Other", otherSum);
        } else {
            axisMap = limitKeySize(finalAxisMap).entrySet()
                    .stream()
                    .sorted(Collections.reverseOrder(Map.Entry.comparingByValue()))
                    .collect(Collectors.toMap(Map.Entry::getKey,
                            Map.Entry::getValue,
                            (e1, e2) -> e2,
                            LinkedHashMap::new));
        }
        return axisMap;
    }

    /**
     * This method is used to limit the size of the keys in the axisMap to a maximum of 45 characters. If the key is longer
     * than 45 characters, it will be shortened to 45 characters and the last 3 characters will be replaced with "...".
     * If the shortened key is not unique, we will create a unique one
     * <p>
     *
     * @param axisMap - Map of the categories and their counts
     * @return Map<String, Integer> - Map of the categories and their counts with the keys limited to 45 characters
     */
    public static Map<String, Integer> limitKeySize(Map<String, Integer> axisMap) {
        if (axisMap == null) {
            throw new IllegalArgumentException("axisMap cannot be null");
        }

        Map<String, Integer> newAxisMap = new HashMap<>();
        HashSet<String> keys = new HashSet<>();
        axisMap.forEach((key, value) -> {
            String adjustedKey = key.length() < MAX_X_LABEL_LINE_LENGTH ? key : createAdjustedKey(axisMap, keys, key);
            newAxisMap.put(adjustedKey, value);
            keys.add(adjustedKey);
        });
        return newAxisMap;
    }

    private static String createAdjustedKey(Map<String, Integer> axisMap, HashSet<String> keys, String key) {
        String keyPrefix = key.substring(0, MAX_X_LABEL_LINE_LENGTH);
        return isKeyPrefixInAxisMap(axisMap, keyPrefix) ? generateUniqueKey(keys, key) : appendEllipsis(keyPrefix);
    }

    private static boolean isKeyPrefixInAxisMap(Map<String, Integer> axisMap, String keyPrefix) {
        return axisMap.keySet().stream().anyMatch(k -> k.startsWith(keyPrefix));
    }

    private static String generateUniqueKey(HashSet<String> keys, String key) {
        int countFromEnd = 6;
        String proposedKey;
        do {
            proposedKey = String.format("%s...%s", key.substring(0, MAX_X_LABEL_LINE_LENGTH - 3 - countFromEnd), key.substring(key.length() - countFromEnd));
            countFromEnd++;
        } while (keys.contains(proposedKey));
        return proposedKey;
    }

    private static String appendEllipsis(String keyPrefixAdjusted) {
        return String.format("%s...", keyPrefixAdjusted);
    }

}
