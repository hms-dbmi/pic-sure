package edu.harvard.hms.dbmi.avillach.hpds.processing.v3.layout;

/**
 * Thrown when a layout store file ({@code pheno.index} or {@code pheno.blocks}) cannot be read: a bad magic, an unsupported version,
 * truncated or corrupt contents, a failed authentication, or a store the reader is not configured for.
 */
public class LayoutFormatException extends RuntimeException {

    /**
     * @param message what was wrong and where
     */
    public LayoutFormatException(String message) {
        super(message);
    }

    /**
     * @param message what was wrong and where
     * @param cause the underlying failure
     */
    public LayoutFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
