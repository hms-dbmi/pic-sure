package edu.harvard.hms.dbmi.avillach.operations.error;

import org.springframework.http.HttpStatus;

import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;

/**
 * Shared factories for the {@link PicsureException} shapes this service raises. Entity-specific message text stays at the call site; only
 * the status, machine-readable error type, and common message template live here.
 */
public final class PicsureExceptions {

    private PicsureExceptions() {}

    /** 404 with the common "{@code <Entity> <identifier> not found}" message shape used across this service. */
    public static PicsureException notFound(String entity, Object identifier) {
        return new PicsureException(HttpStatus.NOT_FOUND, "not_found", entity + " " + identifier + " not found");
    }

    /** 409 with a caller-supplied message (conflict messages are entity-specific and do not share a template). */
    public static PicsureException conflict(String message) {
        return new PicsureException(HttpStatus.CONFLICT, "conflict", message);
    }

    /** 401 with a caller-supplied message. */
    public static PicsureException unauthorized(String message) {
        return new PicsureException(HttpStatus.UNAUTHORIZED, "unauthorized", message);
    }

    public static PicsureException badRequest(String message) {
        return new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", message);
    }
}
