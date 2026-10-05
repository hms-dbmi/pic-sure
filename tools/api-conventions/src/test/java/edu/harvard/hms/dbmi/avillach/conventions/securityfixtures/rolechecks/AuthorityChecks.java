package edu.harvard.hms.dbmi.avillach.conventions.securityfixtures.rolechecks;

import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** Authority checks, and a domain method that shares a role check's name. None of it is a role check. */
public class AuthorityChecks {

    public SecurityFilterChain chain(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(
            auth -> auth.requestMatchers("/admin/**").hasAuthority("SUPER_ADMIN").requestMatchers("/ops/**")
                .hasAnyAuthority("ADMIN", "SUPER_ADMIN").anyRequest().authenticated()
        ).build();
    }

    public AuthorizationManager<Object> admin() {
        return AuthorityAuthorizationManager.hasAuthority("ADMIN");
    }

    public boolean memberIsAdmin(Membership membership) {
        return membership.hasRole("ADMIN");
    }

    /** A PIC-SURE type with its own hasRole, which the rule must not confuse with the framework's. */
    public static class Membership {

        public boolean hasRole(String role) {
            return "ADMIN".equals(role);
        }
    }
}
