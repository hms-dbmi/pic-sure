package edu.harvard.hms.dbmi.avillach.ai.chat;

import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUserResolver;
import edu.harvard.hms.dbmi.avillach.commons.request.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;

/**
 * The inbound identity a chat turn carries end to end: the caller's raw {@code Authorization} header, replayed untouched on every outbound
 * MCP call (see {@code McpToolGateway}), and the gateway-verified {@code X-User-Id} for logging only. Mirrors {@code pic-sure-mcp}'s own
 * {@code edu.harvard.hms.dbmi.avillach.mcp.caller.CallerHeaders} one service over -- same "replay inbound headers outbound, log identity
 * but never the token" shape, since this service has the same requirement {@code pic-sure-mcp} does: no synthetic/shared credential, ever.
 *
 * <p>{@link #toString()} never prints {@link #authorization()}, so the record is safe to log.
 *
 * @param authorization the inbound {@code Authorization} header, always non-blank and {@code Bearer}-prefixed by the time this is
 *        constructed -- see {@code CallerContextArgumentResolver}
 * @param requestId the inbound {@code X-Request-Id}, or null
 * @param userId the {@code X-User-Id} the gateway set after PSAMA introspection, or null. Read only for logging/audit, never replayed on an
 *        outbound call
 */
public record CallerContext(String authorization, String requestId, String userId) {

    /**
     * Reads the caller's identity off an inbound HTTP request.
     *
     * @param request the inbound servlet request
     * @return the caller context, with a possibly-blank {@link #authorization()} the caller must validate -- this factory does not itself
     *         reject a missing header
     */
    public static CallerContext from(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        String requestId = request.getHeader(RequestIdFilter.HEADER);
        String userId = GatewayUserResolver.resolve(request).map(user -> user.getUserId()).orElse(null);
        return new CallerContext(authorization, requestId, userId);
    }

    /**
     * Writes {@link #authorization()} onto an outbound request's headers. A tool gateway calls this when forwarding the caller's identity
     * to the MCP endpoint; never used for the Bedrock call, which has its own, unrelated AWS credential.
     *
     * @param headers the outbound request headers
     */
    public void applyTo(HttpHeaders headers) {
        if (authorization != null && !authorization.isBlank()) {
            headers.set(HttpHeaders.AUTHORIZATION, authorization);
        }
    }

    @Override
    public String toString() {
        String presence = authorization == null || authorization.isBlank() ? "<absent>" : "<redacted>";
        return "CallerContext[authorization=" + presence + ", requestId=" + requestId + ", userId=" + userId + "]";
    }
}
