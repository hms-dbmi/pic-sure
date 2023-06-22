package edu.harvard.hms.dbmi.avillach.resource.visualization.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.resource.visualization.model.CategoricalData;
import edu.harvard.hms.dbmi.avillach.resource.visualization.model.ContinuousData;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestTemplate;

import javax.ejb.Stateless;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Stateless
public class DataProcessingService {

    private Logger logger = LoggerFactory.getLogger(DataProcessingService.class);

    private static final String CONSENTS_KEY = "\\_consents\\";
    private static final String HARMONIZED_CONSENT_KEY = "\\_harmonized_consent\\";
    private static final String TOPMED_CONSENTS_KEY = "\\_topmed_consents\\";
    private static final String PARENT_CONSENTS_KEY = "\\_parent_consents\\";
    private static final int MAX_X_LABEL_LINE_LENGTH = 45;
    boolean LIMITED = true;
    int LIMIT_SIZE = 7;
    private static final double THIRD = 0.3333333333333333;

    private RestTemplate restTemplate;
    private ObjectMapper mapper;

    public DataProcessingService() {
        if (restTemplate == null) {
            restTemplate = new RestTemplate();
        }
        if (mapper == null) {
            mapper = new ObjectMapper();
        }
    }

    /**
     * We then process the results by sorting and if the number of categories is greater than the limit,
     * we only return the top limit and an "Other" category. We create a LinkedHashMap with the processed results
     * and ascertain the title from the HPDS Path.
     *
     * @return List<CategoricalData> - result of query
     */
    public List<CategoricalData> getCategoricalData(Map<String, Map<String, Integer>> crossCountsMap) {
        List<CategoricalData> categoricalDataList = new ArrayList<>();

        for (Map.Entry<String, Map<String, Integer>> entry : crossCountsMap.entrySet()) {
            if (skipKey(entry)) continue;
            Map<String, Integer> axisMap = processResults(entry.getValue());

            String title = getChartTitle(entry.getKey());
            categoricalDataList.add(new CategoricalData(
                    title,
                    new LinkedHashMap<>(axisMap),
                    createXAxisLabel(title),
                    "Number of Participants"
            ));
        }
        logger.debug("Finished Categorical Data with " + categoricalDataList.size() + " results");
        return categoricalDataList;
    }

    public List<CategoricalData> getCategoricalData(Map<String, Map<String, Integer>> crossCountsMap, Map<String, Map<String, Boolean>> obfuscationMap) {
        List<CategoricalData> categoricalDataList = new ArrayList<>();

        for (Map.Entry<String, Map<String, Integer>> entry : crossCountsMap.entrySet()) {
            if (skipKey(entry)) continue;
            Map<String, Integer> axisMap = processResults(entry.getValue());

            String title = getChartTitle(entry.getKey());
            categoricalDataList.add(new CategoricalData(
                    title,
                    new LinkedHashMap<>(axisMap),
                    obfuscationMap.get(entry.getKey()),
                    createXAxisLabel(title),
                    "Number of Participants"
            ));
        }
        logger.debug("Finished Categorical Data with " + categoricalDataList.size() + " results");
        return categoricalDataList;
    }

    private static boolean skipKey(Map.Entry<String, Map<String, Integer>> entry) {
        if (entry.getKey().equals(CONSENTS_KEY) ||
                entry.getKey().equals(HARMONIZED_CONSENT_KEY) ||
                entry.getKey().equals(TOPMED_CONSENTS_KEY) ||
                entry.getKey().equals(PARENT_CONSENTS_KEY)){
            return true;
        }
        return false;
    }


    /**
     * For each continuous cross count we create a histogram of the values.
     *
     * @return List<CategoricalData> - result of query
     */
    public List<ContinuousData> getContinuousData(Map<String, Map<String, Integer>> crossCountsMap) {
        List<ContinuousData> continuousDataList = new ArrayList<>();

        for (Map.Entry<String, Map<String, Integer>> entry : crossCountsMap.entrySet()) {
            String title = getChartTitle(entry.getKey());

            continuousDataList.add(new ContinuousData(
                    title,
                    new LinkedHashMap<>(
                            bucketData(entry.getValue())
                    ),
                    createXAxisLabel(title),
                    "Number of Participants"
            ));
        }
        logger.debug("Finished Categorical Data with " + continuousDataList.size() + " results");
        return continuousDataList;
    }

    /**
     * Calculates the number of bins for the continuous data using the keys of the count map.
     *
     * @param countMap - Map of the counts for the continuous data
     * @return int - number of bins
     */
    private static int calcNumBins(Map<Double, Integer> countMap) {
        double[] keys = countMap.keySet().stream().mapToDouble(Double::doubleValue).toArray();
        DescriptiveStatistics da = new DescriptiveStatistics(keys);
        double smallestKey = da.getMin();
        double largestKey = da.getMax();
        double binWidth = (3.5 * da.getStandardDeviation()) / Math.pow(countMap.size(),THIRD);
        return (int)Math.round((largestKey - smallestKey) / binWidth);
    }

    /**
     * Sorts the map and if there is more than the LIMIT_SIZE then we also get the greatest 7 categories and then combines
     * the others into an "other" category. Also replace long column names with shorter version.
     * @param axisMap - Map of the categories and their counts
     * @return Map<String, Integer> - sorted map of the categories and their counts with the "other" category added if necessary
     */
    private Map<String, Integer> processResults(Map<String, Integer> axisMap) {
        Map<String, Integer> finalAxisMap = axisMap;
        if (LIMITED && axisMap.size() > (LIMIT_SIZE+1) ) {
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
    private Map<String, Integer> limitKeySize(Map<String, Integer> axisMap) {
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

    /**
     * Creates the x axis label for the continuous data.
     *
     * @param filterKey - String - the title of the continuous data axis
     * @return String - the new x axis label for the continuous data
     */
    private String getChartTitle(String filterKey) {
        String[] titleParts = filterKey.split("\\\\");
        String title = filterKey;
        if (titleParts.length >= 2) {
            title = "Variable distribution of " + titleParts[titleParts.length - 2] + ": " + titleParts[titleParts.length - 1];
        }
        return title;
    }

    /**
     * Creates histogram bins for each continuous data and merges the counts that are in the same bin. If there are any
     * missing bis then it adds an empty bin.
     *
     * @param originalMap - Map of the original cross counts for the continuous data
     * @return Map<String, Integer> - the new bucketed cross counts for the continuous data
     */
    private static Map<String, Integer> bucketData(Map<String, Integer> originalMap) {
        // Convert to doubles from string then create a new map. This we need to use the keys to determine the
        // number of bins as well as the bin width.
        Double[] keysAsDoubles = originalMap.keySet().stream().map(Double::valueOf).toArray(Double[]::new);
        Map<Double, Integer> data = new LinkedHashMap<>();
        for (Double key : keysAsDoubles) {
            data.put(key, originalMap.get(key.toString()));
        }

        if (data.isEmpty()) return  new HashMap<>();

        int numBins = calcNumBins(data);
        double min = data.keySet().stream().min(Double::compareTo).orElse(0.0);
        double max = data.keySet().stream().max(Double::compareTo).orElse(0.0);

        if ((min == 0.0 && max == 0.0) || numBins == 0) return new HashMap<>();

        int binSize = (int)Math.ceil((max - min) / numBins);

        Map<Integer, Integer> results = createBinsAndMergeCounts(data, numBins, min, binSize);

        Map<Integer, List<Double>> ranges = new HashMap<>();
        List<Integer> keysToAdd = new ArrayList<>();
        Iterator<Map.Entry<Integer, Integer>> it = results.entrySet().iterator();

        // This while loop finds the range of each bin for createLabelsForBins as well as adds missing bins with 0 count
        double bucketMax = results.keySet().stream().max(Integer::compareTo).orElse(0);
        while (it.hasNext()) {
            // Where bucket.key is the bin index created in createBinsAndMergeCounts and value is the merged counts.
            Map.Entry<Integer, Integer> bucket = it.next();
            ranges.put(bucket.getKey(), new ArrayList<>());
            if (bucket.getKey() == 0) {
                ranges.get(bucket.getKey()).add(min);
            } else {
                ranges.get(bucket.getKey()).add(min + (bucket.getKey() * binSize));
            }
            ranges.get(bucket.getKey()).add(min + ((bucket.getKey() + 1) * binSize) - 1);

            //If not the last item in the map and the results map does not contain the key + 1 add a new key + 1 to the keysToAdd list
            if (bucket.getKey() != bucketMax && !results.containsKey(bucket.getKey() + 1)) {
                keysToAdd.add(bucket.getKey()+1);
                ranges.put(bucket.getKey() + 1, new ArrayList<>());
                ranges.get(bucket.getKey() + 1).add(min + ((bucket.getKey() + 1) * binSize));
                ranges.get(bucket.getKey() + 1).add(min + ((bucket.getKey() + 2) * binSize) - 1);
            }
        }
        Map<Integer, Integer> finalResults = results;
        keysToAdd.forEach(key -> finalResults.put(key, 0));

        //resort added keys
        if (!keysToAdd.isEmpty()) {
            results = finalResults.entrySet()
                    .stream()
                    .sorted(Map.Entry.comparingByKey())
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e2, LinkedHashMap::new));
        } else {
            results = finalResults;
        }

        //Finalizes the map by create labels that include the range of each bin.
        Map<String, Integer> finalMap = createLabelsForBins(results, ranges);
        return finalMap;
    }

    /**
     * Finds the bin location for each value in the data map and merges the counts for each bin.
     *
     * @param data - Map<Double, Integer> - the data to be binned
     * @param numBins - int - the number of bins to be created
     * @param min - double - the minimum value in the data
     * @param binSize - int - the size of each bin
     * @return - Map<Integer, Integer> - the new binned data
     */
    private static Map<Integer, Integer> createBinsAndMergeCounts(Map<Double, Integer> data, int numBins, double min, int binSize) {
        Map<Integer, Integer> results = new LinkedHashMap<>();
        for (Map.Entry<Double, Integer> entry : data.entrySet()) {
            int bin = (int) Math.floor(((entry.getKey() - min) / binSize));
            if (bin < numBins) {
                results.merge(bin, entry.getValue(), Integer::sum);
            } else {
                // When the key is exactly the max value
                results.merge(bin - 1, entry.getValue(), Integer::sum);
            }
        }
        return results;
    }

    /**
     * Iterate over the data and find its bin. If the bin is not in the results map, add it to the results map and
     * add the current entry's value to the bin's list. If the bin is in the results map, add the current entry's
     * value to the bin's list. Track the range of each bin in the ranges map. This is used to determine the bins
     * label.
     *
     * @param results - Map<Integer, Integer> - the binned data
     * @param ranges - Map<Integer, List<Double>> - the range of each bin
     * @return - Map<String, Integer> - the new binned data with labels for each bin
     */
    private static Map<String, Integer> createLabelsForBins(Map<Integer, Integer> results, Map<Integer, List<Double>> ranges) {
        Map<String, Integer> finalMap = new LinkedHashMap<>();
        String label = "";
        for (Map.Entry<Integer, Integer> bucket : results.entrySet()) {
            double minForLabel = ranges.get(bucket.getKey()).stream().min(Double::compareTo).orElse(0.0);
            double maxForLabel = ranges.get(bucket.getKey()).stream().max(Double::compareTo).orElse(0.0);
            if (minForLabel == maxForLabel) {
                label = String.format("%.1f", minForLabel);
            } else {
                label = String.format("%.1f", minForLabel) + " - " + String.format("%.1f", maxForLabel);
            }
            finalMap.put(label, bucket.getValue());
        }
        Integer lastCount = finalMap.get(label);
        //Last label should be the min in the range with a '+' sign.
        if (lastCount != null) {
            String newLabel = label;
            int hasDash = label.indexOf(" -");
            if (hasDash > 0) {
                newLabel = label.substring(0, hasDash);
            }
            finalMap.remove(label);
            finalMap.put(newLabel + " +", lastCount);
        }
        return finalMap;
    }

    /**
     * Creates a label for the x axis using the title of the chart.
     * @param title - String - the title of the chart
     * @return - String - the label for the x axis (usually the variable name)
     */
    private String createXAxisLabel(String title) {
        try {
            return title.substring(title.lastIndexOf(" ") + 1);
        } catch (IndexOutOfBoundsException e) {
            logger.error("Error getting cross counts: " + e.getMessage());
            e.printStackTrace();
            return title;
        }
    }
}
