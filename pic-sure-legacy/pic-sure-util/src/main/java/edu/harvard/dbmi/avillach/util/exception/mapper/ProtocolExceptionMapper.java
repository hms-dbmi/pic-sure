package edu.harvard.dbmi.avillach.util.exception.mapper;

import edu.harvard.dbmi.avillach.util.exception.ProtocolException;
import edu.harvard.dbmi.avillach.util.response.PICSUREResponse;

import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

@Provider
public class ProtocolExceptionMapper implements ExceptionMapper<ProtocolException>{

    @Override
    public Response toResponse(ProtocolException exception) {
        return PICSUREResponse.protocolError(exception.getStatus(), exception.getContent());
    }
}
