package edu.harvard.hms.dbmi.avillach.commons.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Shared actuator access control for PIC-SURE Spring Boot services behind the gateway.
 *
 * <p>The returned chain matches EVERY actuator endpoint (health included) and is gated by {@link TokenAuthenticationFilter}: <ul> <li>Valid
 * X-Application-Token -> install a synthetic authenticated principal (ROLE_ACTUATOR), continue. Because a principal is now present,
 * {@code show-details: when_authorized} reveals component detail on {@code /actuator/health}, and protected endpoints
 * (prometheus/metrics/info) are reachable.</li> <li>Shallow health / liveness / readiness without a token -> continue UNauthenticated ->
 * the load balancer gets shallow {@code {"status":"UP"}} only.</li> <li>Any other actuator path without a valid token -> 401
 * (short-circuit; guarantees 401, not 403).</li> <li>require-token=false -> authenticate unconditionally and permit all (AIO dev /
 * rollback).</li> <li>Token unconfigured (blank) while require-token=true -> fail closed: the token can never match (a blank configured
 * token is never treated as a valid credential, even against a blank header), so every protected endpoint 401s; shallow
 * health/liveness/readiness remain open regardless.</li> </ul>
 *
 * <p>Register the chain at {@code @Order(0)}; the service's main chain must be {@code @Order(>=1)}.
 */
public final class ActuatorSecurityHelper {

    /**
     * Authority granted to a valid-token caller. With {@code show-details: when_authorized} and no {@code management.endpoint.health.roles}
     * configured, an authenticated principal suffices to reveal detail; the authority documents intent and lets operators tighten via that
     * property if desired.
     */
    public static final String ACTUATOR_AUTHORITY = "ROLE_ACTUATOR";

    /** Synthetic principal name for the token-authenticated monitoring caller. */
    public static final String ACTUATOR_PRINCIPAL = "actuator-monitor";

    private ActuatorSecurityHelper() {}

    public static SecurityFilterChain actuatorChain(HttpSecurity http, ActuatorTokenProperties props) throws Exception {
        return http.securityMatcher(EndpointRequest.toAnyEndpoint()) // ALL actuator endpoints, health included
            .csrf(csrf -> csrf.disable())
            // The TokenAuthenticationFilter is the real gate (it 401s directly and installs the principal);
            // the authorization layer permits and lets the filter decide. Spring Security's default
            // SecurityContextHolderAwareRequestFilter wraps the request so HealthEndpoint's when_authorized
            // check sees the principal the filter set into the SecurityContext.
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .addFilterBefore(new TokenAuthenticationFilter(props), AuthorizationFilter.class).build();
    }

    static final class TokenAuthenticationFilter extends OncePerRequestFilter {

        private final ActuatorTokenProperties props;

        TokenAuthenticationFilter(ActuatorTokenProperties props) {
            this.props = props;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {

            // Rollback / AIO dev: no enforcement. Authenticate so detail is visible locally, permit all.
            if (!props.isRequireToken()) {
                authenticate();
                chain.doFilter(req, resp);
                return;
            }

            String header = req.getHeader(props.getHeaderName());
            if (hasConfiguredToken() && header != null && constantTimeEquals(header, props.getToken())) {
                authenticate(); // valid token -> reveal detail + reach protected endpoints
                chain.doFilter(req, resp);
                return;
            }

            if (isOpenHealthPath(req)) { // shallow health + LB probe groups stay open
                chain.doFilter(req, resp); // no principal -> shallow status only
                return;
            }

            resp.sendError(HttpServletResponse.SC_UNAUTHORIZED); // prometheus/metrics/info/links/etc.
        }

        /**
         * A blank/unset configured token is never a valid credential — fail closed rather than let a blank header match a blank token.
         */
        private boolean hasConfiguredToken() {
            String token = props.getToken();
            return token != null && !token.isBlank();
        }

        /**
         * Install a synthetic authenticated principal so HealthEndpoint's when_authorized check (principal != null) reveals component
         * detail and protected endpoints are reachable.
         */
        private static void authenticate() {
            UsernamePasswordAuthenticationToken authn =
                new UsernamePasswordAuthenticationToken(ACTUATOR_PRINCIPAL, "N/A", AuthorityUtils.createAuthorityList(ACTUATOR_AUTHORITY));
            SecurityContext ctx = SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(authn);
            SecurityContextHolder.setContext(ctx);
        }

        /**
         * True only for paths that must stay open to the load balancer without a token: the shallow health aggregate and the
         * liveness/readiness probe groups. Component-detail paths (e.g. /actuator/health/db) are NOT open — they require the token.
         */
        private boolean isOpenHealthPath(HttpServletRequest req) {
            String uri = req.getRequestURI();
            return uri.endsWith("/health") || uri.endsWith("/health/liveness") || uri.endsWith("/health/readiness");
        }

        /** Constant-time comparison to avoid leaking the token via timing. */
        private static boolean constantTimeEquals(String a, String b) {
            if (a == null || b == null) return false;
            byte[] aa = a.getBytes(StandardCharsets.UTF_8);
            byte[] bb = b.getBytes(StandardCharsets.UTF_8);
            if (aa.length != bb.length) return false;
            int diff = 0;
            for (int i = 0; i < aa.length; i++) diff |= aa[i] ^ bb[i];
            return diff == 0;
        }
    }
}
