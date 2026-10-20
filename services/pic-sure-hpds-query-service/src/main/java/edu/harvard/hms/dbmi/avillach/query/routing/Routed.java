package edu.harvard.hms.dbmi.avillach.query.routing;

/**
 * A routed call's answer and the backend label it adds to the audit record.
 *
 * @param value the answer returned to the caller
 * @param hpdsBackend {@value HpdsRouter#BACKEND_RUST}, {@value HpdsRouter#BACKEND_JAVA_FALLBACK} or {@value HpdsRouter#BACKEND_JAVA_SHADOW}
 *        for a call that was routed or mirrored, null for a call that went to Java alone
 * @param <T> the answer type
 */
public record Routed<T>(T value, String hpdsBackend) {
}
