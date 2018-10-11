package edu.harvard.hms.dbmi.avillach.auth.rest;

import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;

import edu.harvard.hms.dbmi.avillach.auth.data.entity.UserMetadataMapping;
import edu.harvard.hms.dbmi.avillach.auth.service.BaseEntityService;
import edu.harvard.hms.dbmi.avillach.auth.service.UserMetadataMappingService;

@Path("mapping")
public class UserMetadataMappingWebService  extends BaseEntityService<UserMetadataMapping>{
	
	public UserMetadataMappingWebService() {
		super(UserMetadataMapping.class);
	}

	@Inject
	UserMetadataMappingService mappingService;
	
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
	
	
}
