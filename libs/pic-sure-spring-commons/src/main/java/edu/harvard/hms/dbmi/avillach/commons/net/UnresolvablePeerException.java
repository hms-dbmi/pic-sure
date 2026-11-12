package edu.harvard.hms.dbmi.avillach.commons.net;

/** The connection peer supplied by the servlet container is missing or is not an IP literal. */
public final class UnresolvablePeerException extends IllegalArgumentException {

    public UnresolvablePeerException(String message) {
        super(message);
    }

    public UnresolvablePeerException(String message, Throwable cause) {
        super(message, cause);
    }
}
