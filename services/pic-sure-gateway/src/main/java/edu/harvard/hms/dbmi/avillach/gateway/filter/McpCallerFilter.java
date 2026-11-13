package edu.harvard.hms.dbmi.avillach.gateway.filter;

import edu.harvard.hms.dbmi.avillach.commons.audit.AuditContext;
import edu.harvard.hms.dbmi.avillach.commons.audit.VerifiedCaller;
import edu.harvard.hms.dbmi.avillach.commons.request.RoutedRequestPath;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Proves that a request came from {@code pic-sure-mcp} and records that as the request's {@link VerifiedCaller}. It runs before
 * {@link OpenAccessFilter} and the introspection filter, so a bad credential is rejected before PSAMA is called, and before
 * {@code InboundIdentityHeaderSanitizingFilter}, so it reads {@value #TOKEN_HEADER} before that filter removes it.
 *
 * <ul> <li>A {@value #TOKEN_HEADER} value equal to the current or the previous configured secret, compared in constant time, marks the
 * request verified caller {@value VerifiedCaller#MCP}.</li> <li>A present value that matches neither gets 401
 * {@code invalid_service_credential}, and the audit metadata records the failure. When no secret is configured, every presented value is
 * invalid.</li> <li>An absent header changes nothing.</li> <li>A request whose {@link RoutedRequestPath} is exactly {@value #MCP_PATH} is
 * marked verified caller {@value VerifiedCaller#MCP}, since only the MCP route serves it. That is the decoded path the router matches, so
 * an encoded spelling such as {@code /%6Dcp} is marked too.</li> </ul>
 *
 * <p>The presented value is never logged, echoed in the response, or written to audit metadata.
 */
public class McpCallerFilter extends ServiceCallerFilter {

    /** Header carrying the shared secret, a credential, on {@code pic-sure-mcp}'s loop-back calls. */
    public static final String TOKEN_HEADER = "X-PIC-SURE-MCP-TOKEN";

    /** The public path of the MCP endpoint itself. */
    public static final String MCP_PATH = "/mcp";

    /**
     * @param audit the request-scoped audit metadata holder
     * @param serviceToken the current secret; null or blank means none is configured
     * @param previousServiceToken the secret being rotated out; null or blank means none
     */
    public McpCallerFilter(AuditContext audit, String serviceToken, String previousServiceToken) {
        super(audit, TOKEN_HEADER, "MCP", false, serviceToken, previousServiceToken);
    }

    @Override
    void mark(HttpServletRequest req, boolean credentialVerified) {
        if (credentialVerified || MCP_PATH.equals(RoutedRequestPath.of(req))) {
            VerifiedCaller.set(req, VerifiedCaller.MCP);
        }
    }
}
