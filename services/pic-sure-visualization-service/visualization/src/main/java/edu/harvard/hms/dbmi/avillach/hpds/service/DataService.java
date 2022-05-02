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
        HttpHeaders headers = new HttpHeaders();
        headers.add(AUTH_HEADER_NAME, queryRequest.getResourceCredentials().get(AUTH_HEADER_NAME));
        String[] _consents = queryRequest.getQuery().categoryFilters.get(CONSENTS_KEY);
        String[] _harmonized_consents = queryRequest.getQuery().categoryFilters.get(HARMONIZED_CONSENT_KEY);
        String[] _topmed_consents = queryRequest.getQuery().categoryFilters.get(TOPMED_CONSENTS_KEY);
        String[] _parent_consents = queryRequest.getQuery().categoryFilters.get(PARENT_CONSENTS_KEY);
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
            if (filter.getKey().equals(CONSENTS_KEY) ||
                    filter.getKey().equals(HARMONIZED_CONSENT_KEY) ||
                    filter.getKey().equals(TOPMED_CONSENTS_KEY) ||
                    filter.getKey().equals(PARENT_CONSENTS_KEY)) {
                continue;
            }
            Map<String, Double> axisMap = Collections.synchronizedMap(new HashMap<>());
            Arrays.stream(filter.getValue()).parallel().forEach(value -> {
                QueryRequest newRequest = new QueryRequest(queryRequest);
                newRequest.getQuery().categoryFilters.clear();
                newRequest.getQuery().categoryFilters.put(CONSENTS_KEY, _consents);
                if (_harmonized_consents != null && _harmonized_consents.length > 0) {
                    newRequest.getQuery().categoryFilters.put(HARMONIZED_CONSENT_KEY, _harmonized_consents);
                }
                if (_topmed_consents != null && _topmed_consents.length > 0) {
                    newRequest.getQuery().categoryFilters.put(TOPMED_CONSENTS_KEY, _topmed_consents);
                }
                if (_parent_consents != null && _parent_consents.length > 0) {
                    newRequest.getQuery().categoryFilters.put(TOPMED_CONSENTS_KEY, _parent_consents);
                }
                newRequest.getQuery().categoryFilters.put(filter.getKey(), new String[]{value});
                newRequest.getQuery().expectedResultType = ResultType.COUNT;
                newRequest.setResourceUUID(picSureUuid);
                logger.info("Calling /picsure/query/sync for categoryFilters field with query:  \n" + newRequest.getQuery().toString());
                Double result = restTemplate.exchange(picSureUrl, HttpMethod.POST, new HttpEntity<>(newRequest, headers), Double.class).getBody();
                String tempValue = value;
                if (value.length() > 70) {
                    String holder = value.substring(0, 70);
                    int target = holder.lastIndexOf(" ");
                    tempValue = value.substring(0, target) + "\n" + value.substring(target+1, value.length()-1);
                }
                axisMap.put(tempValue, result);
            });
            String[] titleParts = filter.getKey().split("\\\\");
            String title = filter.getKey();
            if (title.length() > 4) {
                title = "Variable distribution of " + titleParts[3] + ": " + titleParts[4];
            }
            categoricalDataList.add(new CategoricalData(title, new HashMap<>(axisMap)));
            axisMap.clear();
        }
        logger.debug("Finished Categorical Data with " + categoricalDataList.size() + " results");
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
