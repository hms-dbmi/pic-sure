package edu.harvard.hms.dbmi.avillach.hpds.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.hpds.model.CategoricalData;
import edu.harvard.hms.dbmi.avillach.hpds.model.ContinuousData;
import edu.harvard.hms.dbmi.avillach.hpds.model.domain.*;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
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

    private static final String CONSENTS_KEY = "\\_consents\\";
    private static final String HARMONIZED_CONSENTS_KEY = "\\_harmonized_consents\\";
    private static final String TOPMED_CONSENTS_KEY = "\\_topmed_consents\\";
    private static final String AUTH_HEADER_NAME = "Authorization";

    private RestTemplate restTemplate;

    public DataService() {
        if (restTemplate == null) {
            restTemplate = new RestTemplate();
        }
    }

    @Override
    public List<CategoricalData> getCategoricalData(QueryRequest queryRequest) {
        logger.debug("Starting Categorical Data");
        List<CategoricalData> categoricalDataList = new ArrayList<>();
        Map<String, Double> axisMap = new HashMap<>();
        HttpHeaders headers = new HttpHeaders();
        String token = queryRequest.getResourceCredentials().get(AUTH_HEADER_NAME);
        headers.add(AUTH_HEADER_NAME, token);
        String[] _consents = queryRequest.getQuery().categoryFilters.get(CONSENTS_KEY);
        String[] _harmonized_consents = queryRequest.getQuery().categoryFilters.get(HARMONIZED_CONSENTS_KEY);
        String[] _topmed_consents = queryRequest.getQuery().categoryFilters.get(TOPMED_CONSENTS_KEY);
        for (String filter: queryRequest.getQuery().requiredFields) {
            String body = "{\"query\": \"" + filter.replace("\\", "\\\\") + "\"}";
            ObjectMapper mapper = new ObjectMapper();
            JsonNode actualObj = null;
            try {
                actualObj = mapper.readTree(body);
            } catch (JsonProcessingException e) {
                e.printStackTrace();
            }
            logger.info("Calling /picsure/search/ for required field:  \n" + actualObj.toString());
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
                if (_harmonized_consents != null && _harmonized_consents.length > 0) {
                    newRequest.getQuery().categoryFilters.put(HARMONIZED_CONSENTS_KEY, _harmonized_consents);
                }
                if (_topmed_consents != null && _topmed_consents.length > 0) {
                    newRequest.getQuery().categoryFilters.put(TOPMED_CONSENTS_KEY, _topmed_consents);
                }
                newRequest.getQuery().categoryFilters.put(filter.getKey(), new String[]{value});
                newRequest.getQuery().expectedResultType = ResultType.COUNT;
                logger.info("Calling /picsure/query/sync for categoryFilters field with query:  \n" + newRequest.getQuery().toString());
                Double result = restTemplate.exchange(picSureUrl, HttpMethod.POST, new HttpEntity<>(newRequest, headers), Double.class).getBody();
                axisMap.put(value, result);
            }
            categoricalDataList.add(new CategoricalData(filter.getKey(), axisMap));
        }
        logger.debug("Finished Categorical Data with " + categoricalDataList.size() + " results");
        return categoricalDataList;
    }

    public List<ContinuousData> getContinuousData(QueryRequest queryRequest) {
        logger.debug("Starting Continuous Data");
        List<ContinuousData> continuousDataList = new ArrayList<>();
        HttpHeaders headers = new HttpHeaders();
        String token = queryRequest.getResourceCredentials().get(AUTH_HEADER_NAME);
        headers.add(AUTH_HEADER_NAME, token);
        for (Map.Entry<String, Filter.DoubleFilter> filter: queryRequest.getQuery().numericFilters.entrySet()) {
            QueryRequest newRequest = new QueryRequest(queryRequest);
            String[] _consents = newRequest.getQuery().categoryFilters.get(CONSENTS_KEY);
            String[] _harmonized_consents = queryRequest.getQuery().categoryFilters.get(HARMONIZED_CONSENTS_KEY);
            String[] _topmed_consents = queryRequest.getQuery().categoryFilters.get(TOPMED_CONSENTS_KEY);
            newRequest.getQuery().categoryFilters.clear();
            newRequest.getQuery().categoryFilters.put(CONSENTS_KEY, _consents);
            if (_harmonized_consents != null && _harmonized_consents.length > 0) {
                newRequest.getQuery().categoryFilters.put(HARMONIZED_CONSENTS_KEY, _harmonized_consents);
            }
            if (_topmed_consents != null && _topmed_consents.length > 0) {
                newRequest.getQuery().categoryFilters.put(TOPMED_CONSENTS_KEY, _topmed_consents);
            }
            newRequest.getQuery().numericFilters.replace(filter.getKey(), filter.getValue());
            logger.info("Calling /picsure/query/sync for numericFilters field with query:  \n" + newRequest.getQuery().toString());
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
        logger.debug("Finished Continuous Data with " + continuousDataList.size() + " results");
        return continuousDataList;
    }
}
