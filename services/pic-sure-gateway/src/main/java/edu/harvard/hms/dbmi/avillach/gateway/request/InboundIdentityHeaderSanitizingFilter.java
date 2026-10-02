package edu.harvard.hms.dbmi.avillach.gateway.request;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.web.filter.OncePerRequestFilter;

import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUserResolver;
import edu.harvard.hms.dbmi.avillach.commons.request.RoutedRequestPath;
import edu.harvard.hms.dbmi.avillach.gateway.filter.McpCallerFilter;
import edu.harvard.hms.dbmi.avillach.gateway.filter.OpenAccessFilter;
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
 * {@code X-Session-Id}, {@code request-source}, and ordinary {@code X-Client-Type} values are deliberately left unstripped as client
 * telemetry. The reserved {@code X-Client-Type} values {@code service} and {@code mcp} are stripped because downstream services use them to
 * identify internal calls; only {@link edu.harvard.hms.dbmi.avillach.gateway.filter.IdentityPropagationFilter} may set {@code mcp}, and
 * only for a verified caller. <p> {@value McpCallerFilter#HEADER} is always stripped, so the MCP service secret never reaches a proxied
 * service. {@code X-PICSURE-API-Key} is stripped from every request except one whose decoded {@link RoutedRequestPath} is exactly
 * {@value McpCallerFilter#MCP_PATH}, the same match {@link McpCallerFilter} uses, because {@code pic-sure-mcp} forwards the key on its
 * loop-back calls to sites that enforce one. <p> {@link edu.harvard.hms.dbmi.avillach.gateway.filter.IdentityPropagationFilter} already
 * hides the identity headers from the client, but this filter exists as an independent trust boundary: even if the DB-free auth chain were
 * ever bypassed or misconfigured, a client's own {@code X-User-Id}/{@code X-User-Privileges}/etc. must never pass through untouched -- that
 * would be an identity/privilege-spoofing hole. This filter closes that hole unconditionally, independent of anything the auth chain does.
 * <p> Runs at order 25: after {@code OpenAccessFilter} (order 20) has extracted the optional API key for PSAMA, and before the remaining
 * DB-free auth chain (introspection order 30+). This keeps the API key from downstream services, other than the MCP route, while still
 * allowing open-access validation to consume it. {@link edu.harvard.hms.dbmi.avillach.gateway.filter.IdentityPropagationFilter} (order 50)
 * still runs afterward and sets the gateway-resolved values on its own wrapper, which never falls through to the (already-sanitized) client
 * request for these names -- so normal propagation of resolved identity is unaffected by this filter running first.
 */
public class InboundIdentityHeaderSanitizingFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        filterChain.doFilter(new SanitizedIdentityHeadersRequest(request), response);
    }

    static class SanitizedIdentityHeadersRequest extends HttpServletRequestWrapper {

        private static final String CLIENT_TYPE_HEADER = "X-Client-Type";
        private static final Set<String> RESERVED_CLIENT_TYPES = Set.of("service", McpCallerFilter.CALLER);

        /**
         * Gateway-owned identity headers, spoofable source-address headers, and the internal and MCP service tokens: always hidden from the
         * raw client request, regardless of name casing.
         */
        private static final Set<String> STRIPPED_HEADERS = Set.of(
            GatewayUserResolver.HEADER_USER_ID, GatewayUserResolver.HEADER_USER_SUBJECT, GatewayUserResolver.HEADER_USER_EMAIL,
            GatewayUserResolver.HEADER_USER_ROLES, GatewayUserResolver.HEADER_USER_PRIVILEGES, "X-Real-IP", "Forwarded",
            "X-PIC-SURE-INTERNAL-TOKEN", GatewayUserResolver.HEADER_ACCESS_TYPE, McpCallerFilter.HEADER
        );

        private final boolean onMcpRoute;

        SanitizedIdentityHeadersRequest(HttpServletRequest request) {
            super(request);
            this.onMcpRoute = McpCallerFilter.MCP_PATH.equals(RoutedRequestPath.of(request));
        }

        private static boolean isAlwaysStripped(String name) {
            for (String stripped : STRIPPED_HEADERS) {
                if (stripped.equalsIgnoreCase(name)) return true;
            }
            return false;
        }

        private boolean isStripped(String name) {
            return isAlwaysStripped(name) || isStrippedApiKey(name) || isReservedClientType(name);
        }

        private boolean isStrippedApiKey(String name) {
            return OpenAccessFilter.API_KEY_HEADER.equalsIgnoreCase(name) && !onMcpRoute;
        }

        private boolean isReservedClientType(String name) {
            if (!CLIENT_TYPE_HEADER.equalsIgnoreCase(name)) return false;
            Enumeration<String> values = super.getHeaders(name);
            while (values != null && values.hasMoreElements()) {
                if (isReservedValue(values.nextElement())) return true;
            }
            return false;
        }

        private static boolean isReservedValue(String value) {
            if (value == null) return false;
            for (String reserved : RESERVED_CLIENT_TYPES) {
                if (reserved.equalsIgnoreCase(value.trim())) return true;
            }
            return false;
        }

        @Override
        public String getHeader(String name) {
            return isStripped(name) ? null : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return isStripped(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            Set<String> names = new LinkedHashSet<>();
            Enumeration<String> superNames = super.getHeaderNames();
            while (superNames != null && superNames.hasMoreElements()) {
                String n = superNames.nextElement();
                if (!isStripped(n)) {
                    names.add(n);
                }
            }
            return Collections.enumeration(names);
        }
    }
}
