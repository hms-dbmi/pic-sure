package edu.harvard.hms.dbmi.avillach.resource.visualization.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.hms.dbmi.avillach.resource.visualization.ApplicationProperties;
import edu.harvard.hms.dbmi.avillach.resource.visualization.model.ProcessedCrossCountsResponse;
import edu.harvard.hms.dbmi.avillach.resource.visualization.model.domain.Query;
import edu.harvard.hms.dbmi.avillach.resource.visualization.model.domain.ResultType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ejb.Stateless;
import javax.inject.Inject;
import javax.ws.rs.core.Response;
import java.util.HashMap;
import java.util.Map;

@Stateless
public class VisualizationService {

    private final Logger logger = LoggerFactory.getLogger(VisualizationService.class);

    @Inject
    DataProcessingService dataProcessingServices;

    @Inject
    HpdsService hpdsServices;

    @Inject
    ApplicationProperties properties;

    private final ObjectMapper mapper = new ObjectMapper();
    private static final String AUTHORIZED = "Authorized";

    private final String threshold;
    private final String variance;

    VisualizationService() {
        if (properties == null) {
            properties = new ApplicationProperties();
            logger.info("Initializing properties");
        }
        properties.init("pic-sure-visualization-resource");
        logger.info("VisualizationResource initialized ->", properties.getOrigin());

        threshold = "< " + properties.getTargetPicsureObfuscationThreshold();
        variance = "±" + properties.getTargetPicsureObfuscationVariance();
    }

    /**
     * Handles a query request from the UI. This method is called from the VisualizationResource class.
     *
     * @param query QueryRequest - the query request
     * @param requestSource String - the request source, Authorized or Open
     * @return ProcessedCrossCountsResponse
     */
    public Response handleQuerySync(QueryRequest query, String requestSource) {
        Query queryJson;
        try {
            queryJson = mapper.readValue(mapper.writeValueAsString(query.getQuery()), Query.class);
        } catch (Exception e) {
            logger.error("Error parsing query:  \n" + query, e);
            return Response.status(Response.Status.BAD_REQUEST).entity("Error parsing query:  \n" + query).build();
        }

        if (AUTHORIZED.equals(requestSource)) {
            Map<String, Map<String, Integer>> categroyCrossCountsMap = getCategroyCrossCountsMap(query, queryJson);
            Map<String, Map<String, Integer>> continuousCrossCountsMap = getContinuousCrossCount(query, queryJson);

            return getProcessedCrossCountResponse(categroyCrossCountsMap, continuousCrossCountsMap);
        } else {
            Map<String, Map<String, String>> openCategoricalCrossCounts = getOpenCrossCounts(query, queryJson, ResultType.CATEGORICAL_CROSS_COUNT);
            Map<String, Map<String, String>> openContinuousCrossCounts = getOpenCrossCounts(query, queryJson, ResultType.CONTINUOUS_CROSS_COUNT);

            logger.info("openCategoricalCrossCounts: " + openCategoricalCrossCounts);
            return getOpenProcessedCrossCountResponse(openCategoricalCrossCounts, openContinuousCrossCounts);
        }
    }

    private Response getProcessedCrossCountResponse(Map<String, Map<String, Integer>> categroyCrossCountsMap, Map<String, Map<String, Integer>> continuousCrossCountsMap) {
        if ((categroyCrossCountsMap == null || categroyCrossCountsMap.isEmpty()) && (continuousCrossCountsMap == null || continuousCrossCountsMap.isEmpty()))
            return Response.ok().build();
        ProcessedCrossCountsResponse response = buildProcessedCrossCountsResponse(categroyCrossCountsMap, continuousCrossCountsMap);
        return Response.ok(response).build();
    }

    private Response getOpenProcessedCrossCountResponse(Map<String, Map<String, String>> categroyCrossCountsMap,
                                                        Map<String, Map<String, String>> continuousCrossCountsMap) {
        if ((categroyCrossCountsMap == null || categroyCrossCountsMap.isEmpty()))
            return Response.ok().build();

        boolean isCategoricalObfuscated = isObfuscated(categroyCrossCountsMap);
        Map<String, Map<String, Integer>> cleanedCategoricalData = cleanCategoricalData(categroyCrossCountsMap);

        boolean isContinuousObfuscated = isObfuscated(continuousCrossCountsMap);
        Map<String, Map<String, Integer>> cleanedContinuousData = cleanCategoricalData(continuousCrossCountsMap);

        ProcessedCrossCountsResponse response = buildOpenProcessedCrossCountsResponse(cleanedCategoricalData, cleanedContinuousData, (isCategoricalObfuscated || isContinuousObfuscated));
        return Response.ok(response).build();
    }

    private Map<String, Map<String, Integer>> cleanCategoricalData(Map<String, Map<String, String>> categroyCrossCountsMap) {
        // remove the obfuscation types from the categorical data
        Map<String, Map<String, Integer>> cleanedCategoricalData = new HashMap<>();

        categroyCrossCountsMap.forEach((key, value) -> {
            Map<String, Integer> temp = new HashMap<>();
            value.forEach((subKey, subValue) -> {
                if (subValue.contains(threshold)) {
                    subValue = subValue.replace(threshold, "9");
                } else if (subValue.contains(variance)) {
                    subValue = subValue.replace(variance, "");
                }

                temp.put(subKey, Integer.parseInt(subValue.trim()));
            });

            cleanedCategoricalData.put(key, temp);
        });

        return cleanedCategoricalData;
    }

    private Map<String, Map<String, Boolean>> generateObfuscationMap(Map<String, Map<String, String>> categroyCrossCountsMap) {
        Map<String, Map<String, Boolean>> crossCountsObfuscationMap = new HashMap<>();

        categroyCrossCountsMap.forEach((key, value) -> {
            Map<String, Boolean> tempObf = new HashMap<>();
            value.forEach((subKey, subValue) -> {
                // If the value contains either of the obfuscation types, set the value to true
                if (subValue.contains(threshold) || subValue.contains(variance)) {
                    tempObf.put(subKey, true);
                } else {
                    tempObf.put(subKey, false);
                }
            });

            crossCountsObfuscationMap.put(key, tempObf);
        });

        return crossCountsObfuscationMap;
    }

    private boolean isObfuscated(Map<String, Map<String, String>> categroyCrossCountsMap) {
        boolean isObfuscated = false;
        for (Map.Entry<String, Map<String, String>> e : categroyCrossCountsMap.entrySet()) {
            Map<String, String> value = e.getValue();

            for (Map.Entry<String, String> entry : value.entrySet()) {
                String subValue = entry.getValue();
                if (subValue.contains(threshold) || subValue.contains(variance)) {
                    isObfuscated = true;
                    break;
                }
            }
        }

        return isObfuscated;
    }

    private ProcessedCrossCountsResponse buildOpenProcessedCrossCountsResponse(Map<String, Map<String, Integer>> categroyCrossCountsMap,
                                                                               Map<String, Map<String, Integer>> continuousCrossCountsMap,
                                                                               boolean isObfuscated) {

        ProcessedCrossCountsResponse response = new ProcessedCrossCountsResponse();
        response.getCategoricalData().addAll(dataProcessingServices.getCategoricalData(categroyCrossCountsMap, isObfuscated));
        response.getContinuousData().addAll(dataProcessingServices.getContinuousData(continuousCrossCountsMap, isObfuscated));
        return response;
    }

    private ProcessedCrossCountsResponse buildProcessedCrossCountsResponse(Map<String, Map<String, Integer>> categroyCrossCountsMap, Map<String, Map<String, Integer>> continuousCrossCountsMap) {
        ProcessedCrossCountsResponse response = new ProcessedCrossCountsResponse();
        response.getCategoricalData().addAll(dataProcessingServices.getCategoricalData(categroyCrossCountsMap));
        response.getContinuousData().addAll(dataProcessingServices.getContinuousData(continuousCrossCountsMap));
        return response;
    }

    private Map<String, Map<String, Integer>> getCategroyCrossCountsMap(QueryRequest query, Query queryJson) {
        Map<String, Map<String, Integer>> categroyCrossCountsMap;
        if ((queryJson.categoryFilters != null && queryJson.categoryFilters.size() > 0) || (queryJson.requiredFields != null && queryJson.requiredFields.size() > 0)) {
            categroyCrossCountsMap = hpdsServices.getAuthCrossCountsMap(query, ResultType.CATEGORICAL_CROSS_COUNT);
        } else {
            categroyCrossCountsMap = new HashMap<>();
        }
        return categroyCrossCountsMap;
    }

    private Map<String, Map<String, Integer>> getContinuousCrossCount(QueryRequest query, Query queryJson) {
        Map<String, Map<String, Integer>> continuousCrossCountsMap;
        if ((queryJson.numericFilters != null && queryJson.numericFilters.size() > 0)) {
            continuousCrossCountsMap = hpdsServices.getAuthCrossCountsMap(query, ResultType.CONTINUOUS_CROSS_COUNT);
        } else {
            continuousCrossCountsMap = new HashMap<>();
        }
        return continuousCrossCountsMap;
    }

    private Map<String, Map<String, String>> getOpenCrossCounts(QueryRequest query, Query queryJson, ResultType resultType) {
        Map<String, Map<String, String>> crossCountsMap;
        if ((queryJson.numericFilters != null && queryJson.numericFilters.size() > 0)
                || (queryJson.categoryFilters != null && queryJson.categoryFilters.size() > 0)
                || (queryJson.requiredFields != null && queryJson.requiredFields.size() > 0)) {
            crossCountsMap = hpdsServices.getOpenCrossCountsMap(query, resultType);
        } else {
            crossCountsMap = new HashMap<>();
        }

        return crossCountsMap;
    }

    public Response generateContinuousBin(QueryRequest continuousData) {
        logger.info("Continuous data: " + continuousData.getQuery());
        Map<String, Map<String, Integer>> continuousDataMap = mapper.convertValue(continuousData.getQuery(), new TypeReference<Map<String, Map<String, Integer>>>() {});
        Map<String, Map<String, Integer>> continuousProcessedData = dataProcessingServices.binContinuousData(continuousDataMap);
        return Response.ok(continuousProcessedData).build();
    }
}
