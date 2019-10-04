package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.hms.dbmi.avillach.auth.data.entity.UserMetadataMapping;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.ConnectionRepository;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.UserMetadataMappingRepository;
import edu.harvard.hms.dbmi.avillach.auth.service.BaseEntityService;
import edu.harvard.hms.dbmi.avillach.auth.service.UserMetadataMappingService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;

import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.transaction.Transactional;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.List;

import static edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming.AuthRoleNaming.ADMIN;
import static edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming.AuthRoleNaming.SUPER_ADMIN;

@Api
@Path("mapping")
public class UserMetadataMappingWebService  extends BaseEntityService<UserMetadataMapping>{
	
	public UserMetadataMappingWebService() {
		super(UserMetadataMapping.class);
	}

	@Inject
	UserMetadataMappingService mappingService;

	@Inject
	UserMetadataMappingRepository mappingRepo;

	@Inject
	ConnectionRepository connectionRepo;

	@ApiOperation(value = "GET information of one UserMetadataMapping with the UUID, requires ADMIN or SUPER_ADMIN role")
	@GET
	@Produces("application/json")
    @RolesAllowed({ADMIN, SUPER_ADMIN})
	@Path("{connectionId}")
	public Response getMappingsForConnection(@PathParam("connectionId") String connection) {
		return Response.ok(mappingService.
				getAllMappingsForConnection(connectionRepo
						.getUniqueResultByColumn("id", connection)))
				.build();
	}

    @ApiOperation(value = "GET a list of existing UserMetadataMappings, requires ADMIN or SUPER_ADMIN role")
    @GET
	@Produces("application/json")
    @RolesAllowed({ADMIN, SUPER_ADMIN})
	public Response getAllMappings() {
		return Response.ok(mappingService.getAllMappings()).build();
	}

    @ApiOperation(value = "POST a list of UserMetadataMappings, requires SUPER_ADMIN role")
    @Transactional
	@POST
	@Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({SUPER_ADMIN})
	@Path("/")
	public Response addMapping(
            @ApiParam(required = true, value = "A list of UserMetadataMapping in JSON format")
            List<UserMetadataMapping> mappings) {
		return mappingService.addMappings(mappings);
	}

    @ApiOperation(value = "Update a list of UserMetadataMappings, will only update the fields listed, requires SUPER_ADMIN role")
    @PUT
	@Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({SUPER_ADMIN})
	@Path("/")
	public Response updateMapping(
            @ApiParam(required = true, value = "A list of UserMetadataMapping with fields to be updated in JSON format")
            List<UserMetadataMapping> mappings) {
		return updateEntity(mappings, mappingRepo);
	}

    @ApiOperation(value = "DELETE an UserMetadataMapping by Id only if the UserMetadataMapping is not associated by others, requires SUPER_ADMIN role")
    @Transactional
	@DELETE
    @RolesAllowed({SUPER_ADMIN})
	@Path("/{mappingId}")
	public Response removeById(
            @ApiParam(required = true, value = "A valid UserMetadataMapping Id")
            @PathParam("mappingId") final String mappingId) {
		return removeEntityById(mappingId, mappingRepo);
	}
}
