package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.hms.dbmi.avillach.auth.data.entity.UserMetadataMapping;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.ConnectionRepository;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.UserMetadataMappingRepository;
import edu.harvard.hms.dbmi.avillach.auth.service.BaseEntityService;
import edu.harvard.hms.dbmi.avillach.auth.service.UserMetadataMappingService;

import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.transaction.Transactional;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.List;

import static edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming.AuthRoleNaming.ADMIN;
import static edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming.AuthRoleNaming.SUPER_ADMIN;

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
	
	@GET
	@Produces("application/json")
    @RolesAllowed({ADMIN, SUPER_ADMIN})
	public Response getAllMappings() {
		return Response.ok(mappingService.getAllMappings()).build();
	}

	@Transactional
	@POST
	@Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({SUPER_ADMIN})
	@Path("/")
	public Response addMapping(List<UserMetadataMapping> mappings) {
		return mappingService.addMappings(mappings);
	}

	@PUT
	@Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({SUPER_ADMIN})
	@Path("/")
	public Response updateMapping(List<UserMetadataMapping> mappings) {
		return updateEntity(mappings, mappingRepo);
	}

	@Transactional
	@DELETE
    @RolesAllowed({SUPER_ADMIN})
	@Path("/{mappingId}")
	public Response removeById(@PathParam("mappingId") final String mappingId) {
		return removeEntityById(mappingId, mappingRepo);
	}
}
