package edu.harvard.dbmi.avillach.visualization.error;

/**
 * The upstream data path failed. Mapped to 502 by {@link GlobalExceptionHandler}.
 *
 * <p>Since the query-service migration the immediate upstream is {@code pic-sure-hpds-query-service}, not HPDS itself -- this service no
 * longer calls HPDS directly. The name is retained because the condition it describes is unchanged: the data path behind this service did
 * not answer usefully. Messages and log lines name query-service so an operator reading a 502 sees the hop that actually failed.
 */
public class HpdsUpstreamException extends VisualizationException {

    public HpdsUpstreamException(String message, Throwable cause) {
        super(message, cause);
    }
}
