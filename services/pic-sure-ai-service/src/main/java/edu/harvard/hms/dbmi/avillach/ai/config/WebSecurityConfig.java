package edu.harvard.hms.dbmi.avillach.ai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

import edu.harvard.hms.dbmi.avillach.commons.security.GatewayPrivilegesFilter;

/**
 * This service performs no JWT validation -- the gateway is the sole PSAMA client and has already introspected the token before the request
 * arrives here, the same trust boundary {@code pic-sure-operations-service}'s {@code WebSecurityConfig} documents. It does read the
 * gateway's {@code X-User-Privileges} header ({@link GatewayPrivilegesFilter}) so each chat handler can guard itself with
 * {@code @PreAuthorize}. There are no per-path rules here: a present, well-formed {@code Authorization} header is enforced by
 * {@link CallerContextArgumentResolver} at the MVC layer, and the privilege check by the handlers' guards, so that with the assistant
 * switched off ({@code picsure.ai.enabled=false}, no controller bean) every chat path still answers 404. This chain exists to disable CSRF
 * and sessions, populate the privileges, and permit every path at the Spring Security layer, leaving {@link ActuatorSecurityConfig}'s
 * separate {@code @Order(0)} chain to gate {@code /actuator/**}.
 *
 * <p>CSRF is disabled and sessions are stateless: every request carries its own trust via headers and there is no browser session to
 * protect. The conversation history kept between requests is keyed by who the caller is ({@code CallerContext#owner()}: their user id,
 * their open-access session id, or a hash of their token), not by a server-side session.
 */
@Configuration
@EnableMethodSecurity
public class WebSecurityConfig {

    @Bean
    @Order(10) // yields /actuator/** to ActuatorSecurityConfig's @Order(0) chain.
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // Populate the SecurityContext from X-User-Privileges BEFORE authorization runs.
            .addFilterBefore(new GatewayPrivilegesFilter(), AuthorizationFilter.class)
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll()).build();
    }
}
