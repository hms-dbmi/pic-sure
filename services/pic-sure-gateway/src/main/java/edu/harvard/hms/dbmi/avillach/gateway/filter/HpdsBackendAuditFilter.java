package edu.harvard.hms.dbmi.avillach.gateway.filter;

import java.io.IOException;

import org.springframework.web.filter.OncePerRequestFilter;

import edu.harvard.hms.dbmi.avillach.commons.audit.AuditContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

/**
 * Moves the query service's {@value #HEADER} response header into the audit event. The query service names the HPDS backend that answered a
 * routed call ({@code rust}, {@code java-fallback}) or that a call was mirrored ({@code java-shadow}) in that header; this filter records
 * the value as the {@value #METADATA_KEY} audit metadata and keeps the header off the response the client receives. It wraps the response
 * so the header is caught when the proxy writes it, before the response is committed.
 */
public class HpdsBackendAuditFilter extends OncePerRequestFilter {

    /** The response header the query service sets on a routed or mirrored HPDS call. */
    public static final String HEADER = "X-PIC-SURE-HPDS-Backend";
    /** The audit metadata key the header's value is recorded under. */
    public static final String METADATA_KEY = "hpds_backend";

    private final AuditContext audit;

    public HpdsBackendAuditFilter(AuditContext audit) {
        this.audit = audit;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        chain.doFilter(request, new HttpServletResponseWrapper(response) {

            @Override
            public void setHeader(String name, String value) {
                if (!captured(name, value)) {
                    super.setHeader(name, value);
                }
            }

            @Override
            public void addHeader(String name, String value) {
                if (!captured(name, value)) {
                    super.addHeader(name, value);
                }
            }
        });
    }

    /** Records the value when {@code name} is {@value #HEADER}, matched without regard to case, and reports whether it did. */
    private boolean captured(String name, String value) {
        if (!HEADER.equalsIgnoreCase(name)) {
            return false;
        }
        audit.put(METADATA_KEY, value);
        return true;
    }
}
