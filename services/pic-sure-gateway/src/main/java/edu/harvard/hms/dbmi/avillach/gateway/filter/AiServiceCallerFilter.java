package edu.harvard.hms.dbmi.avillach.gateway.filter;

import java.io.IOException;

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
 * Proves that a call to {@code /mcp} came from {@code pic-sure-ai-service} and records that as the request's {@link VerifiedCaller}. The
 * assistant makes those calls on behalf of a user who holds {@code ASSISTED_SEARCH}, and {@code pic-sure-mcp} otherwise requires the
 * {@code PIC_SURE_MCP} privilege, which the assistant's users do not need. It runs after {@link McpCallerFilter}, which marks every
 * {@code /mcp} request as caller {@code mcp}, so a verified AI call replaces that mark. It runs before {@code OpenAccessFilter} and the
 * introspection filter, so a bad credential is rejected before PSAMA is called, and before {@code InboundIdentityHeaderSanitizingFilter},
 * so it reads {@value #TOKEN_HEADER} before that filter removes it.
 *
 * <ul> <li>A {@value #TOKEN_HEADER} value equal to the current or the previous configured secret, compared in constant time, on a request
 * whose {@link RoutedRequestPath} is exactly {@value McpCallerFilter#MCP_PATH}, marks the request verified caller {@value #CALLER}. The
 * user's token is still introspected, against {@value #AUTHORIZATION_TARGET} rather than {@value McpCallerFilter#MCP_PATH}, so PSAMA checks
 * the {@code ASSISTED_SEARCH} rule and not the {@code PIC_SURE_MCP} one.</li> <li>A present value that matches neither gets 401
 * {@code invalid_service_credential} on any path, and the audit metadata records the failure. When no secret is configured, every presented
 * value is invalid.</li> <li>A valid value on any other path is ignored: it marks nothing.</li> <li>An absent header changes nothing.</li>
 * </ul>
 *
 * <p>The presented value is never logged, echoed in the response, or written to audit metadata.
 */
public class AiServiceCallerFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AiServiceCallerFilter.class);

    /** Header carrying the shared secret, a credential, on {@code pic-sure-ai-service}'s calls to {@code /mcp}. */
    public static final String TOKEN_HEADER = "X-PIC-SURE-AI-TOKEN";

    /** The verified caller type recorded for requests from {@code pic-sure-ai-service}. */
    public static final String CALLER = "ai-service";

    /**
     * The {@code "Target Service"} PSAMA is asked about for a verified AI call. It matches the {@code AR_AI_SERVICE_REQUESTS} rule
     * ({@code ^/ai(/.*)?$}) that {@code ASSISTED_SEARCH} carries. It names the authorization decision only; routing uses the real path.
     */
    public static final String AUTHORIZATION_TARGET = "/ai/mcp";

    private final AuditContext audit;
    private final ServiceTokenMatcher tokens;

    /**
     * Creates the filter. Each configured secret is trimmed, so stray whitespace from an environment file does not make the real secret
     * fail to match. When neither secret is configured, a warning is logged once, because every {@value #TOKEN_HEADER} the filter sees will
     * then be rejected.
     *
     * @param audit the request-scoped audit metadata holder
     * @param serviceToken the current secret; null or blank means none is configured
     * @param previousServiceToken the secret being rotated out; null or blank means none
     */
    public AiServiceCallerFilter(AuditContext audit, String serviceToken, String previousServiceToken) {
        this.audit = audit;
        this.tokens = new ServiceTokenMatcher(serviceToken, previousServiceToken);
        if (tokens.isUnconfigured()) {
            log.warn("No AI service token is configured, so every request presenting {} will be rejected", TOKEN_HEADER);
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
        throws ServletException, IOException {
        String presented = req.getHeader(TOKEN_HEADER);
        if (presented != null) {
            if (!tokens.matches(presented)) {
                audit.put("auth_result", "failure");
                audit.put("auth_failure_reason", "invalid_service_credential");
                GatewayErrors.write(resp, HttpStatus.UNAUTHORIZED, "invalid_service_credential", "Invalid service credential.");
                return;
            }
            if (McpCallerFilter.MCP_PATH.equals(RoutedRequestPath.of(req))) {
                VerifiedCaller.set(req, CALLER);
            }
        }
        chain.doFilter(req, resp);
    }
}
