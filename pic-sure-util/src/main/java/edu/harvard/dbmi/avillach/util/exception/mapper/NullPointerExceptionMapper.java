package edu.harvard.dbmi.avillach.util.exception.mapper;

import edu.harvard.dbmi.avillach.util.response.PICSUREResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

@Provider
public class NullPointerExceptionMapper implements ExceptionMapper<NullPointerException>{

    private static final Logger logger = LoggerFactory.getLogger(NullPointerExceptionMapper.class);

    @Override
    public Response toResponse(NullPointerException exception) {
        logger.error("Uncaught exception", exception);
        return PICSUREResponse.applicationError("An inner problem pops up, no worry, please contact your admin to see the logs in server");
    }
}
