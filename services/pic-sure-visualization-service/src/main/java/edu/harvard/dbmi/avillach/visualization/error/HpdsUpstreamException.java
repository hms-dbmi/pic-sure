package edu.harvard.dbmi.avillach.visualization.error;

/**
 * Indicates that the upstream data path through {@code pic-sure-hpds-query-service} failed. Mapped to 502 by
 * {@link GlobalExceptionHandler}; messages identify the failing query-service hop for operators.
 */
public class HpdsUpstreamException extends VisualizationException {

    public HpdsUpstreamException(String message, Throwable cause) {
        super(message, cause);
    }
}
