package edu.harvard.hms.dbmi.avillach.commons.request;

/** Neutralises caller-controlled text before it is written to a log line, so it cannot forge extra log entries. */
public final class LogSanitizer {

    private LogSanitizer() {}

    /** Replaces CR, LF and other control characters with {@code _}. Null-safe: returns {@code "null"} for null. */
    public static String clean(Object value) {
        if (value == null) {
            return "null";
        }
        String s = value.toString();
        StringBuilder out = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isISOControl(c)) {
                if (out == null) {
                    out = new StringBuilder(s);
                }
                out.setCharAt(i, '_');
            }
        }
        return out == null ? s : out.toString();
    }
}
