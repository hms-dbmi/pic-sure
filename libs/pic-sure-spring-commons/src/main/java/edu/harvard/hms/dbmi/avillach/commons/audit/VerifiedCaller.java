package edu.harvard.hms.dbmi.avillach.commons.audit;

import java.util.Optional;

import jakarta.servlet.ServletRequest;

/**
 * Request attribute holding a caller type that a credential-checking filter has verified. Only a filter that has proven where the request
 * came from should call {@link #set(ServletRequest, String)}. An {@link AuditLoggingFilter} built with {@code verifiedCallerOnly} records
 * this value as the audit event's {@code caller} and never trusts the client-supplied {@code X-Client-Type} header for it.
 */
public final class VerifiedCaller {

    /** Name of the request attribute that carries the verified caller type. */
    public static final String ATTRIBUTE = VerifiedCaller.class.getName();

    private VerifiedCaller() {}

    /**
     * Marks the request as coming from a verified caller.
     *
     * @param request the request being served
     * @param caller the caller type the credential check proved, such as {@code MCP_SERVER}
     * @throws IllegalArgumentException if {@code caller} is null or blank
     */
    public static void set(ServletRequest request, String caller) {
        if (caller == null || caller.isBlank()) {
            throw new IllegalArgumentException("A verified caller must not be blank");
        }
        request.setAttribute(ATTRIBUTE, caller);
    }

    /**
     * Reads the verified caller type from the request.
     *
     * @param request the request being served
     * @return the caller type a filter set with {@link #set(ServletRequest, String)}, or empty when none was set
     */
    public static Optional<String> get(ServletRequest request) {
        return request.getAttribute(ATTRIBUTE) instanceof String caller ? Optional.of(caller) : Optional.empty();
    }
}
