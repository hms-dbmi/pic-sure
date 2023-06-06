package edu.harvard.dbmi.avillach;

import javax.enterprise.context.RequestScoped;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;

@RequestScoped
public class HeaderContext {

    @Context
    private HttpHeaders headers;

    public HttpHeaders getHeaders() {
        return headers;
    }

}
