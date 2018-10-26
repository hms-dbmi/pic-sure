package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.hms.dbmi.avillach.auth.data.entity.UserMetadataMapping;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.UserMetadataMappingRepository;
import edu.harvard.hms.dbmi.avillach.auth.service.BaseEntityService;
import edu.harvard.hms.dbmi.avillach.auth.service.UserMetadataMappingService;

import javax.inject.Inject;
import javax.transaction.Transactional;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.List;

@Path("mapping")
public class UserMetadataMappingWebService  extends BaseEntityService<UserMetadataMapping>{
	
	public UserMetadataMappingWebService() {
		super(UserMetadataMapping.class);
	}

	@Inject
	UserMetadataMappingService mappingService;

	@Inject
	UserMetadataMappingRepository mappingRepo;

	
	@Path("{connectionId}")
	@GET
	@Produces("application/json")
	public Response getMappingsForConnection(@PathParam("connectionId") String connection) {
		return Response.ok(mappingService.getAllMappingsForConnection(connection)).build();
	}
	
	@GET
	@Produces("application/json")
	public Response getAllMappings() {
		return Response.ok(mappingService.getAllMappings()).build();
	}

	@Transactional
	@POST
	@Consumes(MediaType.APPLICATION_JSON)
	@Path("/")
	public Response addMapping(List<UserMetadataMapping> mappings) {
		return mappingService.addMappings(mappings);
	}

	@PUT
	@Consumes(MediaType.APPLICATION_JSON)
	@Path("/")
	public Response updateMapping(List<UserMetadataMapping> mappings) {
		return updateEntity(mappings, mappingRepo);
	}

	@Transactional
	@DELETE
	@Path("/{mappingId}")
	public Response removeById(@PathParam("mappingId") final String mappingId) {
		return removeEntityById(mappingId, mappingRepo);
	}
}
