package edu.harvard.dbmi.avillach.util.exception.mapper;

import edu.harvard.dbmi.avillach.util.response.PICSUREResponse;
import org.hibernate.HibernateException;

import javax.persistence.PersistenceException;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

@Provider
public class HibernateExceptionMapper implements ExceptionMapper<HibernateException>{

    @Override
    public Response toResponse(HibernateException exception) {
        return PICSUREResponse.applicationError(exception.getMessage());
    }
}
