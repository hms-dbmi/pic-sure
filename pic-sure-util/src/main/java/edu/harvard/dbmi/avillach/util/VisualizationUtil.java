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
        Map<String, Integer> newAxisMap = new HashMap<>();
        HashSet<String> keys = new HashSet<>();

        axisMap.forEach((key, value) -> {
            if (key.length() < MAX_X_LABEL_LINE_LENGTH) {
                newAxisMap.put(key, value);
                keys.add(key);
            } else {
                // proposed key
                StringBuilder proposedKey;

                // Check if the key exists in the keys set.
                boolean hasEqual = false;
                // Let first check if the proposed key is a leading substring of another key
                for (String innerKey : axisMap.keySet()) {// Check if this key will be equal to any future keys.
                    if (innerKey.length() >= MAX_X_LABEL_LINE_LENGTH) {
                        hasEqual = key.substring(0, MAX_X_LABEL_LINE_LENGTH).equals(innerKey.substring(0, MAX_X_LABEL_LINE_LENGTH));
                        if (hasEqual) {
                            break;
                        }
                    }
                }

                if (hasEqual) { // if we have an equal we want to create a more unique key
                    int countFromEnd = 6;
                    String uniqueness;
                    do {
                        // get the original key substring max x - 3 (for the dots) - countFromEnd.
                        proposedKey = new StringBuilder(key.substring(0, MAX_X_LABEL_LINE_LENGTH - 3 - countFromEnd));

                        // Get the last "countFromEnd" characters
                        uniqueness = key.substring(key.length() - countFromEnd);

                        proposedKey.append("...").append(uniqueness);
                        countFromEnd++; // increase the number of characters from the end of the string we will use
                    } while (keys.contains(proposedKey.toString()));

                } else {
                    proposedKey = new StringBuilder(key.substring(0, MAX_X_LABEL_LINE_LENGTH - 3) + "...");
                }

                newAxisMap.put(proposedKey.toString(), value);
                keys.add(proposedKey.toString());
            }
        });

        return newAxisMap;
    }

}
