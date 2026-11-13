package edu.harvard.dbmi.avillach.logging.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Configuration
public class WebConfig {

    /**
     * Order 0: preflight is answered and short-circuited before ApiKeyAuthFilter (order 1), mirroring Javalin, where the CORS plugin ran
     * ahead of app.before("/audit"). An OPTIONS preflight therefore never needs an API key.
     */
    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilterRegistration(LoggingProperties properties) {
        CorsConfiguration config = new CorsConfiguration();
        // Valid with "*" because credentials are not allowed.
        config.addAllowedOrigin(properties.allowedOrigin());
        config.addAllowedMethod("GET");
        config.addAllowedMethod("POST");
        config.addAllowedMethod("OPTIONS");
        config.addAllowedHeader("*");

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(new CorsFilter(source));
        registration.addUrlPatterns("/*");
        registration.setOrder(0);
        return registration;
    }
}
