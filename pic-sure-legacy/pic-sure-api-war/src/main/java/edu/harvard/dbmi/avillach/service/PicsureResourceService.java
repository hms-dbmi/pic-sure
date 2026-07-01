package edu.harvard.dbmi.avillach.service;

import java.util.List;

import javax.inject.Inject;
import javax.transaction.Transactional;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.harvard.dbmi.avillach.data.entity.Resource;
import edu.harvard.dbmi.avillach.data.repository.ResourceRepository;
import edu.harvard.dbmi.avillach.util.response.PICSUREResponse;

/**
 * Service handling business logic for CRUD on resources
 */
@Path("/resource")
public class PicsureResourceService extends PicsureBaseEntityService<Resource> {

    Logger logger = LoggerFactory.getLogger(PicsureResourceService.class);

    @Inject
    ResourceRepository resourceRepo;

    public PicsureResourceService() {
        super(Resource.class);
    }

    @GET
    @Path("/{resourceId}")
    public Response getEntityById(@PathParam("resourceId") String resourceId) {
        return getEntityById(resourceId, resourceRepo);
    }

    @GET
    @Path("")
    public Response getResourceAll() {
        logger.info("Getting all resources...");
        List<Resource> resources = null;

        resources = resourceRepo.list();

        if (resources == null) return PICSUREResponse.applicationError("Error occurs when listing all resources.");

        return PICSUREResponse.success(resources);
    }
}
