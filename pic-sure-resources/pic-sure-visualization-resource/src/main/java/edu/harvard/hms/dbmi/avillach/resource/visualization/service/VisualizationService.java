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

        Map<String, Map<String, Integer>> categroyCrossCountsMap = getCategroyCrossCountsMap(requestSource, query, queryJson);
        Map<String, Map<String, Integer>> continuousCrossCountsMap = getContinuousCrossCount(requestSource, query, queryJson);

        return getProcessedCrossCountResponse(categroyCrossCountsMap, continuousCrossCountsMap);
    }

    private Response getProcessedCrossCountResponse(Map<String, Map<String, Integer>> categroyCrossCountsMap, Map<String, Map<String, Integer>> continuousCrossCountsMap) {
        if ((categroyCrossCountsMap == null || categroyCrossCountsMap.isEmpty()) && (continuousCrossCountsMap == null || continuousCrossCountsMap.isEmpty()))
            return Response.ok().build();
        ProcessedCrossCountsResponse response = buildProcessedCrossCountsResponse(categroyCrossCountsMap, continuousCrossCountsMap);
        return Response.ok(response).build();
    }

    private ProcessedCrossCountsResponse buildProcessedCrossCountsResponse(Map<String, Map<String, Integer>> categroyCrossCountsMap, Map<String, Map<String, Integer>> continuousCrossCountsMap) {
        ProcessedCrossCountsResponse response = new ProcessedCrossCountsResponse();
        response.getCategoricalData().addAll(dataProcessingServices.getCategoricalData(categroyCrossCountsMap));
        response.getContinuousData().addAll(dataProcessingServices.getContinuousData(continuousCrossCountsMap));
        return response;
    }

    private Map<String, Map<String, Integer>> getCategroyCrossCountsMap(String requestSource, QueryRequest query, Query queryJson) {
        Map<String, Map<String, Integer>> categroyCrossCountsMap;
        if ((queryJson.categoryFilters != null && queryJson.categoryFilters.size() > 0) || (queryJson.requiredFields != null && queryJson.requiredFields.size() > 0)) {
            categroyCrossCountsMap = hpdsServices.getCrossCountsMap(query, ResultType.CATEGORICAL_CROSS_COUNT, requestSource);
        } else {
            categroyCrossCountsMap = new HashMap<>();
        }
        return categroyCrossCountsMap;
    }

    private Map<String, Map<String, Integer>> getContinuousCrossCount(String requestSource, QueryRequest query, Query queryJson) {
        Map<String, Map<String, Integer>> continuousCrossCountsMap;
        if ((queryJson.numericFilters != null && queryJson.numericFilters.size() > 0)) {
            continuousCrossCountsMap = hpdsServices.getCrossCountsMap(query, ResultType.CONTINUOUS_CROSS_COUNT, requestSource);
        } else {
            continuousCrossCountsMap = new HashMap<>();
        }
        return continuousCrossCountsMap;
    }

}
