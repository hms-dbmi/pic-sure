package edu.harvard.hms.dbmi.avillach.hpds.service.util;

/**
 * Thrown when a synchronous query is still {@code RUNNING} or {@code PENDING} after the configured wait deadline. The query itself is left
 * running; the caller should retry through the asynchronous {@code /query} plus {@code /query/{id}/status} endpoints.
 */
public class QueryTimeoutException extends RuntimeException {

    private final String resourceResultId;

    public QueryTimeoutException(String resourceResultId, String lastObservedStatus) {
        super("Query " + resourceResultId + " did not finish within the synchronous wait deadline; last status was " + lastObservedStatus);
        this.resourceResultId = resourceResultId;
    }

    public String getResourceResultId() {
        return resourceResultId;
    }
}
