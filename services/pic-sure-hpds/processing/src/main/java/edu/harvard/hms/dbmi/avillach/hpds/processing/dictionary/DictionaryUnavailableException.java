package edu.harvard.hms.dbmi.avillach.hpds.processing.dictionary;

/**
 * Signals that concept details could not be fetched from the dictionary service, because no dictionary URL is configured or because a call
 * to it failed. An export that needs concept details fails with this exception instead of writing blank dictionary columns.
 */
public class DictionaryUnavailableException extends RuntimeException {

    /**
     * Creates the exception with no underlying cause.
     *
     * @param message what was unavailable, naming the property or URL involved
     */
    public DictionaryUnavailableException(String message) {
        super(message);
    }

    /**
     * Creates the exception for a failed call.
     *
     * @param message what was unavailable, naming the URL that was called
     * @param cause the failure raised by the HTTP client
     */
    public DictionaryUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
