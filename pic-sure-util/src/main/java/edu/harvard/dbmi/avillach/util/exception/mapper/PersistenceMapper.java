package edu.harvard.dbmi.avillach.util.exception.mapper;

import edu.harvard.dbmi.avillach.util.response.PICSUREResponse;

import javax.persistence.PersistenceException;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

@Provider
public class PersistenceMapper implements ExceptionMapper<PersistenceException>{

    @Override
    public Response toResponse(PersistenceException exception) {
        return PICSUREResponse.applicationError(exception.getMessage());
    }
}
