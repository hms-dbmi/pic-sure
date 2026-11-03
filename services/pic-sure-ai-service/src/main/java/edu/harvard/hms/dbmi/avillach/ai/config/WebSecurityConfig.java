package edu.harvard.hms.dbmi.avillach.ai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * This service performs no JWT validation and no authority-based authorization of its own -- the gateway is the sole PSAMA client and has
 * already authorized the request before it arrives here, the same trust boundary {@code pic-sure-operations-service}'s
 * {@code WebSecurityConfig} documents. Unlike that service, there are no per-path authority rules to declare: the one thing this service
 * requires (a present, well-formed {@code Authorization} header) is enforced by {@link CallerContextArgumentResolver} at the MVC layer, not
 * here. This chain exists only to disable CSRF and sessions and to permit every path at the Spring Security layer, leaving
 * {@link ActuatorSecurityConfig}'s separate {@code @Order(0)} chain to gate {@code /actuator/**}.
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
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll()).build();
    }
}
