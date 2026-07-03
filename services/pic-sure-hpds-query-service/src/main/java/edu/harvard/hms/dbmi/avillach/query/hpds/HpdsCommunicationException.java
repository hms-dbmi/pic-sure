package edu.harvard.hms.dbmi.avillach.query.hpds;

/** Thrown when the HPDS backend is unreachable or returns a non-2xx status. Mapped to 502 (Task 17). */
public class HpdsCommunicationException extends RuntimeException {

    public HpdsCommunicationException(String message) {
        super(message);
    }

    public HpdsCommunicationException(String message, Throwable cause) {
        super(message, cause);
    }
}
