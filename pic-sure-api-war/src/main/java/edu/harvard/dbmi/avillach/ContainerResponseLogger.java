package edu.harvard.dbmi.avillach;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.container.ContainerResponseFilter;
import javax.ws.rs.ext.Provider;

@Provider
public class ContainerResponseLogger implements ContainerResponseFilter {

    Logger logger = LoggerFactory.getLogger(ContainerResponseLogger.class);

    /**
     *  Build up a log message to log every request that comes through
     */
    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {

        //Capture request method and uri
        StringBuilder stringBuilder = new StringBuilder(requestContext.getMethod() + " at " + requestContext.getUriInfo().getRequestUri());

        //Get the username if it exists
        if (requestContext.getSecurityContext().getUserPrincipal() != null){
            stringBuilder.insert(0, requestContext.getSecurityContext().getUserPrincipal().getName() + " requested ");
        } else {
            stringBuilder.append(" requested ");
        }

        //Get the request body if it exists
        if (requestContext.getProperty("requestContent") != null){
            stringBuilder.append("\n" + requestContext.getProperty("requestContent"));

        }

        //What status code was returned
        stringBuilder.append("\n returned: " + responseContext.getStatus());

        logger.info(stringBuilder.toString());
    }
}
