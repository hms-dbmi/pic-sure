package edu.harvard.dbmi.avillach.exception.mapper;

import edu.harvard.dbmi.avillach.exception.ProtocolException;
import edu.harvard.dbmi.avillach.response.PICSUREResponse;

import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

@Provider
public class ProtocolMapper implements ExceptionMapper<ProtocolException>{

    @Override
    public Response toResponse(ProtocolException exception) {
        return PICSUREResponse.protocolError(exception.getResponse().getStatus(), exception.getContent());
    }
}
