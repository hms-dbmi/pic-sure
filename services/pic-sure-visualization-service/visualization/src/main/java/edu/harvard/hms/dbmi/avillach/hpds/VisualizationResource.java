package edu.harvard.hms.dbmi.avillach.hpds;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import edu.harvard.hms.dbmi.avillach.hpds.model.CategoricalData;
import edu.harvard.hms.dbmi.avillach.hpds.model.ContinuousData;
import edu.harvard.hms.dbmi.avillach.hpds.model.IResourceRS;
import edu.harvard.hms.dbmi.avillach.hpds.model.VisualizationResponse;
import edu.harvard.hms.dbmi.avillach.hpds.model.domain.*;
import edu.harvard.hms.dbmi.avillach.hpds.service.ChartService;
import edu.harvard.hms.dbmi.avillach.hpds.service.DataService;
import edu.harvard.hms.dbmi.avillach.hpds.service.VisualizationService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import java.util.*;

@Path("/pic-sure")
@Produces({"application/json"})
@Consumes({"application/json"})
@RequiredArgsConstructor
@JsonIgnoreProperties
public class VisualizationResource implements IResourceRS {

//    public static void main(String[] args) {
//        String picSureUrl = "https://biodatacatalyst.integration.hms.harvard.edu/picsure/query/sync";
//        String searchUrl = "https://biodatacatalyst.integration.hms.harvard.edu/picsure/search/70c837be-5ffc-11eb-ae93-0242ac130002";
//        QueryRequest queryRequest = new QueryRequest();
//        Map<String, Double> axisMap = new HashMap<>();
//        queryRequest.setResourceCredentials(new HashMap<>());
//        queryRequest.setQuery(new Query());
//        queryRequest.getQuery().numericFilters = new HashMap<>();
//        queryRequest.getQuery().expectedResultType = ResultType.DATAFRAME;
//        //queryRequest.setResourceUUID(UUID.fromString("02e23f52-f354-4e8b-992c-d37c8b9ba140"));
//        queryRequest.getResourceCredentials().put("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJmZW5jZXw0MDgzIiwibmFtZSI6ImpAbWVzcGVjay5uZXQiLCJpc3MiOiJlZHUuaGFydmFyZC5obXMuZGJtaS5wc2FtYSIsImV4cCI6MTY0NzQzOTg3NywiaWF0IjoxNjQ3NDM2Mjc3LCJlbWFpbCI6ImpAbWVzcGVjay5uZXQiLCJqdGkiOiJ3aGF0ZXZlciJ9.W92Qy73ZTVhXVth2ENESc1G35M6VAXldRx9ajaJLx80");
////        queryRequest.getQuery().numericFilters.put("\\phs000209\\pht001121\\phv00087071\\agefc\\", new Filter.DoubleFilter(39.0, 96.0));
////        queryRequest.getQuery().categoryFilters.put("\\_consents\\", new String[]{"phs001001.c1", "phs002348.MISSING CONSENTS INFORMATION WHILE BUILDING METADATA", "phs001001.c2", "phs001345.c1", "phs000956.c2", "phs001368.c3", "phs000956.c0", "phs001368.c2", "phs001402.c1", "phs001368.c1", "phs000988.c1", "phs000286.c4", "phs000810.c2", "phs001207.c1", "phs000286.c3", "phs000286.c2", "phs000810.c1", "phs001207.c0", "phs000810.c0", "phs000286.c1", "phs000286.c0", "phs000200.c0", "phs000200.c1", "phs000988.c0", "phs000951.c2", "phs000951.c1", "phs000997.c1", "phs002383.MISSING CONSENTS INFORMATION WHILE BUILDING METADATA", "phs000007.c0", "phs000007.c2", "phs000007.c1", "phs001180.c2", "phs001180.c0", "phs001194.c1", "phs001194.c2", "phs001368.c4", "phs002362.MISSING CONSENTS INFORMATION WHILE BUILDING METADATA", "phs001215.c0", "phs000784.c1", "phs000784.c0", "phs001238.c0", "phs001238.c1", "phs001215.c1", "phs000954.c1", "phs000921.c2", "phs000280.c1", "phs000280.c0", "phs002415.MISSING CONSENTS INFORMATION WHILE BUILDING METADATA", "phs000703.c1", "phs000280.c2", "phs000974.c1", "phs001218.c0", "phs000974.c2", "phs001218.c2", "phs001062.c1", "phs001062.c2", "phs001359.c1", "phs001387.c3", "phs001189.c0", "phs001416.c1", "phs001416.c2", "phs001217.c1", "phs001217.c0", "phs000820.c1", "phs000946.c1", "phs001189.c1", "phs001387.c0", "phs001143.c1", "phs001143.c0", "phs001252.c1", "phs000422.c1", "phs001040.c1", "phs000972.c1", "phs001237.c1", "phs001237.c2", "phs000179.c1", "phs000993.c1", "phs000179.c2", "phs000920.c2", "phs000993.c2", "phs000179.c0", "phs002386.MISSING CONSENTS INFORMATION WHILE BUILDING METADATA", "phs001293.c1", "phs001293.c2", "phs001293.c0", "phs000914.c0", "phs000914.c1", "phs000285.c2", "phs000285.c1", "phs000285.c0", "phs000209.c0", "phs000209.c1", "phs000209.c2", "phs001074.c0", "phs001074.c2", "phs000287.c0", "phs001032.c1", "phs001013.c1", "phs001013.c2", "phs000284.c0", "phs000284.c1", "phs000200.c2", "phs000964.c3", "phs000964.c4", "phs000964.c0", "phs000964.c1", "phs000964.c2", "phs000287.c1", "phs001211.c2", "phs002363.MISSING CONSENTS INFORMATION WHILE BUILDING METADATA", "phs001211.c1", "phs000287.c2", "phs000287.c3", "phs000287.c4", "phs001412.c2", "phs001024.c1", "phs001412.c1", "phs001412.c0"});
////        queryRequest.getQuery().categoryFilters.put("\\phs000209\\pht001121\\phv00087119\\asthmaf\\", new String[]{"NO", "YES"});
//
//        queryRequest.getQuery().requiredFields.add("\\\\phs000209\\\\pht001111\\\\phv00082975\\\\asthma1\\\\");
//
//        queryRequest.getQuery().fields.add("\\_Topmed Study Accession with Subject ID\\");
//        queryRequest.getQuery().fields.add("\\_Parent Study Accession with Subject ID\\");
////        String json = "{\"resourceUUID\":\"02e23f52-f354-4e8b-992c-d37c8b9ba140\",\"query\":{\"categoryFilters\":{\"\\\\_consents\\\\\":[\"phs001001.c1\",\"phs002348.MISSING CONSENTS INFORMATION WHILE BUILDING METADATA\",\"phs001001.c2\",\"phs001345.c1\",\"phs000956.c2\",\"phs001368.c3\",\"phs000956.c0\",\"phs001368.c2\",\"phs001402.c1\",\"phs001368.c1\",\"phs000988.c1\",\"phs000286.c4\",\"phs000810.c2\",\"phs001207.c1\",\"phs000286.c3\",\"phs000286.c2\",\"phs000810.c1\",\"phs001207.c0\",\"phs000810.c0\",\"phs000286.c1\",\"phs000286.c0\",\"phs000200.c0\",\"phs000200.c1\",\"phs000988.c0\",\"phs000951.c2\",\"phs000951.c1\",\"phs000997.c1\",\"phs002383.MISSING CONSENTS INFORMATION WHILE BUILDING METADATA\",\"phs000007.c0\",\"phs000007.c2\",\"phs000007.c1\",\"phs001180.c2\",\"phs001180.c0\",\"phs001194.c1\",\"phs001194.c2\",\"phs001368.c4\",\"phs002362.MISSING CONSENTS INFORMATION WHILE BUILDING METADATA\",\"phs001215.c0\",\"phs000784.c1\",\"phs000784.c0\",\"phs001238.c0\",\"phs001238.c1\",\"phs001215.c1\",\"phs000954.c1\",\"phs000921.c2\",\"phs000280.c1\",\"phs000280.c0\",\"phs002415.MISSING CONSENTS INFORMATION WHILE BUILDING METADATA\",\"phs000703.c1\",\"phs000280.c2\",\"phs000974.c1\",\"phs001218.c0\",\"phs000974.c2\",\"phs001218.c2\",\"phs001062.c1\",\"phs001062.c2\",\"phs001359.c1\",\"phs001387.c3\",\"phs001189.c0\",\"phs001416.c1\",\"phs001416.c2\",\"phs001217.c1\",\"phs001217.c0\",\"phs000820.c1\",\"phs000946.c1\",\"phs001189.c1\",\"phs001387.c0\",\"phs001143.c1\",\"phs001143.c0\",\"phs001252.c1\",\"phs000422.c1\",\"phs001040.c1\",\"phs000972.c1\",\"phs001237.c1\",\"phs001237.c2\",\"phs000179.c1\",\"phs000993.c1\",\"phs000179.c2\",\"phs000920.c2\",\"phs000993.c2\",\"phs000179.c0\",\"phs002386.MISSING CONSENTS INFORMATION WHILE BUILDING METADATA\",\"phs001293.c1\",\"phs001293.c2\",\"phs001293.c0\",\"phs000914.c0\",\"phs000914.c1\",\"phs000285.c2\",\"phs000285.c1\",\"phs000285.c0\",\"phs000209.c0\",\"phs000209.c1\",\"phs000209.c2\",\"phs001074.c0\",\"phs001074.c2\",\"phs000287.c0\",\"phs001032.c1\",\"phs001013.c1\",\"phs001013.c2\",\"phs000284.c0\",\"phs000284.c1\",\"phs000200.c2\",\"phs000964.c3\",\"phs000964.c4\",\"phs000964.c0\",\"phs000964.c1\",\"phs000964.c2\",\"phs000287.c1\",\"phs001211.c2\",\"phs002363.MISSING CONSENTS INFORMATION WHILE BUILDING METADATA\",\"phs001211.c1\",\"phs000287.c2\",\"phs000287.c3\",\"phs000287.c4\",\"phs001412.c2\",\"phs001024.c1\",\"phs001412.c1\",\"phs001412.c0\"],\"\\\\phs000287\\\\pht001480\\\\phv00103374\\\\ASTHMA32\\\\\":[\"NEVER TOLD\",\"TOLD DURING THE PAST YEAR\",\"TOLD MORE THAN 1 YEAR AGO\"]},\"numericFilters\":{},\"requiredFields\":[],\"fields\":[\"\\\\_Topmed Study Accession with Subject ID\\\\\",\"\\\\_Parent Study Accession with Subject ID\\\\\"],\"variantInfoFilters\":[{\"categoryVariantInfoFilters\":{},\"numericVariantInfoFilters\":{}}],\"expectedResultType\":\"COUNT\"}}";
//        RestTemplate restTemplate = new RestTemplate();
//        HttpHeaders headers = new HttpHeaders();
//        String token = queryRequest.getResourceCredentials().get("Authorization");
//        headers.add("Authorization", token);
//        headers.setContentType(MediaType.APPLICATION_JSON);
////        String[] consents = queryRequest.getQuery().categoryFilters.get("\\_consents\\");
//        List<HttpEntity<QueryRequest>> requests = new ArrayList<>();
//        String CONSENTS_KEY = "\\_consents\\";
//        List<CategoricalData> categoricalDataList = new ArrayList<>();
//        String[] _consents = queryRequest.getQuery().categoryFilters.get(CONSENTS_KEY);
//        for (String filter : queryRequest.getQuery().requiredFields) {
//            System.out.println("filter: " + filter);
//            String body = "{\"query\": \"" + filter + "\"}";
//            ObjectMapper mapper = new ObjectMapper();
//            JsonNode actualObj = null;
//            try {
//                actualObj = mapper.readTree(body);
//            } catch (JsonProcessingException e) {
//                e.printStackTrace();
//            }
//            System.out.println("body: " + body);
//            SearchResults searchResults = restTemplate.exchange(searchUrl, HttpMethod.POST, new HttpEntity<>(actualObj, headers), SearchResults.class).getBody();
//            System.out.println("searchResults: " + searchResults);
//            //SearchResults searchResults = new SearchResults();
////            searchResults.setResults(new ArrayList<>());
////            searchResults.getResults().add(new Results());
////            Map<String, SearchResult> phenotypes = new HashMap<>();
////            SearchResult first = new SearchResult();
////            String[] values = new String[2];
////            values[0] = "No";
////            values[1] = "Yes";
////            first.setCategoryValues(values);
////            first.setCategorical(true);
////            first.setObservationCount(251);
////            phenotypes.put("\\phs000209\\pht001111\\phv00082975\\asthma1\\", first);
////            searchResults.getResults().get(0).setPhenotypes(phenotypes);
//            for (Map.Entry<String, SearchResult> phenotype : searchResults.getResults().getPhenotypes().entrySet()) {
//                queryRequest.getQuery().categoryFilters.put(phenotype.getKey(), phenotype.getValue().getCategoryValues());
//            }
//        }
//        for (Map.Entry<String, String[]> filter : queryRequest.getQuery().categoryFilters.entrySet()) {
//            if (filter.getKey().equals(CONSENTS_KEY)) {
//                continue;
//            }
//            for (String value : filter.getValue()) {
//                QueryRequest newRequest = new QueryRequest(queryRequest);
//                newRequest.setResourceUUID(UUID.fromString("02e23f52-f354-4e8b-992c-d37c8b9ba140"));
//                newRequest.getQuery().categoryFilters.clear();
//                newRequest.getQuery().categoryFilters.put(CONSENTS_KEY, _consents);
//                newRequest.getQuery().categoryFilters.put(filter.getKey(), new String[]{value});
//                newRequest.getQuery().expectedResultType = ResultType.COUNT;
//                Double result = restTemplate.exchange(picSureUrl, HttpMethod.POST, new HttpEntity<>(newRequest, headers), Double.class).getBody();
//                axisMap.put(value, result);
//            }
//            categoricalDataList.add(new CategoricalData(filter.getKey(), axisMap));
//        }
//        categoricalDataList.forEach(System.out::println);
//
//
////        for (Map.Entry<String, String[]> filter: queryRequest.getQuery().categoryFilters.entrySet()) {
////            if (filter.getKey().equals("\\_consents\\")) {
////                continue;
////            }
////            if (filter.getValue().length > 1) {
////                for (String value: filter.getValue()) {
////                    QueryRequest newRequest = new QueryRequest(queryRequest);
////                    System.out.println("Adding filter: "+filter.getKey()+"="+value);
////                    newRequest.getQuery().categoryFilters.replace(filter.getKey(), new String[]{value});
////                    requests.add(new HttpEntity<>(newRequest, headers)); //TODO: jsut call the reqyest
////                    requests.get(requests.size()-1).getBody().getQuery().categoryFilters.entrySet().forEach(System.out::println);
////                }
////            }
////        }
////        List<Object> results = new ArrayList<>();
////        requests.forEach(request2 -> {
////            System.out.println("//////////////////////////////////////// REQUEST START //////////////////////////////////////////////////////");
////            request2.getBody().getQuery().categoryFilters.entrySet().forEach(o -> System.out.println(o.getKey().toString()+"="+o.getValue()[0]));
////            //results.add(restTemplate.exchange(testUrl, HttpMethod.POST, request2, Object.class).getBody());
////            System.out.println("//////////////////////////////////////// REQUEST END //////////////////////////////////////////////////////");
////        });
////        results.forEach(result -> {
////            System.out.println(result.toString());
////        });
////        Map<Double[], Double[]> axisMap = new HashMap<>();
////        List<ContinuousData> continuousDataList = new ArrayList<>();
////        for (Map.Entry<String, Filter.DoubleFilter> filter: queryRequest.getQuery().numericFilters.entrySet()) {
////            QueryRequest newRequest = new QueryRequest(queryRequest);
////            System.out.println("Adding filter: "+filter.getKey()+"="+filter.getValue());
////            String[] concents = newRequest.getQuery().categoryFilters.get("\\_consents\\");
////            newRequest.getQuery().categoryFilters.clear();
////            newRequest.getQuery().categoryFilters.put("\\_consents\\", concents);
////            newRequest.getQuery().numericFilters.replace(filter.getKey(), filter.getValue());
////            System.out.println(newRequest.getQuery());
////            //String rawResult = "Patient ID,\\_Parent Study Accession with Subject ID\\,\\_Topmed Study Accession with Subject ID\\,\\_consents\\,\\phs000209\\pht001121\\phv00087071\\agefc\\,\\phs000209\\pht001121\\phv00087119\\asthmaf\\\n" +
////                    //"403469,phs000209.v13_37001,phs001416.v2_37001,phs000209.c1,51.0,NO\n";
////            String rawResult = restTemplate.exchange(testUrl, HttpMethod.POST, new HttpEntity<>(newRequest, headers), String.class).getBody();
////                String[] result = rawResult.split("\n");
////                // Ignore the first line
////                Map<Double, Integer> countMap = new HashMap<>();
////                for(int i = 1; i < result.length; i++) {
////                    String[] split = result[i].split(",");
////                    Double key = Double.parseDouble(split[split.length-1]);
////                    if (countMap.containsKey(key)) {
////                        countMap.put(key, countMap.get(key)+1);
////                    } else {
////                        countMap.put(key, 1);
////                    }
////                }
////                continuousDataList.add(new ContinuousData(filter.getKey(), countMap));
////            }
////        ChartService visualizationService = new ChartService();
////        for (ContinuousData data: continuousDataList) {
////            visualizationService.createHistogram(data);
////        }
//    }

    private Logger logger = LoggerFactory.getLogger(VisualizationResource.class);

    @Inject
    private final @NonNull ChartService chartService;
    @Inject
    private final @NonNull DataService dataService;
    @Inject
    private final @NonNull VisualizationService visualizationService;

    @POST
    @Path("/visualizations")
    public VisualizationResponse getVisualizations(QueryRequest queryJson) {
        System.out.println("//////////////////////////////////////// REQUEST START //////////////////////////////////////////////////////");
        List<CategoricalData> categoricalData = dataService.getCategoricalData(queryJson);
        List<ContinuousData> continuousData = dataService.getContinuousData(queryJson);
        VisualizationResponse response = new VisualizationResponse();
        for(CategoricalData data: categoricalData) {
            response.getImages().add(visualizationService.createBase64PNG(chartService.createPieChart(data)));
        }
        for(ContinuousData data: continuousData) {
            response.getImages().add(visualizationService.createBase64PNG(chartService.createHistogram(data)));
        }
        return response;
    }
}
