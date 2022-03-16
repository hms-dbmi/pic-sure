package edu.harvard.hms.dbmi.avillach.hpds.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.hpds.model.CategoricalData;
import edu.harvard.hms.dbmi.avillach.hpds.model.ContinuousData;
import edu.harvard.hms.dbmi.avillach.hpds.model.domain.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
public class DataService implements IDataService {

    @Value("${picSure.url}")
    String picSureUrl;

    @Value("${search.url}")
    String searchUrl;

    private static final String CONSENTS_KEY = "\\_consents\\";
    private static final String AUTH_HEADER_NAME = "Authorization";

    @Override
    public List<CategoricalData> getCategoricalData(QueryRequest queryRequest) {
        System.out.println("queryRequest: " + queryRequest);
        List<CategoricalData> categoricalDataList = new ArrayList<>();
        Map<String, Double> axisMap = new HashMap<>();
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        String token = queryRequest.getResourceCredentials().get(AUTH_HEADER_NAME);
        headers.add(AUTH_HEADER_NAME, token);
        String[] _consents = queryRequest.getQuery().categoryFilters.get(CONSENTS_KEY);
        for (String filter: queryRequest.getQuery().requiredFields) {
            String body = "{\"query\": \"" + filter.replace("\\", "\\\\") + "\"}";
            ObjectMapper mapper = new ObjectMapper();
            JsonNode actualObj = null;
            try {
                actualObj = mapper.readTree(body);
            } catch (JsonProcessingException e) {
                e.printStackTrace();
            }
            SearchResults searchResults = restTemplate.exchange(searchUrl, HttpMethod.POST, new HttpEntity<>(actualObj, headers), SearchResults.class).getBody();
            for (Map.Entry<String, SearchResult> phenotype:searchResults.getResults().getPhenotypes().entrySet()) {
                queryRequest.getQuery().categoryFilters.put(phenotype.getKey(), phenotype.getValue().getCategoryValues());
            }
        }
        for (Map.Entry<String, String[]> filter: queryRequest.getQuery().categoryFilters.entrySet()) {
            if (filter.getKey().equals(CONSENTS_KEY)) {
                continue;
            }
            for (String value: filter.getValue()) {
                QueryRequest newRequest = new QueryRequest(queryRequest);
                newRequest.getQuery().categoryFilters.clear();
                newRequest.getQuery().categoryFilters.put(CONSENTS_KEY, _consents);
                newRequest.getQuery().categoryFilters.put(filter.getKey(), new String[]{value});
                newRequest.getQuery().expectedResultType = ResultType.COUNT;
                Double result = restTemplate.exchange(picSureUrl, HttpMethod.POST, new HttpEntity<>(newRequest, headers), Double.class).getBody();
                axisMap.put(value, result);
            }
            categoricalDataList.add(new CategoricalData(filter.getKey(), axisMap));
        }


        return categoricalDataList;
    }

    public List<ContinuousData> getContinuousData(QueryRequest queryRequest) {
        List<ContinuousData> continuousDataList = new ArrayList<>();
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        String token = queryRequest.getResourceCredentials().get(AUTH_HEADER_NAME);
        headers.add(AUTH_HEADER_NAME, token);
        for (Map.Entry<String, Filter.DoubleFilter> filter: queryRequest.getQuery().numericFilters.entrySet()) {
            QueryRequest newRequest = new QueryRequest(queryRequest);
            String[] _consents = newRequest.getQuery().categoryFilters.get(CONSENTS_KEY);
            newRequest.getQuery().categoryFilters.clear();
            newRequest.getQuery().categoryFilters.put(CONSENTS_KEY, _consents);
            newRequest.getQuery().numericFilters.replace(filter.getKey(), filter.getValue());
            String rawResult = restTemplate.exchange(picSureUrl, HttpMethod.POST, new HttpEntity<>(newRequest, headers), String.class).getBody();
            String[] result = rawResult != null ? rawResult.split("\n") : null;
            // Ignore the first line
            Map<Double, Integer> countMap = new HashMap<>();
            if (result != null) {
                for (int i = 1; i < result.length; i++) {
                    String[] split = result[i].split(",");
                    Double key = Double.parseDouble(split[split.length - 1]);
                    if (countMap.containsKey(key)) {
                        countMap.put(key, countMap.get(key) + 1);
                    } else {
                        countMap.put(key, 1);
                    }
                }
            }
            continuousDataList.add(new ContinuousData(filter.getKey(), countMap));
        }
        return continuousDataList;
    }
}
