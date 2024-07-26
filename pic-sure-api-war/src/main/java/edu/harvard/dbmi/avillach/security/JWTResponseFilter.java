package edu.harvard.dbmi.avillach.security;

import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.container.ContainerResponseFilter;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.ext.Provider;
import java.io.IOException;

@Provider
public class JWTResponseFilter implements ContainerResponseFilter {

    @Override
    public void filter(ContainerRequestContext containerRequestContext, ContainerResponseContext containerResponseContext)
        throws IOException {
        String newToken = (String) containerRequestContext.getProperty("refreshedToken");
        if (newToken != null) {
            containerResponseContext.getHeaders().add(HttpHeaders.AUTHORIZATION, "Bearer " + newToken);
        }
    }
}
