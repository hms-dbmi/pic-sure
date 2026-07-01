package edu.harvard.dbmi.avillach.util.exception.mapper;

import edu.harvard.dbmi.avillach.util.response.PICSUREResponse;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

@Provider
public class UnsupportedOperationExceptionMapper implements ExceptionMapper<UnsupportedOperationException>{

    @Override
    public Response toResponse(UnsupportedOperationException exception) {
        return PICSUREResponse.error(Response.Status.NOT_IMPLEMENTED, exception.getMessage(), MediaType.APPLICATION_JSON_TYPE);
    }
}
