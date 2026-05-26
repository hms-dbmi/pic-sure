package edu.harvard.dbmi.avillach.visualization.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final ObjectMapper objectMapper;

    public SecurityConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // CSRF disabled: this is a stateless API-to-API service with no browser sessions or cookies.
        http.csrf(csrf -> csrf.disable()).sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .httpBasic(basic -> basic.disable()).formLogin(form -> form.disable())
            .authorizeHttpRequests(
                auth -> auth.requestMatchers("/actuator/health").permitAll().requestMatchers("/visualization/**").permitAll().anyRequest()
                    .denyAll()
            ).addFilterBefore(requestSourceFilter(), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public OncePerRequestFilter requestSourceFilter() {
        return new OncePerRequestFilter() {

            private static final Set<String> REQUIRES_REQUEST_SOURCE = Set.of("/visualization/v3/query/sync", "/visualization/v3/info");

            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
                throws ServletException, IOException {
                // Positive match: only these specific endpoints require the request-source header.
                // getRequestURI() returns the decoded, container-normalized path (../ resolved).
                String path = request.getRequestURI();
                if (REQUIRES_REQUEST_SOURCE.contains(path)) {
                    String requestSource = request.getHeader("request-source");
                    if (requestSource == null || requestSource.isBlank()) {
                        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                        response.setContentType("application/json");
                        objectMapper.writeValue(response.getOutputStream(), Map.of("error", "request-source header is required"));
                        return;
                    }
                }
                filterChain.doFilter(request, response);
            }
        };
    }
}
