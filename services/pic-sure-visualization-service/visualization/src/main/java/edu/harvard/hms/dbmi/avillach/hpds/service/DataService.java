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
    private static final double NEGATIVE_THIRD = -0.3333333333333333;

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
    }

    @Override
    public List<CategoricalData> getCategoricalData(QueryRequest queryRequest) {
        List<CategoricalData> categoricalDataList = new ArrayList<>();

        Map<String, Map<String, Double>> crossCountsMap = new LinkedHashMap<>();
        HttpHeaders headers = prepareQueryRequest(queryRequest, ResultType.CATEGORICAL_CROSS_COUNT);
        try {
            crossCountsMap = restTemplate.exchange(picSureUrl, HttpMethod.POST, new HttpEntity<>(queryRequest, headers), LinkedHashMap.class).getBody();
        } catch (Exception e) {
            e.printStackTrace();
        }

        for (Map.Entry<String, Map<String, Double>> entry : crossCountsMap.entrySet()) {
            if (entry.getKey().equals(CONSENTS_KEY) ||
                    entry.getKey().equals(HARMONIZED_CONSENT_KEY) ||
                    entry.getKey().equals(TOPMED_CONSENTS_KEY) ||
                    entry.getKey().equals(PARENT_CONSENTS_KEY)){
                continue;
            }
            Map<String, Double> axisMap = LIMITED ? createOtherBar(entry.getValue()) : entry.getValue();
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

    public List<ContinuousData> getContinuousData(QueryRequest queryRequest) {
        List<ContinuousData> continuousDataList = new ArrayList<>();

        HttpHeaders headers = prepareQueryRequest(queryRequest, ResultType.CONTINUOUS_CROSS_COUNT);
        Map<String, Map<Double, Integer>> crossCountsMap = new LinkedHashMap<>();
        try {
            crossCountsMap = restTemplate.exchange(picSureUrl, HttpMethod.POST, new HttpEntity<>(queryRequest, headers), LinkedHashMap.class).getBody();
        } catch (Exception e) {
            e.printStackTrace();
        }

        for (Map.Entry<String, Map<Double, Integer>> entry : crossCountsMap.entrySet()) {
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

    private static double calcBinWidth(Map<Double, Integer> countMap) {
        double[] keys = countMap.keySet().stream().mapToDouble(Double::doubleValue).toArray();
        DescriptiveStatistics da = new DescriptiveStatistics(keys);
        double iqr = da.getPercentile(75) - da.getPercentile(25);
        double countToNegThird = Math.pow(countMap.size(), NEGATIVE_THIRD);
        return 2 * iqr * countToNegThird;
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

    private Map<String, Double> createOtherBar(Map<String, Double> axisMap) {
        Map<String, Double> finalAxisMap = axisMap;
        Supplier<Stream<Map.Entry<String, Double>>> stream = () -> finalAxisMap.entrySet().stream().sorted(Collections.reverseOrder(Map.Entry.comparingByValue()));
        Double otherSum = stream.get().skip(LIMIT_SIZE).mapToDouble(Map.Entry::getValue).sum();
        axisMap = stream.get().limit(LIMIT_SIZE).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e2, LinkedHashMap::new));
        axisMap.put("Other", otherSum);
        return axisMap;
    }

    private String getChartTitle(String filterKey) {
        String[] titleParts = filterKey.split("\\\\");
        String title = filterKey;

        if (titleParts.length > 4) {
            title = "Variable distribution of " + titleParts[3] + ": " + titleParts[4];
        } else if (titleParts.length > 3) {
            title = "Variable distribution of " + titleParts[2] + ": " + titleParts[3];
        }
        return title;
    }

    private static Map<String, Integer> bucketData(Map<Double, Integer> data) {
        double binWidth = calcBinWidth(data);
        double min = data.keySet().stream().min(Double::compareTo).get();
        double max = data.keySet().stream().max(Double::compareTo).get();
        int maxSize = (int) Math.ceil((max-min)/binWidth);
        Map<String, Integer> results = new LinkedHashMap<>();
        List<String> currentBucketLabels = new ArrayList<>();
        int currentSize = 0;
        int currentBucketCount = 0;
        for (Map.Entry<Double, Integer> entry : data.entrySet()) {
            if (currentSize < maxSize) {
                currentBucketCount += entry.getValue();
                currentBucketLabels.add(String.format("%.1f", entry.getKey()));
                currentSize++;
            } else {
                String key = currentBucketLabels.get(0) + " - " + currentBucketLabels.get(currentBucketLabels.size() - 1);
                results.put(key, currentBucketCount);
                currentBucketCount = 0;
                currentSize = 0;
                currentBucketLabels.clear();
                currentBucketCount += entry.getValue();
                currentBucketLabels.add(String.format("%.1f", entry.getKey()));
                currentSize++;
            }
        }
        if (currentSize > 0) {
            String key = currentBucketLabels.get(0) + "+";
            results.put(key, currentBucketCount);
        }
        return results;
    }

    private String createXAxisLabel(String title) {
        return title.substring(title.lastIndexOf(" "));
    }
}
