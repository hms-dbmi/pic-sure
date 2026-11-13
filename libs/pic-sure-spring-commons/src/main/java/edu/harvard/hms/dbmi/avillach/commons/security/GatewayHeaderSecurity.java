package edu.harvard.hms.dbmi.avillach.commons.security;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * The security setup every service behind the gateway shares: CSRF off and no server-side session, because each request carries its own
 * trust in the gateway's {@code X-User-*} headers, and {@link GatewayPrivilegesFilter} run before authorization so the path rules and
 * {@code @PreAuthorize} guards can see who the caller is. A service adds its own path rules to the result.
 */
public final class GatewayHeaderSecurity {

    private GatewayHeaderSecurity() {}

    public static HttpSecurity statelessWithGatewayPrivileges(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(new GatewayPrivilegesFilter(), AuthorizationFilter.class);
    }
}
