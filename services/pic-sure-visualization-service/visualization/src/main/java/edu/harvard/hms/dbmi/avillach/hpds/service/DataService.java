package edu.harvard.hms.dbmi.avillach.hpds.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.hpds.model.CategoricalData;
import edu.harvard.hms.dbmi.avillach.hpds.model.ContinuousData;
import edu.harvard.hms.dbmi.avillach.hpds.model.domain.*;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class DataService implements IDataService {

    private Logger logger = LoggerFactory.getLogger(DataService.class);

    @Value("${picSure.url}")
    private String picSureUrl;
    @Value("${search.url}")
    private String searchUrl;
    @Value("${picSure.uuid}")
    private UUID picSureUuid;

    private static final String CONSENTS_KEY = "\\_consents\\";
    private static final String HARMONIZED_CONSENT_KEY = "\\_harmonized_consent\\";
    private static final String TOPMED_CONSENTS_KEY = "\\_topmed_consents\\";
    private static final String PARENT_CONSENTS_KEY = "\\_parent_consents\\";
    private static final String AUTH_HEADER_NAME = "Authorization";
    private static final int MAX_X_LABEL_LINE_LENGTH = 45;
    boolean LIMITED = true;
    int LIMIT_SIZE = 7;
    private static final double THIRD = 0.3333333333333333;

    private RestTemplate restTemplate;
    private ObjectMapper mapper;

    public DataService() {
        if (restTemplate == null) {
            restTemplate = new RestTemplate();
        }
        if (mapper == null) {
            mapper = new ObjectMapper();
        }
    }

    @Override
    public List<CategoricalData> getCategoricalData(QueryRequest queryRequest) {
        List<CategoricalData> categoricalDataList = new ArrayList<>();

        Map<String, Map<String, Integer>> crossCountsMap = new LinkedHashMap<>();
        HttpHeaders headers = prepareQueryRequest(queryRequest, ResultType.CATEGORICAL_CROSS_COUNT);
        try {
            crossCountsMap = restTemplate.exchange(picSureUrl, HttpMethod.POST, new HttpEntity<>(queryRequest, headers), LinkedHashMap.class).getBody();
        } catch (Exception e) {
            logger.error("Error getting cross counts: " + e.getMessage());
            e.printStackTrace();
        }
        if (crossCountsMap != null) {
            for (Map.Entry<String, Map<String, Integer>> entry : crossCountsMap.entrySet()) {
                if (entry.getKey().equals(CONSENTS_KEY) ||
                        entry.getKey().equals(HARMONIZED_CONSENT_KEY) ||
                        entry.getKey().equals(TOPMED_CONSENTS_KEY) ||
                        entry.getKey().equals(PARENT_CONSENTS_KEY)){
                    continue;
                }
                Map<String, Integer> axisMap =  processResults(entry.getValue());
                //Replace long column names with shorter version
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

                String title = getChartTitle(entry.getKey());
                categoricalDataList.add(new CategoricalData(
                        title,
                        new LinkedHashMap<>(axisMap),
                        createXAxisLabel(title),
                        "Number of Participants"
                ));
            }
        }
        logger.debug("Finished Categorical Data with " + categoricalDataList.size() + " results");
        return categoricalDataList;
    }

    public List<ContinuousData> getContinuousData(QueryRequest queryRequest) {
        List<ContinuousData> continuousDataList = new ArrayList<>();

        HttpHeaders headers = prepareQueryRequest(queryRequest, ResultType.CONTINUOUS_CROSS_COUNT);
        Map<String, Map<String, Integer>> crossCountsMap = new LinkedHashMap<>();
        try {
            crossCountsMap = restTemplate.exchange(picSureUrl, HttpMethod.POST, new HttpEntity<>(queryRequest, headers), LinkedHashMap.class).getBody();
        } catch (Exception e) {
            logger.error("Error getting cross counts: " + e.getMessage());
            e.printStackTrace();
        }

        if (crossCountsMap != null) {
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
        }
        logger.debug("Finished Categorical Data with " + continuousDataList.size() + " results");
        return continuousDataList;
    }

    private static int calcNumBins(Map<Double, Integer> countMap) {
        double[] keys = countMap.keySet().stream().mapToDouble(Double::doubleValue).toArray();
        DescriptiveStatistics da = new DescriptiveStatistics(keys);
        double smallestKey = da.getMin();
        double largestKey = da.getMax();
        double binWidth = (3.5 * da.getStandardDeviation()) / Math.pow(countMap.size(),THIRD);
        return (int) Math.round((largestKey - smallestKey) / binWidth);
    }

    private HttpHeaders prepareQueryRequest(QueryRequest queryRequest, ResultType requestType) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(AUTH_HEADER_NAME,
                queryRequest.getResourceCredentials().get(AUTH_HEADER_NAME)
        );
        queryRequest.getQuery().expectedResultType = requestType;
        queryRequest.setResourceUUID(picSureUuid);
        return headers;
    }

    // Sorts the map and if there is more than the LIMIT_SIZE then we also get the greatest 7 categories and then combine
    // the others into an "other" category.
    private Map<String, Integer> processResults(Map<String, Integer> axisMap) {
        Map<String, Integer> finalAxisMap = axisMap;
        Supplier<Stream<Map.Entry<String, Integer>>> stream = () -> finalAxisMap.entrySet().stream().sorted(Collections.reverseOrder(Map.Entry.comparingByValue()));
        if (LIMITED && axisMap.size()>LIMIT_SIZE) {
            Integer otherSum = stream.get().skip(LIMIT_SIZE).mapToInt(Map.Entry::getValue).sum();
            axisMap = stream.get().limit(LIMIT_SIZE).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e2, LinkedHashMap::new));
            axisMap.put("Other", otherSum);
        } else {
            axisMap = stream.get().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e2, LinkedHashMap::new));
        }
        return axisMap;
    }

    private String getChartTitle(String filterKey) {
        String[] titleParts = filterKey.split("\\\\");
        String title = filterKey;
        if (titleParts.length >= 2) {
            title = "Variable distribution of " + titleParts[titleParts.length - 2] + ": " + titleParts[titleParts.length - 1];
        }
        return title;
    }

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

        double binSize = (max - min) / (double)numBins;

        // Iterate over the data and find its bin. If the bin is not in the results map, add it to the results map and
        // add the current entry's value to the bin's list. If the bin is in the results map, add the current entry's
        // value to the bin's list. Track the range of each bin in the ranges map. This is used to determine the bins
        // label.
        Map<Integer, Integer> results = new LinkedHashMap<>();
        Map<Integer, List<Double>> ranges = new HashMap<>();
        for (Map.Entry<Double, Integer> entry : data.entrySet()) {
            int bin = (int) ((entry.getKey() - min) / binSize);
            if (bin < numBins) {
                results.merge(bin, entry.getValue(), Integer::sum);
                if (ranges.containsKey(bin)) {
                    ranges.get(bin).add(entry.getKey());
                } else {
                    ranges.put(bin, new ArrayList<>());
                    ranges.get(bin).add(entry.getKey());
                }
                ranges.get(bin).add(entry.getKey());
            } else {
                results.merge(bin - 1, entry.getValue(), Integer::sum);
                if (ranges.containsKey(bin-1)) {
                    ranges.get(bin-1).add(entry.getKey());
                } else {
                    ranges.put(bin-1, new ArrayList<>());
                    ranges.get(bin-1).add(entry.getKey());
                }
                ranges.get(bin - 1).add(entry.getKey());
            }
        }

        //Finalizes the map by create labels that include the range of each bin.
        Map<String, Integer> finalMap = createLabelsForBins(results, ranges);
        return finalMap;
    }

    private static Map<String, Integer> createLabelsForBins(Map<Integer, Integer> results, Map<Integer, List<Double>> ranges) {
        Map<String, Integer> finalMap = new LinkedHashMap<>();
        String label = "";
        for (Map.Entry<Integer, Integer> entry : results.entrySet()) {
            double minForLabel = ranges.get(entry.getKey()).stream().min(Double::compareTo).orElse(0.0);
            double maxForLabel = ranges.get(entry.getKey()).stream().max(Double::compareTo).orElse(0.0);
            label = String.format("%.1f", minForLabel) + " - " + String.format("%.1f", maxForLabel);
            finalMap.put(label, entry.getValue());
        }
        Integer lastCount = finalMap.get(label);
        //Last label should be the min in the range with a '+' sign.
        if (lastCount != null) {
            String newLabel = label.substring(0, label.indexOf(" -"));
            finalMap.remove(label);
            finalMap.put(newLabel + " +", lastCount);
        }
        return finalMap;
    }

    private String createXAxisLabel(String title) {
        try {
            return title.substring(title.lastIndexOf(" "));
        } catch (IndexOutOfBoundsException e) {
            logger.error("Error getting cross counts: " + e.getMessage());
            e.printStackTrace();
            return title;
        }
    }
}
