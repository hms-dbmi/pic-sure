package edu.harvard.dbmi.avillach.exception.mapper;

import edu.harvard.dbmi.avillach.exception.ResourceInterfaceException;
import edu.harvard.dbmi.avillach.response.PICSUREResponse;

import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

@Provider
public class ResourceInterfaceMapper implements ExceptionMapper<ResourceInterfaceException>{
    @Override
    public Response toResponse(ResourceInterfaceException exception) {
        return PICSUREResponse.riError(exception.getMessage());
    }
}