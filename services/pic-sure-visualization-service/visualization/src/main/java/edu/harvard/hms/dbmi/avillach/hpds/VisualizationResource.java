package edu.harvard.hms.dbmi.avillach.hpds;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.hpds.model.*;
import edu.harvard.hms.dbmi.avillach.hpds.model.domain.*;
import edu.harvard.hms.dbmi.avillach.hpds.service.ChartService;
import edu.harvard.hms.dbmi.avillach.hpds.service.DataService;
import edu.harvard.hms.dbmi.avillach.hpds.service.VisualizationService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;

import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;
import java.util.*;

@Path("/pic-sure")
@Produces({"application/json"})
@Consumes({"application/json"})
@RequiredArgsConstructor
@JsonIgnoreProperties
public class VisualizationResource implements IResourceRS {

    private final Logger logger = LoggerFactory.getLogger(VisualizationResource.class);

    @Inject
    private final @NonNull ChartService chartService;
    @Inject
    private final @NonNull DataService dataService;
    @Inject
    private final @NonNull VisualizationService visualizationService;

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
                "requiredFields", "A list of field names for which a patient must have a value in order to be included in the result set. Used to make Pie Charts",
                "categoryFilters", "A map where each entry maps a field name to a list of values to be included in the result set. Used to make Pie Charts."
        ));
        return info;
    }

    @POST
    @Path("/query/sync")
    public Response getVisualizations(QueryRequest queryJson) {
        logger.info("Received query:  \n" + queryJson);
        List<CategoricalData> categoricalData = dataService.getCategoricalData(queryJson);
        List<ContinuousData> continuousData = dataService.getContinuousData(queryJson);
        VisualizationResponse response = new VisualizationResponse();
        for(CategoricalData data: categoricalData) {
            String image = visualizationService.createBase64PNG(
                    chartService.createPieChart(data)
            );
            response.getImages().add(new VisualizationImage(image, "pie chart", data.getTitle()));
        }
        for(ContinuousData data: continuousData) {
            String image = visualizationService.createBase64PNG(
                    chartService.createHistogram(data)
            );
            response.getImages().add(new VisualizationImage(image, "histogram", data.getTitle()));
        }
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
