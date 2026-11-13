package edu.harvard.hms.dbmi.avillach.gateway.filter;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import edu.harvard.hms.dbmi.avillach.commons.audit.AuditContext;
import edu.harvard.hms.dbmi.avillach.gateway.error.GatewayErrors;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Checks the shared secret a sibling service presents in a header, and leaves what a good credential marks to the subclass. A value equal
 * to the current or the previous configured secret, compared in constant time, is accepted. A present value that matches neither gets 401
 * {@code invalid_service_credential} on any path, and the audit metadata records the failure. When no secret is configured, every presented
 * value is invalid. An absent header is not a failure.
 *
 * <p>The presented value is never logged, echoed in the response, or written to audit metadata.
 */
abstract class ServiceCallerFilter extends OncePerRequestFilter {

    private final AuditContext audit;
    private final ServiceTokenMatcher tokens;
    private final String tokenHeader;

    /**
     * Each configured secret is trimmed, so stray whitespace from an environment file does not make the real secret fail to match. When
     * neither secret is configured, a message is logged once, because every request presenting {@code tokenHeader} will then be rejected.
     * It is a warning when the service is expected to exist everywhere, and only info when the service is optional.
     *
     * @param audit the request-scoped audit metadata holder
     * @param tokenHeader the header that carries the secret
     * @param serviceName what the message calls the service, such as {@code MCP}
     * @param serviceOptional true when many deployments run without the service, so a missing secret is normal and is logged at info
     * @param serviceToken the current secret; null or blank means none is configured
     * @param previousServiceToken the secret being rotated out; null or blank means none
     */
    ServiceCallerFilter(
        AuditContext audit, String tokenHeader, String serviceName, boolean serviceOptional, String serviceToken,
        String previousServiceToken
    ) {
        this.audit = audit;
        this.tokenHeader = tokenHeader;
        this.tokens = new ServiceTokenMatcher(serviceToken, previousServiceToken);
        if (tokens.isUnconfigured()) {
            // Logged under the concrete filter's name, so the message names which service's secret is missing.
            Logger logger = LoggerFactory.getLogger(getClass());
            String message = "No {} service token is configured, so every request presenting {} will be rejected";
            if (serviceOptional) {
                logger.info(message, serviceName, tokenHeader);
            } else {
                logger.warn(message, serviceName, tokenHeader);
            }
        }
    }

    /**
     * Records what the request proves about its caller. Runs for every request that was not rejected.
     *
     * @param req the request being served
     * @param credentialVerified true when the request presented a valid secret
     */
    abstract void mark(HttpServletRequest req, boolean credentialVerified);

    @Override
    protected final void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
        throws ServletException, IOException {
        String presented = req.getHeader(tokenHeader);
        if (presented != null && !tokens.matches(presented)) {
            audit.put("auth_result", "failure");
            audit.put("auth_failure_reason", "invalid_service_credential");
            GatewayErrors.write(resp, HttpStatus.UNAUTHORIZED, "invalid_service_credential", "Invalid service credential.");
            return;
        }
        mark(req, presented != null);
        chain.doFilter(req, resp);
    }
}
