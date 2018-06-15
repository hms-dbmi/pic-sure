package edu.harvard.dbmi.avillach.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.xml.bind.v2.runtime.output.MTOMXmlOutput;
import edu.harvard.dbmi.avillach.data.entity.Resource;
import edu.harvard.dbmi.avillach.data.repository.ResourceRepository;
import edu.harvard.dbmi.avillach.util.exception.ProtocolException;
import edu.harvard.dbmi.avillach.util.response.PICSUREResponse;
import edu.harvard.dbmi.avillach.utils.PicsureWarNaming;

import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Service handling business logic for CRUD on resources
 */
@RolesAllowed(PicsureWarNaming.RoleNaming.ROLE_SYSTEM)
@Path("/resource/")
public class PicsureResourceService {

    @Inject
    ResourceRepository resourceRepo;

    @GET
    @Path("get/{resourceId}")
    public Response getResourceByIdOrAll(
            @DefaultValue("all")
            @PathParam("resourceId") final String resourceId) {
        List<Resource> resources = null;
        if (("all").equalsIgnoreCase(resourceId)){
            resources = resourceRepo.list();
        } else {
            Resource resource = resourceRepo.getById(UUID.fromString(resourceId));
            if (resource == null)
                return PICSUREResponse.protocolError("Resource is not found by given resource ID: " + resourceId);
            else
                return PICSUREResponse.success(resource);
        }

        return PICSUREResponse.success(resources);
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("add")
    public Response addResource(List<Resource> resources){
        if (resources == null || resources.isEmpty())
            return PICSUREResponse.protocolError("No resource to be added.");

        int notAdded = 0;
        List<Resource> addedResources = new ArrayList<>();
        for (Resource resource : resources){
            resourceRepo.persist(resource);
            if (resourceRepo.getById(resource.getUuid()) == null){
                notAdded++;
                continue;
            }
            addedResources.add(resource);
        }

        if (notAdded > 0)
            return PICSUREResponse.applicationError(Integer.toString(notAdded) + " resources are NOT added." +
                    " Added resources are as follow: ", addedResources);

        return PICSUREResponse.success("All resources are added.", addedResources);
    }

    @GET
    @Path("remove/{resourceId}")
    public Response removeById(@PathParam("resourceId") final String resourceId) {
        UUID uuid = UUID.fromString(resourceId);
        Resource resource = resourceRepo.getById(uuid);
        if (resource == null)
            return PICSUREResponse.protocolError("Resource is not found by resource ID");

        resourceRepo.remove(resource);

        resource = resourceRepo.getById(uuid);
        if (resource != null){
            return PICSUREResponse.applicationError("Cannot delete the resource by id: " + resourceId);
        }

        return PICSUREResponse.success("Successfully deleted resource by id: " + resourceId);
    }

}
