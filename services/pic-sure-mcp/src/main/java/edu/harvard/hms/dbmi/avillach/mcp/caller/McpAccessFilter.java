package edu.harvard.hms.dbmi.avillach.mcp.caller;

import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUser;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUserResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Admits a request to {@code /mcp} only when the gateway vouched for it with the {@value #PRIVILEGE} privilege or as a verified
 * {@code pic-sure-ai-service} call, and answers 403 otherwise. The gateway introspects every {@code /mcp} call (PSAMA applies its own
 * {@code AR_MCP_REQUESTS} rule) and forwards {@code X-User-Privileges}; this filter is the second check, so a caller that reaches the
 * service by a route the PSAMA rule does not cover still needs the privilege. The service validates no token itself and trusts these
 * gateway-owned headers, so it must be reachable only through the gateway.
 *
 * <ul> <li>An open-access caller carries a user id but no privileges, so it is refused.</li> <li>A request with no resolved user is
 * refused.</li> <li>{@code X-Client-Type: ai-service} is gateway-owned: the gateway strips a client-supplied value and sets it only on a
 * call that presented the AI service credential. That call is admitted without the privilege, because the assistant's own
 * {@code ASSISTED_SEARCH} guard decided who may reach it. {@code ASSISTED_SEARCH} alone does not admit a caller here.</li> </ul>
 *
 * <p>This is a servlet filter, not {@code @PreAuthorize}, because the service has no Spring Security and the MCP transport is a function
 * router.
 */
public class McpAccessFilter extends OncePerRequestFilter {

    /** The privilege a caller needs to use {@code /mcp}. */
    public static final String PRIVILEGE = "PIC_SURE_MCP";

    /** Header carrying the gateway-verified client type. */
    public static final String CLIENT_TYPE_HEADER = "X-Client-Type";

    /** {@value #CLIENT_TYPE_HEADER} value the gateway sets only on a verified {@code pic-sure-ai-service} call. */
    public static final String AI_SERVICE_CLIENT = "ai-service";

    private static final Logger log = LoggerFactory.getLogger(McpAccessFilter.class);

    private static final String FORBIDDEN_BODY =
        "{\"error\":\"forbidden\",\"message\":\"This account does not have the " + PRIVILEGE + " privilege.\"}";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        if (admits(request)) {
            chain.doFilter(request, response);
            return;
        }
        log.info("Refused /mcp call without {}: userId={}", PRIVILEGE, request.getHeader(CallerHeaders.USER_ID_HEADER));
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(FORBIDDEN_BODY);
    }

    private static boolean admits(HttpServletRequest request) {
        if (AI_SERVICE_CLIENT.equals(request.getHeader(CLIENT_TYPE_HEADER))) {
            return true;
        }
        return GatewayUserResolver.resolve(request).map(GatewayUser::getPrivileges).map(privileges -> privileges.contains(PRIVILEGE))
            .orElse(false);
    }
}
