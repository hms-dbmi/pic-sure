package edu.harvard.hms.dbmi.avillach.query.routing;

/**
 * Why a Rust HPDS call gave no usable answer. In serve mode each value is a fallback reason, counted under its {@link #label()}; in shadow
 * mode the transport values say why a mirrored call was not compared.
 */
public enum RustFailure {
    /** The connection to Rust HPDS could not be opened. */
    CONNECT_ERROR("connect-error"),
    /** Rust HPDS did not answer within {@code HPDS_RS_TIMEOUT_MS}. */
    TIMEOUT("timeout"),
    /** The exchange failed for another I/O reason after it started. */
    IO_ERROR("io-error"),
    /** Rust HPDS answered with a 4xx status, such as a by-design refusal. */
    STATUS_4XX("status-4xx"),
    /** Rust HPDS answered with a 5xx status. */
    STATUS_5XX("status-5xx"),
    /** Rust HPDS answered with a status that is neither 2xx, 4xx nor 5xx. */
    STATUS_OTHER("status-other"),
    /** Rust HPDS answered 2xx with a body that could not be read as the expected response. */
    UNREADABLE_ANSWER("unreadable-answer");

    private final String label;

    RustFailure(String label) {
        this.label = label;
    }

    /** @return the name used in log lines and as the metric tag value */
    public String label() {
        return label;
    }

    /**
     * Maps a non-2xx status to its failure.
     *
     * @param status an HTTP status code that is not 2xx
     * @return {@link #STATUS_4XX}, {@link #STATUS_5XX} or {@link #STATUS_OTHER}
     */
    static RustFailure forStatus(int status) {
        if (status >= 400 && status < 500) {
            return STATUS_4XX;
        }
        return status >= 500 && status < 600 ? STATUS_5XX : STATUS_OTHER;
    }
}
