package edu.harvard.hms.dbmi.avillach.resource.visualization.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.hms.dbmi.avillach.resource.visualization.ApplicationProperties;
import edu.harvard.hms.dbmi.avillach.resource.visualization.model.ProcessedCrossCountsResponse;
import edu.harvard.hms.dbmi.avillach.resource.visualization.model.domain.Query;
import edu.harvard.hms.dbmi.avillach.resource.visualization.model.domain.ResultType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.ws.rs.core.Response;
import java.util.HashMap;
import java.util.Map;

public class VisualizationService {

    private final Logger logger = LoggerFactory.getLogger(VisualizationService.class);

    @Inject
    DataProcessingService dataProcessingServices;

    @Inject
    HpdsService hpdsServices;

    @Inject
    ApplicationProperties properties;

    private final ObjectMapper mapper = new ObjectMapper();

    VisualizationService() {
        if (properties == null) {
            properties = new ApplicationProperties();
            logger.info("Initializing properties");
        }
        properties.init("pic-sure-visualization-resource");
        logger.info("VisualizationResource initialized ->", properties.getOrigin());
    }

    /**
     * Handles a query request from the UI. This method is called from the VisualizationResource class.
     *
     * @param query QueryRequest - the query request
     * @param requestSource String - the request source, Authorized or Open
     * @return ProcessedCrossCountsResponse
     */
    public Response handleQuerySync(QueryRequest query, String requestSource) {
        logger.debug("Received query:  \n" + query);

        Query queryJson;
        try {
            queryJson = mapper.readValue(mapper.writeValueAsString(query.getQuery()), Query.class);
        } catch (Exception e) {
            logger.error("Error parsing query:  \n" + query, e);
            return Response.status(Response.Status.BAD_REQUEST).entity("Error parsing query:  \n" + query).build();
        }

        if (requestSource.equals("Authorized")) {
            Map<String, Map<String, Integer>> categroyCrossCountsMap = getCategroyCrossCountsMap(query, queryJson);
            Map<String, Map<String, Integer>> continuousCrossCountsMap = getContinuousCrossCount(query, queryJson);

            return getProcessedCrossCountResponse(categroyCrossCountsMap, continuousCrossCountsMap);
        } else {
            Map<String, Map<String, String>> openCategoricalCrossCounts = getOpenCrossCounts(query, queryJson, ResultType.CATEGORICAL_CROSS_COUNT);

            logger.info("openCategoricalCrossCounts: " + openCategoricalCrossCounts);
            return getOpenProcessedCrossCountResponse(openCategoricalCrossCounts);
        }
    }

    private Response getProcessedCrossCountResponse(Map<String, Map<String, Integer>> categroyCrossCountsMap, Map<String, Map<String, Integer>> continuousCrossCountsMap) {
        if ((categroyCrossCountsMap == null || categroyCrossCountsMap.isEmpty()) && (continuousCrossCountsMap == null || continuousCrossCountsMap.isEmpty()))
            return Response.ok().build();
        ProcessedCrossCountsResponse response = buildProcessedCrossCountsResponse(categroyCrossCountsMap, continuousCrossCountsMap);
        return Response.ok(response).build();
    }

    private Response getOpenProcessedCrossCountResponse(Map<String, Map<String, String>> categroyCrossCountsMap) {
        if ((categroyCrossCountsMap == null || categroyCrossCountsMap.isEmpty()))
            return Response.ok().build();

        ProcessedCrossCountsResponse response = buildOpenProcessedCrossCountsResponse(categroyCrossCountsMap);
        return Response.ok(response).build();
    }

    private ProcessedCrossCountsResponse buildOpenProcessedCrossCountsResponse(Map<String, Map<String, String>> categroyCrossCountsMap) {
        ProcessedCrossCountsResponse response = new ProcessedCrossCountsResponse();
//        response.getCategoricalData().addAll(dataProcessingServices.getCategoricalData(categroyCrossCountsMap));
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
        if ((queryJson.numericFilters != null && queryJson.numericFilters.size() > 0) || (queryJson.categoryFilters != null && queryJson.categoryFilters.size() > 0) || (queryJson.requiredFields != null && queryJson.requiredFields.size() > 0)) {
            crossCountsMap = hpdsServices.getOpenCrossCountsMap(query, resultType);
        } else {
            crossCountsMap = new HashMap<>();
        }

        return crossCountsMap;
    }

}
