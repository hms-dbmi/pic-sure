package edu.harvard.dbmi.avillach.util.exception;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;

/**
 * Will end up to return an application error
 *
 * @see edu.harvard.dbmi.avillach.util.response.PICSUREResponse
 */
public class ApplicationException extends WebApplicationException{

    private Object content;

    public ApplicationException() {
        super(Response.Status.INTERNAL_SERVER_ERROR);
    }

    public ApplicationException(Response.Status status) {
        super(status);
    }

    public ApplicationException(Object content) {
        super(Response.Status.INTERNAL_SERVER_ERROR);
        this.content = content;
    }

    public ApplicationException(Response.Status status, Object content) {
        super(status);
        this.content = content;
    }

    public ApplicationException(String message, Exception exception) {
        super(message, exception);
    }


    public Object getContent() {
        return content;
    }

    public void setContent(Object content) {
        this.content = content;
    }
}
