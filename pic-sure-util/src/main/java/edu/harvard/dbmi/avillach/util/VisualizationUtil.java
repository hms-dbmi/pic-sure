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

    public static boolean skipKey(Map.Entry<String, Map<String, Integer>> entry) {
        return entry.getKey().equals(CONSENTS_KEY) ||
                entry.getKey().equals(HARMONIZED_CONSENT_KEY) ||
                entry.getKey().equals(TOPMED_CONSENTS_KEY) ||
                entry.getKey().equals(PARENT_CONSENTS_KEY);
    }

    /**
     * Sorts the map and if there is more than the LIMIT_SIZE then we also get the greatest 7 categories and then combines
     * the others into an "other" category. Also replace long column names with shorter version.
     *
     * @param axisMap - Map of the categories and their counts
     * @return Map<String, Integer> - sorted map of the categories and their counts with the "other" category added if necessary
     */
    public static Map<String, Integer> processResults(Map<String, Integer> axisMap) {
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
     * Replaces long column names with shorter version.
     *
     * @param axisMap
     * @return
     */
    private static Map<String, Integer> limitKeySize(Map<String, Integer> axisMap) {
        List<String> toRemove = new ArrayList<>();
        Map<String, Integer> toAdd = new HashMap<>();
        axisMap.keySet().forEach(key -> {
            if (key.length() > MAX_X_LABEL_LINE_LENGTH) {
                toRemove.add(key);
                toAdd.put(
                        key.substring(0, MAX_X_LABEL_LINE_LENGTH - 3) + "...",
                        axisMap.get(key));
            }
        });
        toRemove.forEach(key -> axisMap.remove(key));
        axisMap.putAll(toAdd);
        return axisMap;
    }

}
