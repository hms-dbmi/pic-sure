package edu.harvard.hms.dbmi.avillach.resource.visualization;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.domain.QueryFormat;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.dbmi.avillach.domain.ResourceInfo;
import edu.harvard.dbmi.avillach.service.IResourceRS;
import edu.harvard.hms.dbmi.avillach.resource.visualization.service.RequestScopedHeader;
import edu.harvard.hms.dbmi.avillach.resource.visualization.model.domain.Query;
import edu.harvard.hms.dbmi.avillach.resource.visualization.service.VisualizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ejb.Stateless;
import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;
import java.util.Map;

@Path("/visualization")
@Produces({"application/json"})
@Consumes({"application/json"})
@JsonIgnoreProperties
@Stateless
public class VisualizationResource implements IResourceRS {

    private final Logger logger = LoggerFactory.getLogger(VisualizationResource.class);

    @Inject
    VisualizationService visualizationService;

    @Inject
    ApplicationProperties properties;

    @Inject
    RequestScopedHeader requestScopedHeader;

    private final ObjectMapper mapper = new ObjectMapper();

    VisualizationResource() {
        if (properties == null) {
            properties = new ApplicationProperties();
            logger.info("Initializing properties");
        }
        properties.init("pic-sure-visualization-resource");
        logger.info("VisualizationResource initialized ->", properties.getOrigin());
    }

    @Override
    @POST
    @Path("/info")
    public ResourceInfo info(QueryRequest infoRequest) {
        ResourceInfo info = new ResourceInfo();
        info.setName("Pic-Sure Visualization Resource");
        info.setId(properties.getVisualizationResourceId());
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

    @Override
    @POST
    @Path("/query/sync")
    public Response querySync(QueryRequest query) {
        String requestSource = null;
        if (requestScopedHeader != null && requestScopedHeader.getHeaders() != null) {
            requestSource = requestScopedHeader.getHeaders().get("request-source").get(0);
        }

        String queryRequest = "";
        try {
            queryRequest = mapper.writeValueAsString(query);
        } catch (JsonProcessingException e) {
            logger.error("Unable to serialize query request", e);
        }

        logger.info("resource=visualization /query/sync requestSource=" + requestSource + " query=" + queryRequest);
        return visualizationService.handleQuerySync(query, requestSource);
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

    @Override
    @POST
    @Path("/bin/continuous")
    public Response generateContinuousBin(QueryRequest continuousData) {
    	return visualizationService.generateContinuousBin(continuousData);
    }
}