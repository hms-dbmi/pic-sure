package edu.harvard.hms.dbmi.avillach.query.routing;

/** What one Rust HPDS call produced: an HTTP answer of any status, or a failure that left no usable answer. */
public sealed interface RustOutcome {

    /**
     * Rust HPDS answered.
     *
     * @param status the HTTP status
     * @param body the response body, empty when there was none
     * @param queryMetadata the {@code queryMetadata} response header, or null
     */
    record Answered(int status, byte[] body, String queryMetadata) implements RustOutcome {

        /** @return whether the status is 2xx */
        public boolean successful() {
            return status >= 200 && status < 300;
        }
    }

    /**
     * Rust HPDS gave no HTTP answer.
     *
     * @param reason why: {@link RustFailure#CONNECT_ERROR}, {@link RustFailure#TIMEOUT} or {@link RustFailure#IO_ERROR}, or, for a 2xx body
     *        the caller's model cannot read, {@link RustFailure#UNREADABLE_ANSWER}
     */
    record Failed(RustFailure reason) implements RustOutcome {
    }
}
