package edu.harvard.dbmi.avillach.visualization.error;

import org.springframework.web.client.HttpStatusCodeException;

/**
 * Indicates that the upstream data path through {@code pic-sure-hpds-query-service} failed. Mapped to 502 by
 * {@link GlobalExceptionHandler}, which returns the message as the response body.
 *
 * <p>The messages are client-visible sentences owned by this class, one per factory method. They never carry the query-service address or
 * the message of the underlying exception, and the only upstream values in them are the status code and status text of an error response.
 * Operator detail lives in the server log and on the cause.
 */
public class HpdsUpstreamException extends VisualizationException {

    private static final String UNREACHABLE = "Could not reach the query service";

    private static final String REQUEST_FAILED = "Query service request failed";

    /**
     * Not public, so code outside this package builds instances through the factory methods and cannot introduce another wording.
     *
     * @param message the client-visible message
     * @param cause the underlying failure
     */
    HpdsUpstreamException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Builds the exception for an I/O failure on the query-service call, such as a refused connection or a timeout.
     *
     * @param cause the I/O failure, kept as the cause
     * @return an exception whose message is the fixed sentence "Could not reach the query service"
     */
    public static HpdsUpstreamException unreachable(Throwable cause) {
        return new HpdsUpstreamException(UNREACHABLE, cause);
    }

    /**
     * Builds the exception for a query-service call that failed for any reason other than an HTTP error status or an I/O failure, such as a
     * response body that cannot be read.
     *
     * @param cause the underlying failure, kept as the cause
     * @return an exception whose message is the fixed sentence "Query service request failed"
     */
    public static HpdsUpstreamException requestFailed(Throwable cause) {
        return new HpdsUpstreamException(REQUEST_FAILED, cause);
    }

    /**
     * Builds the exception for a query-service call that was answered with an HTTP error status.
     *
     * @param cause the error response, kept as the cause
     * @return an exception whose message is "Query service request failed with status N: T", where N and T are the status code and status
     *         text of the response
     */
    public static HpdsUpstreamException failedWithStatus(HttpStatusCodeException cause) {
        return new HpdsUpstreamException(
            REQUEST_FAILED + " with status " + cause.getStatusCode().value() + ": " + cause.getStatusText(), cause
        );
    }
}
