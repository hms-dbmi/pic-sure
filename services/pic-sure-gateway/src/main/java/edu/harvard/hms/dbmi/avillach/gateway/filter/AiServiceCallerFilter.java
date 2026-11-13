package edu.harvard.hms.dbmi.avillach.gateway.filter;

import edu.harvard.hms.dbmi.avillach.commons.audit.AuditContext;
import edu.harvard.hms.dbmi.avillach.commons.audit.VerifiedCaller;
import edu.harvard.hms.dbmi.avillach.commons.request.RoutedRequestPath;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Proves that a call to {@code /mcp} came from {@code pic-sure-ai-service} and records that as the request's {@link VerifiedCaller}. The
 * assistant makes those calls on behalf of a user who holds {@code ASSISTED_SEARCH}, and {@code pic-sure-mcp} otherwise requires the
 * {@code PIC_SURE_MCP} privilege, which the assistant's users do not need. It runs after {@link McpCallerFilter}, which marks every
 * {@code /mcp} request as caller {@code mcp}, so a verified AI call replaces that mark. It runs before {@code OpenAccessFilter} and the
 * introspection filter, so a bad credential is rejected before PSAMA is called, and before {@code InboundIdentityHeaderSanitizingFilter},
 * so it reads {@value #TOKEN_HEADER} before that filter removes it.
 *
 * <ul> <li>A {@value #TOKEN_HEADER} value equal to the current or the previous configured secret, compared in constant time, on a request
 * whose {@link RoutedRequestPath} is exactly {@value McpCallerFilter#MCP_PATH}, marks the request verified caller
 * {@value VerifiedCaller#AI_SERVICE}. The introspection filter then tells PSAMA the caller, so PSAMA checks the {@code ASSISTED_SEARCH}
 * rule and not the {@code PIC_SURE_MCP} one.</li> <li>A present value that matches neither gets 401 {@code invalid_service_credential} on
 * any path, and the audit metadata records the failure. When no secret is configured, every presented value is invalid.</li> <li>A valid
 * value on any other path is ignored: it marks nothing.</li> <li>An absent header changes nothing.</li> </ul>
 *
 * <p>The presented value is never logged, echoed in the response, or written to audit metadata.
 */
public class AiServiceCallerFilter extends ServiceCallerFilter {

    /** Header carrying the shared secret, a credential, on {@code pic-sure-ai-service}'s calls to {@code /mcp}. */
    public static final String TOKEN_HEADER = VerifiedCaller.AI_SERVICE_TOKEN_HEADER;

    /**
     * @param audit the request-scoped audit metadata holder
     * @param serviceToken the current secret; null or blank means none is configured
     * @param previousServiceToken the secret being rotated out; null or blank means none
     */
    public AiServiceCallerFilter(AuditContext audit, String serviceToken, String previousServiceToken) {
        super(audit, TOKEN_HEADER, "AI service", true, serviceToken, previousServiceToken);
    }

    @Override
    void mark(HttpServletRequest req, boolean credentialVerified) {
        if (credentialVerified && McpCallerFilter.MCP_PATH.equals(RoutedRequestPath.of(req))) {
            VerifiedCaller.set(req, VerifiedCaller.AI_SERVICE);
        }
    }
}
