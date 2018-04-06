package edu.harvard.dbmi.avillach.exception.mapper;

import edu.harvard.dbmi.avillach.exception.ApplicationException;
import edu.harvard.dbmi.avillach.response.PICSUREResponse;

import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

@Provider
public class ApplicationMapper implements ExceptionMapper<ApplicationException>{

    @Override
    public Response toResponse(ApplicationException exception) {
        return PICSUREResponse.applicationError(exception.getContent());
    }
}
