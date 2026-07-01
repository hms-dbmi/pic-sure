package edu.harvard.dbmi.avillach.util.exception;

import com.fasterxml.jackson.annotation.JsonIgnore;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;

/**
 * Will end up to return a protocol error
 *
 * Common errors are included as public final strings
 *
 * @see edu.harvard.dbmi.avillach.util.response.PICSUREResponse
 */
public class ProtocolException extends WebApplicationException {

    public final static String MISSING_RESOURCE_ID = "Missing resource id";
    public final static String RESOURCE_NOT_FOUND = "No resource with id: ";
    public final static String MISSING_DATA = "Missing query request data";
    public final static String MISSING_QUERY_ID = "Missing query id";
    public final static String QUERY_NOT_FOUND = "No query with id: ";
    public static final String INCORRECTLY_FORMATTED_REQUEST = "Incorrectly formatted query request data";

    @JsonIgnore
    private Response.Status status;

    private Object content;

    public ProtocolException() {
        this.status = Response.Status.INTERNAL_SERVER_ERROR;
    }

    public ProtocolException(Response.Status status) {
        this.status = status;
    }

    public ProtocolException(Object content) {
        super(Response.Status.INTERNAL_SERVER_ERROR);
        this.content = content;
    }

    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }

    public ProtocolException(Response.Status status, Object content) {
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
