package edu.harvard.hms.dbmi.avillach.query.logging;

/**
 * Makes request and stored values safe to place in a log line. A value that came from a caller or from a stored query row could otherwise
 * carry line breaks or other control characters and start a fake log entry.
 */
public final class LogValues {

    /** Longest value written to a log line; anything past it is cut and marked with {@link #TRUNCATION_MARKER}. */
    static final int MAX_LENGTH = 200;
    static final String TRUNCATION_MARKER = "...";

    private LogValues() {}

    /**
     * Returns the value's string form with every ISO control character (including CR, LF and tab) replaced by {@code _}, cut to
     * {@link #MAX_LENGTH} characters.
     *
     * @param value any value; {@code null} is returned as the string {@code "null"}
     * @return a single-line string safe to log
     */
    public static String of(Object value) {
        String text = String.valueOf(value);
        StringBuilder safe = new StringBuilder(Math.min(text.length(), MAX_LENGTH + TRUNCATION_MARKER.length()));
        for (int i = 0; i < text.length() && i < MAX_LENGTH; i++) {
            char c = text.charAt(i);
            safe.append(Character.isISOControl(c) ? '_' : c);
        }
        if (text.length() > MAX_LENGTH) {
            safe.append(TRUNCATION_MARKER);
        }
        return safe.toString();
    }

    /**
     * Describes an exception for a log line without its stack trace or raw message: the exception's class name and its message passed
     * through {@link #of}. Parser exceptions quote the input they failed on, so the raw message can carry caller or stored content.
     *
     * @param e the exception to describe
     * @return {@code "<SimpleClassName>: <safe message>"}
     */
    public static String of(Throwable e) {
        return e.getClass().getSimpleName() + ": " + of((Object) e.getMessage());
    }
}
