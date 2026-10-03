package edu.harvard.hms.dbmi.avillach.gateway.filter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import edu.harvard.hms.dbmi.avillach.commons.audit.AuditContext;
import edu.harvard.hms.dbmi.avillach.commons.audit.VerifiedCaller;
import edu.harvard.hms.dbmi.avillach.commons.request.RoutedRequestPath;
import edu.harvard.hms.dbmi.avillach.gateway.error.GatewayErrors;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Proves that a request came from {@code pic-sure-mcp} and records that as the request's {@link VerifiedCaller}. It runs before
 * {@link OpenAccessFilter} and the introspection filter, so a bad credential is rejected before PSAMA is called, and before
 * {@code InboundIdentityHeaderSanitizingFilter}, so it reads {@value #TOKEN_HEADER} before that filter removes it.
 *
 * <ul> <li>A {@value #TOKEN_HEADER} value equal to the current or the previous configured secret, compared in constant time, marks the
 * request verified caller {@value #CALLER}.</li> <li>A present value that matches neither gets 401 {@code invalid_service_credential}, and
 * the audit metadata records the failure. When no secret is configured, every presented value is invalid.</li> <li>An absent header changes
 * nothing.</li> <li>A request whose {@link RoutedRequestPath} is exactly {@value #MCP_PATH} is marked verified caller {@value #CALLER},
 * since only the MCP route serves it. That is the decoded path the router matches, so an encoded spelling such as {@code /%6Dcp} is marked
 * too.</li> </ul>
 *
 * <p>The presented value is never logged, echoed in the response, or written to audit metadata.
 */
public class McpCallerFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(McpCallerFilter.class);

    /** Header carrying the shared secret, a credential, on {@code pic-sure-mcp}'s loop-back calls. */
    public static final String TOKEN_HEADER = "X-PIC-SURE-MCP-TOKEN";

    /** The verified caller type recorded for requests from {@code pic-sure-mcp}. */
    public static final String CALLER = "mcp";

    /** The public path of the MCP endpoint itself. */
    public static final String MCP_PATH = "/mcp";

    private final AuditContext audit;
    private final byte[] serviceToken;
    private final byte[] previousServiceToken;

    /**
     * Creates the filter. Each configured secret is trimmed, so stray whitespace from an environment file does not make the real secret
     * fail to match. When neither secret is configured, a warning is logged once, because every {@value #TOKEN_HEADER} the filter sees will
     * then be rejected.
     *
     * @param audit the request-scoped audit metadata holder
     * @param serviceToken the current secret; null or blank means none is configured
     * @param previousServiceToken the secret being rotated out; null or blank means none
     */
    public McpCallerFilter(AuditContext audit, String serviceToken, String previousServiceToken) {
        this.audit = audit;
        this.serviceToken = configuredBytes(serviceToken);
        this.previousServiceToken = configuredBytes(previousServiceToken);
        if (this.serviceToken == null && this.previousServiceToken == null) {
            log.warn("No MCP service token is configured, so every request presenting {} will be rejected", TOKEN_HEADER);
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
        throws ServletException, IOException {
        String presented = req.getHeader(TOKEN_HEADER);
        if (presented != null) {
            if (!matchesConfiguredToken(presented)) {
                audit.put("auth_result", "failure");
                audit.put("auth_failure_reason", "invalid_service_credential");
                GatewayErrors.write(resp, HttpStatus.UNAUTHORIZED, "invalid_service_credential", "Invalid service credential.");
                return;
            }
            VerifiedCaller.set(req, CALLER);
        }
        if (MCP_PATH.equals(RoutedRequestPath.of(req))) {
            VerifiedCaller.set(req, CALLER);
        }
        chain.doFilter(req, resp);
    }

    private boolean matchesConfiguredToken(String presented) {
        byte[] candidate = presented.getBytes(StandardCharsets.UTF_8);
        boolean current = serviceToken != null && MessageDigest.isEqual(serviceToken, candidate);
        boolean previous = previousServiceToken != null && MessageDigest.isEqual(previousServiceToken, candidate);
        return current | previous;
    }

    private static byte[] configuredBytes(String token) {
        return token == null || token.isBlank() ? null : token.trim().getBytes(StandardCharsets.UTF_8);
    }
}
