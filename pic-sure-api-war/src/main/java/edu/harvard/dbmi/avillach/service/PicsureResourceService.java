package edu.harvard.dbmi.avillach.service;

import edu.harvard.dbmi.avillach.data.entity.Resource;
import edu.harvard.dbmi.avillach.data.repository.ResourceRepository;
import edu.harvard.dbmi.avillach.util.response.PICSUREResponse;
import edu.harvard.dbmi.avillach.utils.PicsureWarNaming;

import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.ArrayList;
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
        List<Resource> resources;
        if (("all").equalsIgnoreCase(resourceId)){
            resources = resourceRepo.list();
        } else {
            resources = new ArrayList<>();
            resources.add(resourceRepo.getById(resourceId));
        }

        if (resources == null || resources.isEmpty())
            return PICSUREResponse.protocolError("Resource is not found by resource ID");


        return PICSUREResponse.success(resources);
    }

    /**
     * for a list of specific ids
     * @param ids
     * @return
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("get")
    public Response getResourcesByIds(List<UUID> ids) {
        List<Resource> resources;
        resources = resourceRepo.listByIDs((UUID[])ids.toArray());

        if (resources == null || resources.isEmpty())
            return PICSUREResponse.protocolError("Resource is not found by resource ID");

        return PICSUREResponse.success(resources);
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

        return PICSUREResponse.success("Successfully delted resource by id: " + resourceId);
    }

}
