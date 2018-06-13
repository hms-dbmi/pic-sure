package edu.harvard.dbmi.avillach.util.exception.mapper;

import edu.harvard.dbmi.avillach.util.response.PICSUREResponse;
import org.hibernate.HibernateException;

import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

@Provider
public class IllegalArgumentExceptionMapper implements ExceptionMapper<IllegalArgumentException>{

    @Override
    public Response toResponse(IllegalArgumentException exception) {
        return PICSUREResponse.protocolError(exception.getMessage());
    }
}
