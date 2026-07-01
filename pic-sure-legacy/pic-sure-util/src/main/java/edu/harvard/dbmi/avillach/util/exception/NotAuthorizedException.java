package edu.harvard.dbmi.avillach.util.exception;

import com.fasterxml.jackson.annotation.JsonIgnore;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;

public class NotAuthorizedException extends WebApplicationException {

    public static final String MISSING_CREDENTIALS = "Missing credentials";

    @JsonIgnore
    private Response.Status status;

    private Object content;

    public NotAuthorizedException() {
        this.status = Response.Status.UNAUTHORIZED;
    }

    public NotAuthorizedException(Response.Status status) {
        this.status = status;
    }

    public NotAuthorizedException(Object content) {
        super(Response.Status.UNAUTHORIZED);
        this.content = content;
    }

    public NotAuthorizedException(String message, Throwable cause) {
        super(message, cause);
    }

    public NotAuthorizedException(Response.Status status, Object content) {
        this.status = status;
        this.content = content;
    }

    public Response.Status getStatus() {
        return status;
    }

    public Object getContent() {
        return content;
    }

    public void setContent(Object content) {
        this.content = content;
    }
}
