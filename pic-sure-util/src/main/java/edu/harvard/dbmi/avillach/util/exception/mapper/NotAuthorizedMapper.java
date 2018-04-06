package edu.harvard.dbmi.avillach.util.exception.mapper;

import edu.harvard.dbmi.avillach.util.response.PICSUREResponse;

import javax.ws.rs.NotAuthorizedException;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

@Provider
public class NotAuthorizedMapper implements ExceptionMapper<NotAuthorizedException>{
    @Override
    public Response toResponse(NotAuthorizedException exception) {
        return PICSUREResponse.protocolError(Response.Status.UNAUTHORIZED,
                exception.getChallenges().toString());
    }
}
