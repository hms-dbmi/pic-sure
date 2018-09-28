package edu.harvard.dbmi.avillach.util.exception.mapper;

import edu.harvard.dbmi.avillach.util.response.PICSUREResponse;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

@Provider
public class NullPointerExceptionMapper implements ExceptionMapper<NullPointerException>{

    @Override
    public Response toResponse(NullPointerException exception) {
        exception.printStackTrace();
        return PICSUREResponse.applicationError("An inner problem pops up, no worry, please contact your admin to see the logs in server");
    }
}
