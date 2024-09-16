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
        logger.info("VisualizationResource initialized -> {}", properties.getOrigin());

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
            // The exception is caught here because I don't want to modify the method signature to throw the
            // exception.
            logger.error("Error parsing query:  \n{}", query, e);
            return Response.status(Response.Status.BAD_REQUEST).entity("Could not parse query.").build();
        }

        if (AUTHORIZED.equals(requestSource)) {
            Map<String, Map<String, Integer>> categoryCrossCountsMap = getCategoryCrossCountsMap(query, queryJson);
            Map<String, Map<String, Integer>> continuousCrossCountsMap = getContinuousCrossCount(query, queryJson);

            return getProcessedCrossCountResponse(categoryCrossCountsMap, continuousCrossCountsMap);
        } else {
            Map<String, Map<String, String>> openCategoricalCrossCounts = getOpenCategoricalCrossCounts(query, queryJson);
            Map<String, Map<String, String>> openContinuousCrossCounts = getOpenContinuousCrossCounts(query, queryJson);

            return getOpenProcessedCrossCountResponse(openCategoricalCrossCounts, openContinuousCrossCounts);
        }
    }

    private Response getProcessedCrossCountResponse(
        Map<String, Map<String, Integer>> categoryCrossCountsMap, Map<String, Map<String, Integer>> continuousCrossCountsMap
    ) {
        if (
            (categoryCrossCountsMap == null || categoryCrossCountsMap.isEmpty())
                && (continuousCrossCountsMap == null || continuousCrossCountsMap.isEmpty())
        ) return Response.ok().build();
        ProcessedCrossCountsResponse response =
            buildProcessedCrossCountsResponse(categoryCrossCountsMap, continuousCrossCountsMap, false, false, false);
        return Response.ok(response).build();
    }

    /**
     * This method determines if the data is obfuscated and if so, converts the string values to integers by removing the obfuscation types.
     * If the value was obfuscated the response will be marked as obfuscated so the UI can display the data accordingly.
     *
     * @param categoryCrossCountsMap - the categorical cross counts
     * @param continuousCrossCountsMap - the continuous cross counts
     * @return Response - the processed cross counts response
     */
    private Response getOpenProcessedCrossCountResponse(
        Map<String, Map<String, String>> categoryCrossCountsMap, Map<String, Map<String, String>> continuousCrossCountsMap
    ) {
        Map<String, Map<String, Integer>> cleanedCategoricalData = new HashMap<>();
        boolean isCategoricalObfuscated = false;
        if (categoryCrossCountsMap != null && !categoryCrossCountsMap.isEmpty()) {
            isCategoricalObfuscated = isObfuscated(categoryCrossCountsMap);
            cleanedCategoricalData = cleanCrossCountData(categoryCrossCountsMap);
        }

        boolean isContinuousObfuscated = false;
        Map<String, Map<String, Integer>> cleanedContinuousData = new HashMap<>();
        if (continuousCrossCountsMap != null && !continuousCrossCountsMap.isEmpty()) {
            isContinuousObfuscated = isObfuscated(continuousCrossCountsMap);
            cleanedContinuousData = cleanCrossCountData(continuousCrossCountsMap);
        }

        ProcessedCrossCountsResponse response = buildProcessedCrossCountsResponse(
            cleanedCategoricalData, cleanedContinuousData, isCategoricalObfuscated, isContinuousObfuscated, true
        );
        return Response.ok(response).build();
    }

    /**
     * This method removes the obfuscation types from the categorical data. The obfuscation types are the threshold and variance values that
     * are appended to the cross counts when the data is obfuscated.
     *
     * @param crossCounts - the categorical cross counts
     * @return Map<String, Map < String, Integer>> - the cleaned categorical data
     */
    private Map<String, Map<String, Integer>> cleanCrossCountData(Map<String, Map<String, String>> crossCounts) {
        // remove the obfuscation types from the categorical data
        Map<String, Map<String, Integer>> cleanedCrossCounts = new HashMap<>();
        String thresholdReplacement = String.valueOf((properties.getTargetPicsureObfuscationThreshold() - 1));
        crossCounts.forEach((key, value) -> {
            Map<String, Integer> temp = new HashMap<>();
            if (!key.equals("\\_harmonized_consent\\")) {
                value.forEach((subKey, subValue) -> {
                    if (subValue.contains(threshold)) {
                        subValue = subValue.replace(threshold, thresholdReplacement);
                    } else if (subValue.contains(variance)) {
                        subValue = subValue.replace(variance, "");
                    }

                    temp.put(subKey, Integer.parseInt(subValue.trim()));
                });
            }

            cleanedCrossCounts.put(key, temp);
        });

        return cleanedCrossCounts;
    }


    private boolean isObfuscated(Map<String, Map<String, String>> crossCounts) {
        boolean isObfuscated = false;
        for (Map.Entry<String, Map<String, String>> e : crossCounts.entrySet()) {
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

    private ProcessedCrossCountsResponse buildProcessedCrossCountsResponse(
        Map<String, Map<String, Integer>> categoryCrossCountsMap, Map<String, Map<String, Integer>> continuousCrossCountsMap,
        boolean isCategoricalObfuscated, boolean isContinuousObfuscated, boolean isOpenAccess
    ) {

        ProcessedCrossCountsResponse response = new ProcessedCrossCountsResponse();
        response.getCategoricalData()
            .addAll(dataProcessingServices.getCategoricalData(categoryCrossCountsMap, isCategoricalObfuscated, isOpenAccess));
        response.getContinuousData()
            .addAll(dataProcessingServices.getContinuousData(continuousCrossCountsMap, isContinuousObfuscated, isOpenAccess));
        return response;
    }

    private Map<String, Map<String, Integer>> getCategoryCrossCountsMap(QueryRequest query, Query queryJson) {
        Map<String, Map<String, Integer>> categoryCrossCountsMap;
        if (
            (queryJson.categoryFilters != null && !queryJson.categoryFilters.isEmpty())
                || (queryJson.requiredFields != null && !queryJson.requiredFields.isEmpty())
        ) {
            categoryCrossCountsMap = hpdsServices.getAuthCrossCountsMap(query, ResultType.CATEGORICAL_CROSS_COUNT);
        } else {
            categoryCrossCountsMap = new HashMap<>();
        }
        return categoryCrossCountsMap;
    }

    /**
     * @param query QueryRequest
     * @param queryJson Query
     * @return Map<String, Map < String, Integer>> - the continuous cross counts
     */
    private Map<String, Map<String, Integer>> getContinuousCrossCount(QueryRequest query, Query queryJson) {
        Map<String, Map<String, Integer>> continuousCrossCountsMap;
        if ((queryJson.numericFilters != null && !queryJson.numericFilters.isEmpty())) {
            continuousCrossCountsMap = hpdsServices.getAuthCrossCountsMap(query, ResultType.CONTINUOUS_CROSS_COUNT);
        } else {
            continuousCrossCountsMap = new HashMap<>();
        }
        return continuousCrossCountsMap;
    }

    private Map<String, Map<String, String>> getOpenCategoricalCrossCounts(QueryRequest query, Query queryJson) {
        Map<String, Map<String, String>> crossCountsMap;
        if (
            (queryJson.categoryFilters != null && !queryJson.categoryFilters.isEmpty())
                || (queryJson.requiredFields != null && !queryJson.requiredFields.isEmpty())
        ) {
            crossCountsMap = hpdsServices.getOpenCrossCountsMap(query, ResultType.CATEGORICAL_CROSS_COUNT);
        } else {
            crossCountsMap = new HashMap<>();
        }

        return crossCountsMap;
    }

    private Map<String, Map<String, String>> getOpenContinuousCrossCounts(QueryRequest query, Query queryJson) {
        Map<String, Map<String, String>> crossCountsMap;
        if ((queryJson.numericFilters != null && !queryJson.numericFilters.isEmpty())) {
            crossCountsMap = hpdsServices.getOpenCrossCountsMap(query, ResultType.CONTINUOUS_CROSS_COUNT);
        } else {
            crossCountsMap = new HashMap<>();
        }

        return crossCountsMap;
    }

    /**
     * Given a query containing continuous data, bin the data and return the binned data.
     *
     * @param continuousData QueryRequest - the query request
     * @return Response - the binned data
     */
    public Response generateContinuousBin(QueryRequest continuousData) {
        // validate the continuous data
        if (continuousData == null || continuousData.getQuery() == null) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Continuous data is required.").build();
        }

        logger.info("Continuous data: " + continuousData.getQuery());
        Map<String, Map<String, Integer>> continuousDataMap = mapper.convertValue(continuousData.getQuery(), new TypeReference<>() {});
        Map<String, Map<String, Integer>> continuousProcessedData = dataProcessingServices.binContinuousData(continuousDataMap);
        return Response.ok(continuousProcessedData).build();
    }
}
