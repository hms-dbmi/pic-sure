package edu.harvard.hms.dbmi.avillach.resource.visualization.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Priority;
import javax.inject.Inject;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.ext.Provider;

@Provider
@Priority(1)
public class HeaderFilter implements ContainerRequestFilter {

    @Inject
    private RequestScopedHeader headers;

    private final Logger logger = LoggerFactory.getLogger(HeaderFilter.class);

    @Override
    public void filter(ContainerRequestContext containerRequestContext) {
        logger.info("HeaderFilter called with headers: " + containerRequestContext.getHeaders().toString());
        MultivaluedMap<String, String> httpHeaders = containerRequestContext.getHeaders();
        headers.setHeaders(httpHeaders);
    }
}
