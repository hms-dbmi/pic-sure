package edu.harvard.dbmi.avillach;

import java.util.Map;

import javax.ejb.EJBAccessException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

@Provider
public class EJBAccessExceptionMapper implements ExceptionMapper<EJBAccessException>{

	@Override
	public Response toResponse(EJBAccessException arg0) {
		return Response.status(401).type(MediaType.APPLICATION_JSON).entity(Map.of("message", 
				"User does not have sufficient privileges."))
				.build();
	}

}

