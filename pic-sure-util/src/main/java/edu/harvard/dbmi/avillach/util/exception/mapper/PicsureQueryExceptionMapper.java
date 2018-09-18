package edu.harvard.dbmi.avillach.util.exception.mapper;

import edu.harvard.dbmi.avillach.util.exception.PicsureQueryException;
import edu.harvard.dbmi.avillach.util.response.PICSUREResponse;

import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;

public class PicsureQueryExceptionMapper implements ExceptionMapper<PicsureQueryException> {

    @Override
    public Response toResponse(PicsureQueryException exception) {
        return PICSUREResponse.riError(exception.getClass().getSimpleName() + " - " + exception.getMessage()
                + exception.getCause()==null?"":", cause: " + exception.getCause().getClass().getSimpleName() + " - " + exception.getCause().getMessage());
    }
}
