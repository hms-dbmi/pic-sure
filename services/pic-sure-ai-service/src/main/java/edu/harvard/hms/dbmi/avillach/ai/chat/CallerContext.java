package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * @param userId the {@code X-User-Id} the gateway set after PSAMA introspection, or null. Never replayed on an outbound call
 * @param accessType the {@code X-Picsure-Access-Type} the gateway set: {@code authorized} for a validated login token, {@code open} for
 *        an open-access grant, or null when the request reached neither gateway filter
 */
public record CallerContext(String authorization, String requestId, String userId, String accessType) {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** PSAMA's open-access session tokens, {@code picsure_s_<jwt>}: reissued every few minutes with the same session id in {@code sub}. */
    private static final String OPEN_SESSION_PREFIX = "Bearer picsure_s_";

    public CallerContext(String authorization, String requestId, String userId) {
        this(authorization, requestId, userId, null);
    }

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
        return new CallerContext(authorization, requestId, userId, request.getHeader(GatewayUserResolver.HEADER_ACCESS_TYPE));
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

    /**
     * Who a stored conversation or reply belongs to, stable for as long as the caller is the same person or session even though their
     * token is replaced: a logged-in user by their gateway user id, an open-access session by the session id in its token. Neither can be
     * the {@code X-User-Id} alone, which every open-access caller shares. The session id is read without checking the token's signature:
     * {@code open} means the gateway had PSAMA verify it, the same trust its {@code X-User-*} headers rely on. Any other caller (a stored
     * API key, or a request that did not come through the gateway) is identified by a hash of its token, never the token itself.
     *
     * @return {@code user:<id>}, {@code session:<id>} or {@code token:<SHA-256 hex>}
     */
    public String owner() {
        if (GatewayUserResolver.ACCESS_TYPE_AUTHORIZED.equals(accessType) && userId != null && !userId.isBlank()) {
            return "user:" + userId;
        }
        String session = GatewayUserResolver.ACCESS_TYPE_OPEN.equals(accessType) ? openSessionId(authorization) : null;
        return session != null ? "session:" + session : "token:" + sha256(authorization);
    }

    /** @return the {@code sub} of a {@code picsure_s_} token, or null when it is not one or its payload is not a session id */
    private static String openSessionId(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, OPEN_SESSION_PREFIX, 0, OPEN_SESSION_PREFIX.length())) {
            return null;
        }
        String[] parts = authorization.substring(OPEN_SESSION_PREFIX.length()).trim().split("\\.");
        if (parts.length != 3) {
            return null;
        }
        try {
            JsonNode subject = JSON.readTree(Base64.getUrlDecoder().decode(parts[1])).path("sub");
            // PSAMA only issues canonical UUIDs; anything else is not a session it made.
            return subject.isTextual() && UUID.fromString(subject.textValue()).toString().equals(subject.textValue()) ? subject.textValue()
                : null;
        } catch (IllegalArgumentException | IOException e) {
            return null;
        }
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(String.valueOf(text).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java runtime provides SHA-256", e);
        }
    }

    @Override
    public String toString() {
        String presence = authorization == null || authorization.isBlank() ? "<absent>" : "<redacted>";
        return "CallerContext[authorization=" + presence + ", requestId=" + requestId + ", userId=" + userId + ", accessType=" + accessType
            + "]";
    }
}
