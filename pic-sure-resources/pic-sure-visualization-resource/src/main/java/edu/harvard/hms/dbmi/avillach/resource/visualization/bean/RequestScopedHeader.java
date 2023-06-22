package edu.harvard.hms.dbmi.avillach.resource.visualization.bean;

import org.springframework.web.context.annotation.RequestScope;

import javax.ws.rs.core.MultivaluedMap;

@RequestScope
public class RequestScopedHeader {

    private MultivaluedMap<String, String> headers;

    public void setHeaders(MultivaluedMap<String, String> headers) {
        this.headers = headers;
    }

    public MultivaluedMap<String, String> getHeaders() {
        return headers;
    }

}
