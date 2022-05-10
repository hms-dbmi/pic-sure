package edu.harvard.hms.dbmi.avillach.hpds.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.hpds.model.CategoricalData;
import edu.harvard.hms.dbmi.avillach.hpds.model.ContinuousData;
import edu.harvard.hms.dbmi.avillach.hpds.model.domain.*;
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

        HttpHeaders headers = prepareQueryRequest(queryRequest);

        queryRequest.getQuery().requiredFields.parallelStream().forEach(filter -> {
            processRequiredFilters(queryRequest, filter, headers);
        });

        for (Map.Entry<String, String[]> filter : queryRequest.getQuery().categoryFilters.entrySet()) {

            if (filter.getKey().equals(CONSENTS_KEY) ||
                    filter.getKey().equals(HARMONIZED_CONSENT_KEY) ||
                    filter.getKey().equals(TOPMED_CONSENTS_KEY) ||
                    filter.getKey().equals(PARENT_CONSENTS_KEY)){
                continue;
            }
            String[] resultLines;
            try {
                resultLines = restTemplate.exchange(picSureUrl, HttpMethod.POST, new HttpEntity<>(queryRequest, headers), String.class).getBody().split("\n");
            } catch (Exception e) {
                e.printStackTrace();
                resultLines = new String[0];
            }

            Map<String, Double> axisMap = buildAxisMap(resultLines, filter.getKey());

            if (LIMITED == true && axisMap.size() > LIMIT_SIZE) {
                axisMap = createOtherBar(axisMap);
            }

            String title = getChartTitle(filter.getKey());

            categoricalDataList.add(new CategoricalData(
                    title,
                    new LinkedHashMap<>(axisMap),
                    createXAxisLabel(title),
                    "Number of Participants"
            ));
            logger.debug("Finished Categorical Data with " + categoricalDataList.size() + " results");
        }
        return categoricalDataList;
    }

    public List<ContinuousData> getContinuousData(QueryRequest queryRequest) {
        List<ContinuousData> continuousDataList = new ArrayList<>();

        HttpHeaders headers = prepareQueryRequest(queryRequest);

        String[] resultLines;
        try {
            resultLines = restTemplate.exchange(picSureUrl, HttpMethod.POST, new HttpEntity<>(queryRequest, headers), String.class).getBody().split("\n");
        } catch (Exception e) {
            e.printStackTrace();
            resultLines = new String[0];
        }

        for (Map.Entry<String, Filter.DoubleFilter> filter: queryRequest.getQuery().numericFilters.entrySet()) {

            TreeMap<Double, Integer> countMap = buildCountMap(resultLines, filter.getKey());

            String title = getChartTitle(filter.getKey());

            continuousDataList.add(new ContinuousData(
                    title,
                    new TreeMap<>(countMap),
                    createXAxisLabel(title),
                    "Frequency"));
            countMap.clear();
        }
        logger.debug("Finished Categorical Data with " + continuousDataList.size() + " results");
        return continuousDataList;
    }

    private void processRequiredFilters(QueryRequest queryRequest, String filter, HttpHeaders headers) {
        String body = "{\"query\": \"" + filter.replace("\\", "\\\\") + "\"}";
        JsonNode actualObj = null;

        try {
            actualObj = mapper.readTree(body);
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
        try {
            SearchResults searchResults = restTemplate.exchange(searchUrl, HttpMethod.POST, new HttpEntity<>(actualObj, headers), SearchResults.class).getBody();
            for (Map.Entry<String, SearchResult> phenotype : searchResults.getResults().getPhenotypes().entrySet()) {
                queryRequest.getQuery().categoryFilters.put(phenotype.getKey(), phenotype.getValue().getCategoryValues());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private HttpHeaders prepareQueryRequest(QueryRequest queryRequest) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(AUTH_HEADER_NAME,
                queryRequest.getResourceCredentials().get(AUTH_HEADER_NAME)
        );
        queryRequest.getQuery().expectedResultType = ResultType.DATAFRAME;
        queryRequest.setResourceUUID(picSureUuid);
        return headers;
    }

    private Map<String, Double> buildAxisMap(String[] resultLines, String filterKey) {
        Map<String, Double> axisMap = new LinkedHashMap<>();

        if (resultLines != null && resultLines.length > 0) {
            List<String> headerLine = Arrays.asList(resultLines[0].split(","));
            for (int i = 1; i < resultLines.length; i++) {
                String[] splitLines = resultLines[i].split(",");
                String key = (splitLines[
                        headerLine.indexOf(filterKey)
                        ]);

                if (key.length() > MAX_X_LABEL_LINE_LENGTH) {
                    key = key.substring(0, MAX_X_LABEL_LINE_LENGTH - 3) + "...";
                }

                if (axisMap.containsKey(key)) {
                    axisMap.put(key, axisMap.get(key) + 1);
                } else {
                    axisMap.put(key, 1.0);
                }
            }
        }

        return axisMap;
    }

    private TreeMap<Double, Integer> buildCountMap(String[] resultLines, String filterKey) {
        TreeMap<Double, Integer> countMap = new TreeMap<>();
        if (resultLines != null && resultLines.length > 0) {
            List<String> headerLine = Arrays.asList(resultLines[0].split(","));
            for (int i = 1; i < resultLines.length; i++) {
                String[] splitLines = resultLines[i].split(",");
                Double key = Double.parseDouble(splitLines[
                        headerLine.indexOf(filterKey)
                        ]);
                if (countMap.containsKey(key)) {
                    countMap.put(key, countMap.get(key) + 1);
                } else {
                    countMap.put(key, 1);
                }
            }
        }
        return countMap;
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

    private String createXAxisLabel(String title) {
        return title.substring(title.lastIndexOf(" "));
    }
}
