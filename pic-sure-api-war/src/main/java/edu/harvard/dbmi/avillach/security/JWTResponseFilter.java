package edu.harvard.dbmi.avillach.security;

import com.fasterxml.jackson.databind.node.TextNode;

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
        Object tokenObject = containerRequestContext.getProperty("refreshedToken");

        if (tokenObject instanceof TextNode) {
            String newToken = ((TextNode) tokenObject).asText();
            containerResponseContext.getHeaders().add(HttpHeaders.AUTHORIZATION, "Bearer " + newToken);
        } else if (tokenObject instanceof String) {
            String newToken = (String) tokenObject;
            containerResponseContext.getHeaders().add(HttpHeaders.AUTHORIZATION, "Bearer " + newToken);
        }
    }
}
