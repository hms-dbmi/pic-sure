package edu.harvard.hms.dbmi.avillach.query.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * Security posture for the single HPDS ingress. This service performs NO JWT validation of its own -- the gateway is the sole PSAMA client
 * and has already authorized the request; {@link GatewayPrivilegesFilter} rebuilds a {@code SecurityContext} from the gateway's
 * {@code X-User-*} headers purely so the rules below can be expressed declaratively. Network ACLs (not this filter chain) are what actually
 * stop a client from reaching this service directly and forging the headers.
 *
 * <p>Path rules, in the order they are declared (first match wins): <ol> <li>{@code /actuator/health}, {@code /actuator/info},
 * {@code /openapi/**}, {@code /swagger-ui/**}, {@code /v3/api-docs/**} -- unauthenticated (ops/tooling surfaces, never gated behind caller
 * identity).</li> <li>{@code /hpds/**} -- requires an authenticated caller, i.e. the gateway supplied {@code X-User-Id}. Spring Security's
 * {@code authenticated()} already excludes the default anonymous principal (see {@code AuthenticatedAuthorizationManager}), so a request
 * with no identity is correctly rejected rather than silently treated as authenticated.</li> <li>Everything else is permitted at this
 * layer; the controllers built in later tasks enforce any remaining per-endpoint rules themselves. There is deliberately NO
 * {@code /internal/**} endpoint on this service -- dispatch/persistence lives on pic-sure-operations-service, reached via
 * {@link edu.harvard.hms.dbmi.avillach.query.operations.OperationsClient}.</li> </ol>
 *
 * <p>CSRF is disabled and sessions are stateless: every request carries its own trust via headers, there is no browser session to protect
 * and nothing is stored server-side between requests.
 */
@Configuration
public class WebSecurityConfig {

    @Bean
    @Order(10) // yields /actuator/** to ActuatorSecurityConfig's @Order(0) chain.
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // Populate the SecurityContext from X-User-Privileges BEFORE authorization runs.
            .addFilterBefore(new GatewayPrivilegesFilter(), AuthorizationFilter.class)
            .authorizeHttpRequests(
                auth -> auth.requestMatchers("/actuator/health", "/actuator/info", "/openapi/**", "/swagger-ui/**", "/v3/api-docs/**")
                    .permitAll().requestMatchers("/hpds/**").authenticated().anyRequest().permitAll()
            ).build();
    }
}
