package edu.harvard.hms.dbmi.avillach.resource.visualization.filter;

import edu.harvard.hms.dbmi.avillach.resource.visualization.bean.RequestScopedHeader;

import javax.inject.Inject;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.ext.Provider;
import java.io.IOException;

@Provider
public class HeaderFilter implements ContainerRequestFilter {

    @Inject
    private RequestScopedHeader headers;

    @Override
    public void filter(ContainerRequestContext containerRequestContext) throws IOException {
        MultivaluedMap<String, String> httpHeaders = containerRequestContext.getHeaders();
        headers.setHeaders(httpHeaders);
    }
}
