package edu.harvard.hms.dbmi.avillach.ai.error;

import java.util.ArrayList;
import java.util.List;

/**
 * One log-line description of a failure in place of a stack trace: each exception in the cause chain, and the innermost frame of this
 * service's own code, e.g. {@code ModelUnavailableException: Bedrock Converse call failed: Read timed out <- SocketTimeoutException @
 * ConverseSdkModelClient.send:54}. A cause's message is left out when the exception wrapping it already repeats it.
 */
public final class ErrorSummary {

    private static final String APP_PACKAGE = "edu.harvard.hms.dbmi.avillach.";
    private static final int MAX_CHAIN = 5;
    private static final int MAX_MESSAGE_CHARS = 300;

    private ErrorSummary() {}

    /**
     * @param error the failure to describe; its cause chain is followed up to five levels
     * @return a single line with no line breaks, safe to append to a log message
     */
    public static String of(Throwable error) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable t = error; t != null && chain.size() < MAX_CHAIN && !chain.contains(t); t = t.getCause()) {
            chain.add(t);
        }
        StringBuilder summary = new StringBuilder();
        String outerMessage = "";
        for (Throwable t : chain) {
            if (!summary.isEmpty()) {
                summary.append(" <- ");
            }
            summary.append(t.getClass().getSimpleName());
            String message = oneLine(t.getMessage());
            if (!message.isEmpty() && !outerMessage.contains(message)) {
                summary.append(": ").append(message.length() > MAX_MESSAGE_CHARS ? message.substring(0, MAX_MESSAGE_CHARS) + "..." : message);
            }
            outerMessage = message;
        }
        String where = innermostAppFrame(chain);
        return where == null ? summary.toString() : summary + " @ " + where;
    }

    /** The root cause's stack still holds the service frames that called into the library that failed, so search it first. */
    private static String innermostAppFrame(List<Throwable> chain) {
        for (Throwable t : chain.reversed()) {
            for (StackTraceElement frame : t.getStackTrace()) {
                if (frame.getClassName().startsWith(APP_PACKAGE)) {
                    String className = frame.getClassName().substring(frame.getClassName().lastIndexOf('.') + 1);
                    return className + "." + frame.getMethodName() + ":" + frame.getLineNumber();
                }
            }
        }
        return null;
    }

    private static String oneLine(String message) {
        return message == null ? "" : message.replaceAll("\\s+", " ").strip();
    }
}
