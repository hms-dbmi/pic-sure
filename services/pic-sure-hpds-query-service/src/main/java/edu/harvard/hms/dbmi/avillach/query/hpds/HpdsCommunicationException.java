package edu.harvard.hms.dbmi.avillach.query.hpds;

/** Thrown when the HPDS backend is unreachable, returns a non-2xx status, or answers a submit without accepting it. Mapped to 502. */
public class HpdsCommunicationException extends RuntimeException {

    public HpdsCommunicationException(String message) {
        super(message);
    }

    public HpdsCommunicationException(String message, Throwable cause) {
        super(message, cause);
    }
}
