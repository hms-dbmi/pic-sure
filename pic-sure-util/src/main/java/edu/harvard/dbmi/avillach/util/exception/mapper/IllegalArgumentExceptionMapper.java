package edu.harvard.dbmi.avillach.util.exception.mapper;

import edu.harvard.dbmi.avillach.util.response.PICSUREResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

@Provider
public class IllegalArgumentExceptionMapper implements ExceptionMapper<IllegalArgumentException>{

    private static final Logger logger = LoggerFactory.getLogger(IllegalArgumentExceptionMapper.class);

    @Override
    public Response toResponse(IllegalArgumentException exception) {
        logger.error("Uncaught exception", exception);
        return PICSUREResponse.protocolError(exception.getMessage());
    }
}
