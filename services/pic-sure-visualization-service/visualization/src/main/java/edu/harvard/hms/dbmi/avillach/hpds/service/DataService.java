package edu.harvard.hms.dbmi.avillach.hpds.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.hpds.model.CategoricalData;
import edu.harvard.hms.dbmi.avillach.hpds.model.ContinuousData;
import edu.harvard.hms.dbmi.avillach.hpds.model.domain.*;
import org.knowm.xchart.Histogram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

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

    private RestTemplate restTemplate;

    public DataService() {
        if (restTemplate == null) {
            restTemplate = new RestTemplate();
        }
    }

    @Override
    public List<CategoricalData> getCategoricalData(QueryRequest queryRequest) {
        List<CategoricalData> categoricalDataList = new ArrayList<>();
        ObjectMapper mapper = new ObjectMapper();
        HttpHeaders headers = new HttpHeaders();
        headers.add(AUTH_HEADER_NAME,
                queryRequest.getResourceCredentials().get(AUTH_HEADER_NAME)
        );
        queryRequest.getQuery().expectedResultType = ResultType.DATAFRAME;

        for (String filter : queryRequest.getQuery().requiredFields) {
            String body = "{\"query\": \"" + filter.replace("\\", "\\\\") + "\"}";
            JsonNode actualObj = null;

            try {
                actualObj = mapper.readTree(body);
            } catch (JsonProcessingException e) {
                e.printStackTrace();
            }

            SearchResults searchResults = restTemplate.exchange(searchUrl, HttpMethod.POST, new HttpEntity<>(actualObj, headers), SearchResults.class).getBody();
            for (Map.Entry<String, SearchResult> phenotype : searchResults.getResults().getPhenotypes().entrySet()) {
                queryRequest.getQuery().categoryFilters.put(phenotype.getKey(), phenotype.getValue().getCategoryValues());
            }
        }
        Map<String, Double> axisMap = Collections.synchronizedMap(new HashMap<>());
        for (Map.Entry<String, String[]> filter : queryRequest.getQuery().categoryFilters.entrySet()) {

            if (filter.getKey().equals(CONSENTS_KEY) ||
                    filter.getKey().equals(HARMONIZED_CONSENT_KEY) ||
                    filter.getKey().equals(TOPMED_CONSENTS_KEY) ||
                    filter.getKey().equals(PARENT_CONSENTS_KEY)) {
                continue;
            }

            queryRequest.getQuery().expectedResultType = ResultType.DATAFRAME;
            queryRequest.setResourceUUID(picSureUuid);

            String rawResult = restTemplate.exchange(picSureUrl, HttpMethod.POST, new HttpEntity<>(queryRequest, headers), String.class).getBody();
            String[] result = rawResult != null ? rawResult.split("\n") : null;

            if (result != null && result.length > 0) {
                String[] headerLine = result[0].split(",");
                for (int i = 1; i < result.length; i++) {
                    String[] split = result[i].split(",");
                    String key = (split[
                            Arrays.asList(headerLine).indexOf(filter.getKey())
                            ]);
                    if (axisMap.containsKey(key)) {
                        axisMap.put(key, axisMap.get(key) + 1);
                    } else {
                        axisMap.put(key, 1.0);
                    }
                }
            }
//            if (filter.getKey().length() > 70) {
//                String holder = filter.getKey().substring(0, 70);
//                int target = holder.lastIndexOf(" ");
//                tempValue = filter.getKey().substring(0, target) + "\n" + filter.getKey().substring(target+1, filter.getKey().length()-1);
//            }
            String[] titleParts = filter.getKey().split("\\\\");
            String title = filter.getKey();
            if (title.length() > 4) {
                title = "Variable distribution of " + titleParts[3] + ": " + titleParts[4];
            }
            categoricalDataList.add(new CategoricalData(title, new HashMap<>(axisMap)));
            axisMap.clear();
            logger.debug("Finished Categorical Data with " + categoricalDataList.size() + " results");
        }
        return categoricalDataList;
    }

    public List<ContinuousData> getContinuousData(QueryRequest queryRequest) {
        List<ContinuousData> continuousDataList = new ArrayList<>();
        TreeMap<Double, Integer> countMap = new TreeMap<>();
        HttpHeaders headers = new HttpHeaders();

        headers.add(AUTH_HEADER_NAME,
                queryRequest.getResourceCredentials().get(AUTH_HEADER_NAME)
        );
        queryRequest.setResourceUUID(picSureUuid);
        queryRequest.getQuery().expectedResultType = ResultType.DATAFRAME;

        String rawResult = restTemplate.exchange(picSureUrl, HttpMethod.POST, new HttpEntity<>(queryRequest, headers), String.class).getBody();

        String[] result = rawResult != null ? rawResult.split("\n") : null;

        for (Map.Entry<String, Filter.DoubleFilter> filter: queryRequest.getQuery().numericFilters.entrySet()) {
            if (result != null && result.length > 0) {
                String[] headerLine = result[0].split(",");
                for (int i = 1; i < result.length; i++) {
                    String[] split = result[i].split(",");
                    Double key = Double.parseDouble(split[
                                Arrays.asList(headerLine).indexOf(filter.getKey())
                            ]);
                    if (countMap.containsKey(key)) {
                        countMap.put(key, countMap.get(key) + 1);
                    } else {
                        countMap.put(key, 1);
                    }
                }
            }

            String[] titleParts = filter.getKey().split("\\\\");
            String xAxisLabel = filter.getKey();
            String title = filter.getKey();

            if (title.length() > 4) {
                title = "Variable distribution of " + titleParts[3] + ": " + titleParts[4];
                xAxisLabel = titleParts[4];
            }

            continuousDataList.add(new ContinuousData(title, new TreeMap<>(countMap), xAxisLabel, "Frequency"));
            countMap.clear();
        }

        return continuousDataList;
    }
}
