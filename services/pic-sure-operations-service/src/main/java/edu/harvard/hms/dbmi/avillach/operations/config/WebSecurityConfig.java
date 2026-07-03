package edu.harvard.hms.dbmi.avillach.operations.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * Security posture for the sole pic-sure-DB owner. This service performs NO JWT validation of its own -- the gateway is the sole PSAMA
 * client and has already authorized the request; {@link GatewayPrivilegesFilter} rebuilds a {@code SecurityContext} from the gateway's
 * {@code X-User-*} headers purely so the rules below can be expressed declaratively. Network ACLs (not this filter chain) are what actually
 * stop a client from reaching this service directly and forging the headers.
 *
 * <p>Path rules, in the order they are declared (first match wins): <ol> <li>{@code /actuator/health}, {@code /actuator/info},
 * {@code /v3/api-docs/**}, {@code /swagger-ui/**}, {@code /openapi/**} -- unauthenticated (ops/tooling surfaces, never gated behind caller
 * identity).</li> <li>{@code /configuration/admin/**} -- requires the {@code SUPER_ADMIN} authority. Declared before the broader
 * public-read rule below so it wins for every HTTP method, including {@code GET}.</li> <li>{@code GET /configuration/} and
 * {@code GET /configuration/*&#47;} -- public, UNAUTHENTICATED reads. The gateway allow-lists these exact paths at its introspection layer,
 * so they arrive with NO {@code X-User-*} headers at all; security here must not require an identity that will never be present.</li>
 * <li>{@code /internal/**} -- explicitly {@code permitAll()} at THIS layer (belt-and-suspenders, redundant with the catch-all rule below).
 * Spring Security performs no authentication/authorization for these paths because it isn't the real gate:
 * {@code edu.harvard.hms.dbmi.avillach.operations.query.InternalTokenFilter}, registered by {@code InternalTokenFilterConfig} against the
 * exact same {@code /internal/*} URL pattern, is what actually enforces the shared-secret check on every request the container routes
 * here.</li> <li>{@code /dataset/**} -- requires an authenticated caller, i.e. the gateway supplied {@code X-User-Id}. Spring Security's
 * {@code authenticated()} already excludes the default anonymous principal (see {@code AuthenticatedAuthorizationManager}), so a request
 * with no identity is correctly rejected rather than silently treated as authenticated.</li> <li>Everything else is permitted at this
 * layer; the config/dataset controllers built in later tasks enforce any remaining per-endpoint rules themselves (e.g. the
 * {@code NamedDataset} owner-email check).</li> </ol>
 *
 * <p>CSRF is disabled and sessions are stateless: every request carries its own trust via headers, there is no browser session to protect
 * and nothing is stored server-side between requests.
 */
@Configuration
public class WebSecurityConfig {

    static final String SUPER_ADMIN = "SUPER_ADMIN";

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // Populate the SecurityContext from X-User-Privileges BEFORE authorization runs.
            .addFilterBefore(new GatewayPrivilegesFilter(), AuthorizationFilter.class)
            .authorizeHttpRequests(
                auth -> auth.requestMatchers("/actuator/health", "/actuator/info", "/v3/api-docs/**", "/swagger-ui/**", "/openapi/**")
                    .permitAll().requestMatchers("/configuration/admin/**").hasAuthority(SUPER_ADMIN)
                    .requestMatchers(HttpMethod.GET, "/configuration/", "/configuration/*/").permitAll().requestMatchers("/internal/**")
                    .permitAll().requestMatchers("/dataset/**").authenticated().anyRequest().permitAll()
            ).build();
    }
}
