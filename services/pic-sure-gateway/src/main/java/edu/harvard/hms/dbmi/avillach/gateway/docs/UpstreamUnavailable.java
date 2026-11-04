package edu.harvard.hms.dbmi.avillach.gateway.docs;

/** A service's document could not be fetched: connection failure, timeout, non-2xx, or a body that is not a JSON object. */
public class UpstreamUnavailable extends RuntimeException {

    private final String service;

    public UpstreamUnavailable(String service, String message) {
        super(service + ": " + message);
        this.service = service;
    }

    public UpstreamUnavailable(String service, Throwable cause) {
        super(service + ": " + cause.getMessage(), cause);
        this.service = service;
    }

    /** The registry name of the service that failed. */
    public String service() {
        return service;
    }
}
