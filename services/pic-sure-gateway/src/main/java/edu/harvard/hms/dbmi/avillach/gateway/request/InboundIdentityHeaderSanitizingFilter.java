package edu.harvard.hms.dbmi.avillach.gateway.request;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;

import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUserResolver;
import edu.harvard.hms.dbmi.avillach.commons.audit.ClientTypeAllowlist;
import edu.harvard.hms.dbmi.avillach.commons.audit.VerifiedCaller;
import edu.harvard.hms.dbmi.avillach.gateway.error.GatewayErrors;
import edu.harvard.hms.dbmi.avillach.commons.request.RoutedRequestPath;
import edu.harvard.hms.dbmi.avillach.gateway.filter.McpCallerFilter;
import edu.harvard.hms.dbmi.avillach.gateway.auth.ApiKeyBearer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Defense-in-depth, registered UNCONDITIONALLY (see {@code ObservabilityConfig}): strips the five gateway-owned {@code X-User-*} identity
 * headers ({@link GatewayUserResolver#HEADER_USER_ID}, {@code _SUBJECT}, {@code _EMAIL}, {@code _ROLES}, {@code _PRIVILEGES}) and
 * {@link GatewayUserResolver#HEADER_ACCESS_TYPE}, the spoofable source-address headers {@code X-Real-IP} and {@code Forwarded}, and the
 * service-to-service {@code X-PIC-SURE-INTERNAL-TOKEN} (a client-presented internal token must never transit the gateway) from every
 * inbound CLIENT request before anything downstream -- routing, the auth chain, or proxied services -- can ever see a client-supplied
 * value. <p> {@code X-Forwarded-For} is deliberately NOT stripped: the trusted front proxy legitimately appends to it, and consumers
 * ({@code AuditLoggingFilter}) take the RIGHTMOST entry -- the nearest trusted hop -- rather than the client-forgeable leftmost one.
 * {@code X-Session-Id} and {@code request-source} are deliberately left unstripped as client telemetry. {@code X-Client-Type} must be
 * absent, blank, or made up only of entries on the {@link ClientTypeAllowlist}; any other request is rejected with 400
 * {@code unexpected_caller_type} before it reaches a proxied service. Accepted values are rewritten to their listed spelling. The reserved
 * values {@code service} and {@code mcp}, which downstream services use to identify internal calls, are never on the list, so a client
 * cannot present them; the one exception is {@code mcp} on a request {@link McpCallerFilter} has verified. Reserved values are still hidden
 * from the wrapped request, because only {@link edu.harvard.hms.dbmi.avillach.gateway.filter.IdentityPropagationFilter} may set
 * {@code mcp}, and only for a verified caller. <p> {@value McpCallerFilter#TOKEN_HEADER} is always stripped, so the MCP service secret
 * never reaches a proxied service. An {@code Authorization} header carrying a PSAMA key ({@link ApiKeyBearer}) is stripped from every
 * request except one whose decoded {@link RoutedRequestPath} is exactly {@value McpCallerFilter#MCP_PATH}, the same match
 * {@link McpCallerFilter} uses, because {@code pic-sure-mcp} forwards the key on its loop-back calls to sites that enforce one. <p>
 * {@link edu.harvard.hms.dbmi.avillach.gateway.filter.IdentityPropagationFilter} already hides the identity headers from the client, but
 * this filter exists as an independent trust boundary: even if the DB-free auth chain were ever bypassed or misconfigured, a client's own
 * {@code X-User-Id}/{@code X-User-Privileges}/etc. must never pass through untouched -- that would be an identity/privilege-spoofing hole.
 * This filter closes that hole unconditionally, independent of anything the auth chain does. <p> Runs at order 25: after
 * {@code OpenAccessFilter} (order 20) has extracted the optional API key for PSAMA, and before the remaining DB-free auth chain
 * (introspection order 30+). This keeps the API key from downstream services, other than the MCP route, while still allowing open-access
 * validation to consume it. {@link edu.harvard.hms.dbmi.avillach.gateway.filter.IdentityPropagationFilter} (order 50) still runs afterward
 * and sets the gateway-resolved values on its own wrapper, which never falls through to the (already-sanitized) client request for these
 * names -- so normal propagation of resolved identity is unaffected by this filter running first.
 */
public class InboundIdentityHeaderSanitizingFilter extends OncePerRequestFilter {

    private final ClientTypeAllowlist clientTypes;

    /** Creates a filter that accepts the {@link ClientTypeAllowlist#DEFAULT} client types. */
    public InboundIdentityHeaderSanitizingFilter() {
        this(ClientTypeAllowlist.DEFAULT);
    }

    /**
     * Creates a filter that passes only the given client types through in {@code X-Client-Type}.
     *
     * @param clientTypes the client types a request may present
     */
    public InboundIdentityHeaderSanitizingFilter(ClientTypeAllowlist clientTypes) {
        this.clientTypes = clientTypes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        if (hasUnexpectedCallerType(request)) {
            GatewayErrors.write(response, HttpStatus.BAD_REQUEST, "unexpected_caller_type", "Unexpected caller type.");
            return;
        }
        filterChain.doFilter(new SanitizedIdentityHeadersRequest(request, clientTypes), response);
    }

    private boolean hasUnexpectedCallerType(HttpServletRequest request) {
        boolean verifiedMcp = VerifiedCaller.get(request).filter(McpCallerFilter.CALLER::equals).isPresent();
        Enumeration<String> values = request.getHeaders("X-Client-Type");
        while (values != null && values.hasMoreElements()) {
            for (String entry : values.nextElement().split(",", -1)) {
                String token = entry.trim();
                boolean allowed = token.isEmpty() || clientTypes.resolve(token).isPresent()
                    || (verifiedMcp && McpCallerFilter.CALLER.equalsIgnoreCase(token));
                if (!allowed) return true;
            }
        }
        return false;
    }

    static class SanitizedIdentityHeadersRequest extends HttpServletRequestWrapper {

        private static final String AUTHORIZATION_HEADER = "Authorization";
        private static final String CLIENT_TYPE_HEADER = "X-Client-Type";

        /**
         * Gateway-owned identity headers, spoofable source-address headers, and the internal and MCP service tokens: always hidden from the
         * raw client request, regardless of name casing.
         */
        private static final Set<String> STRIPPED_HEADERS = Set.of(
            GatewayUserResolver.HEADER_USER_ID, GatewayUserResolver.HEADER_USER_SUBJECT, GatewayUserResolver.HEADER_USER_EMAIL,
            GatewayUserResolver.HEADER_USER_ROLES, GatewayUserResolver.HEADER_USER_PRIVILEGES, "X-Real-IP", "Forwarded",
            "X-PIC-SURE-INTERNAL-TOKEN", GatewayUserResolver.HEADER_ACCESS_TYPE, McpCallerFilter.TOKEN_HEADER
        );

        private final boolean onMcpRoute;
        private final ClientTypeAllowlist clientTypes;

        SanitizedIdentityHeadersRequest(HttpServletRequest request, ClientTypeAllowlist clientTypes) {
            super(request);
            this.clientTypes = clientTypes;
            this.onMcpRoute = McpCallerFilter.MCP_PATH.equals(RoutedRequestPath.of(request));
        }

        private static boolean isAlwaysStripped(String name) {
            for (String stripped : STRIPPED_HEADERS) {
                if (stripped.equalsIgnoreCase(name)) return true;
            }
            return false;
        }

        private boolean isStripped(String name) {
            return isAlwaysStripped(name) || isStrippedApiKey(name) || isDisallowedClientType(name);
        }

        private boolean isStrippedApiKey(String name) {
            return AUTHORIZATION_HEADER.equalsIgnoreCase(name) && !onMcpRoute && carriesApiKey(super.getHeaders(name));
        }

        private static boolean carriesApiKey(Enumeration<String> values) {
            while (values != null && values.hasMoreElements()) {
                if (ApiKeyBearer.extract(values.nextElement()) != null) return true;
            }
            return false;
        }

        private boolean isDisallowedClientType(String name) {
            if (!CLIENT_TYPE_HEADER.equalsIgnoreCase(name)) return false;
            Enumeration<String> values = super.getHeaders(name);
            while (values != null && values.hasMoreElements()) {
                if (hasDisallowedEntry(values.nextElement())) return true;
            }
            return false;
        }

        private boolean hasDisallowedEntry(String value) {
            if (value == null) return false;
            for (String token : value.split(",", -1)) {
                if (!token.isBlank() && clientTypes.resolve(token).isEmpty()) return true;
            }
            return false;
        }

        private String canonicalClientType(String value) {
            if (value == null) return null;
            StringJoiner joined = new StringJoiner(", ");
            for (String token : value.split(",", -1)) {
                if (!token.isBlank()) joined.add(clientTypes.resolve(token).orElse(token.trim()));
            }
            return joined.toString();
        }

        @Override
        public String getHeader(String name) {
            if (isStripped(name)) return null;
            String value = super.getHeader(name);
            return CLIENT_TYPE_HEADER.equalsIgnoreCase(name) ? canonicalClientType(value) : value;
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if (isStripped(name)) return Collections.emptyEnumeration();
            if (!CLIENT_TYPE_HEADER.equalsIgnoreCase(name)) return super.getHeaders(name);
            List<String> values = new ArrayList<>();
            Enumeration<String> raw = super.getHeaders(name);
            while (raw != null && raw.hasMoreElements())
                values.add(canonicalClientType(raw.nextElement()));
            return Collections.enumeration(values);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            Set<String> names = HeaderNames.retain(super.getHeaderNames(), n -> !isStripped(n));
            return Collections.enumeration(names);
        }
    }
}
