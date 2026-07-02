package edu.harvard.hms.dbmi.avillach.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Interim security wiring introduced alongside {@code spring-boot-starter-security} (Task 1). Without an explicit
 * {@link SecurityFilterChain} bean, Spring Boot's default auto-configuration requires authentication on every request, which breaks
 * transparent pass-through. This permits all requests at the Spring Security layer; the real auth boundary is the PSAMA introspection
 * filter chain landing in a later task (plan: {@code SecurityConfig} Step 3), which will extend this class with
 * {@code GatewaySecurityProperties} and the filter registrations (BufferingFilter, OpenAccessFilter, PsamaIntrospectionFilter, ...).
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain http(HttpSecurity http) throws Exception {
        // Gateway permits all at the Security layer; a later task adds the real auth boundary.
        return http.csrf(csrf -> csrf.disable()).authorizeHttpRequests(a -> a.anyRequest().permitAll()).build();
    }
}
