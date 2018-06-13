package edu.harvard.dbmi.avillach.util.exception.mapper;

import edu.harvard.dbmi.avillach.util.exception.ResourceInterfaceException;
import edu.harvard.dbmi.avillach.util.response.PICSUREResponse;

import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

@Provider
public class ResourceInterfaceExceptionMapper implements ExceptionMapper<ResourceInterfaceException>{
    @Override
    public Response toResponse(ResourceInterfaceException exception) {
        return PICSUREResponse.riError(exception.getMessage());
    }
}