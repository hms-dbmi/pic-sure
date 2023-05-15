package edu.harvard.hms.dbmi.avillach.resource.visualization;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.domain.QueryFormat;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.dbmi.avillach.domain.ResourceInfo;
import edu.harvard.dbmi.avillach.service.IResourceRS;
import edu.harvard.hms.dbmi.avillach.resource.visualization.model.*;
import edu.harvard.hms.dbmi.avillach.resource.visualization.model.domain.*;
import edu.harvard.hms.dbmi.avillach.resource.visualization.service.DataProcessingProcessingService;
import edu.harvard.hms.dbmi.avillach.resource.visualization.service.HpdsService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;
import java.util.*;

@Path("/visualization")
@Produces({"application/json"})
@Consumes({"application/json"})
@JsonIgnoreProperties
@RequiredArgsConstructor
public class VisualizationResource implements IResourceRS {

    private final Logger logger = LoggerFactory.getLogger(VisualizationResource.class);

    @Inject
    private final @NonNull DataProcessingProcessingService dataProcessingService;
    @Inject
    private final @NonNull HpdsService hpdsService;

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${UUID}")
    private UUID uuid;

    @Override
    @POST
    @Path("/info")
    public ResourceInfo info(QueryRequest infoRequest) {
        ResourceInfo info = new ResourceInfo();
        info.setName("Pic-Sure Visualization Resource");
        info.setId(uuid);
        QueryFormat queryFormat = new QueryFormat();
        queryFormat.setName("Pic-Sure Query Format");
        info.getQueryFormats().add(queryFormat);
        queryFormat.setSpecification(Map.of(
                "numericFilters", "A map where each entry maps a field name to an object with min and/or max properties. Patients without a value between the min and max will not be included in the result set. Used to make Histograms.",
                "requiredFields", "A list of field names for which a patient must have a value in order to be included in the result set. Used to make Pie and Bar Charts.",
                "categoryFilters", "A map where each entry maps a field name to a list of values to be included in the result set. Used to make Pie and Bar Charts."
        ));
        return info;
    }

    @POST
    @Path("/query/sync")
    public Response getProcessedCrossCounts(QueryRequest query) {
        logger.debug("Received query:  \n" + query);
        Query queryJson;
        try {
            queryJson = (Query) query.getQuery();
        } catch (Exception e) {
            logger.error("Error parsing query:  \n" + query, e);
            return Response.status(Response.Status.BAD_REQUEST).entity("Error parsing query:  \n" + query).build();
        }
        Map<String, Map<String, Integer>> categroyCrossCountsMap;
        if ((queryJson.categoryFilters != null && queryJson.categoryFilters.size() > 0) || (queryJson.requiredFields != null && queryJson.requiredFields.size() > 0)) {
            categroyCrossCountsMap = hpdsService.getCrossCountsMap(query, ResultType.CATEGORICAL_CROSS_COUNT);
        } else {
            categroyCrossCountsMap = new HashMap<>();
        }
        Map<String, Map<String, Integer>> continuousCrossCountsMap;
        if ((queryJson.numericFilters != null && queryJson.numericFilters.size() > 0)) {
            continuousCrossCountsMap = hpdsService.getCrossCountsMap(query, ResultType.CONTINUOUS_CROSS_COUNT);
        } else {
            continuousCrossCountsMap = new HashMap<>();
        }
        if ((categroyCrossCountsMap == null || categroyCrossCountsMap.isEmpty()) && (continuousCrossCountsMap == null || continuousCrossCountsMap.isEmpty())) return Response.ok().build();
        ProcessedCrossCountsResponse response = new ProcessedCrossCountsResponse();
        response.getCategoricalData().addAll(dataProcessingService.getCategoricalData(query, categroyCrossCountsMap));
        response.getContinuousData().addAll(dataProcessingService.getContinuousData(query, continuousCrossCountsMap));
        return Response.ok(response).build();
    }

    @Override
    @POST
    @Path("/query/format")
    public Response queryFormat(QueryRequest resultRequest) {
        try {
            String queryAsString = mapper.readValue(mapper.writeValueAsString(resultRequest.getQuery()), Query.class).toString();
            return Response.ok("The user requested visualizations to be created with the following as the query: \n" + queryAsString).build();
        } catch (JsonProcessingException e) {
            return Response.serverError().entity("An error occurred formatting the query for display: " + e.getLocalizedMessage()).build();
        }
    }
}