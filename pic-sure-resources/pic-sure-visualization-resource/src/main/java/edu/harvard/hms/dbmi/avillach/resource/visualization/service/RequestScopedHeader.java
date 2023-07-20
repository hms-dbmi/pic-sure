package edu.harvard.hms.dbmi.avillach.resource.visualization.service;

import javax.enterprise.context.RequestScoped;
import javax.ws.rs.core.MultivaluedMap;

@RequestScoped
public class RequestScopedHeader {

    private MultivaluedMap<String, String> headers;

    public void setHeaders(MultivaluedMap<String, String> headers) {
        this.headers = headers;
    }

    public MultivaluedMap<String, String> getHeaders() {
        return headers;
    }

}
