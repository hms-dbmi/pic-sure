package edu.harvard.dbmi.avillach.exception;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;

/**
 * Will end up to return a protocol error
 *
 * @see edu.harvard.hms.dbmi.bd2k.irct.util.IRCTResponse
 */
public class ProtocolException extends WebApplicationException {

    private Object content;

    public ProtocolException() {
        super(Response.Status.INTERNAL_SERVER_ERROR);
    }

    public ProtocolException(Response.Status status) {
        super(status);
    }

    public ProtocolException(Object content) {
        super(Response.Status.INTERNAL_SERVER_ERROR);
        this.content = content;
    }

    public ProtocolException(Response.Status status, Object content) {
        super(status);
        this.content = content;
    }

    public Object getContent() {
        return content;
    }

    public void setContent(Object content) {
        this.content = content;
    }
}
