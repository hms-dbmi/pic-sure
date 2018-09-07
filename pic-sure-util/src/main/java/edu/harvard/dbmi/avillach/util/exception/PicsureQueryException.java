package edu.harvard.dbmi.avillach.util.exception;

import javax.ws.rs.WebApplicationException;

public class PicsureQueryException extends WebApplicationException {
    private Object content;

    public PicsureQueryException() {
    }

    public PicsureQueryException(Object content) {
        this.content = content;
    }

    /**
     * Create a Resource Interface Exception with the given message
     *
     * @param message Messsage
     */
    public PicsureQueryException(String message) {
        super(message);
    }

    /**
     * Create a Resource Interface Exception
     *
     * @param exception Exception
     */
    public PicsureQueryException(Exception exception) {
        super(exception);
    }

    /**
     * Create a Resource Interface Exception with the given message
     *
     * @param message Message
     * @param exception Exception
     */
    public PicsureQueryException(String message, Exception exception) {
        super(message, exception);
    }

    public Object getContent() {
        return content;
    }

    public void setContent(Object content) {
        this.content = content;
    }
}
