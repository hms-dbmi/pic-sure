package edu.harvard.hms.dbmi.avillach.conventions.securityfixtures.rolechecks;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** Filter-chain URL rules that check roles, written inside the configurer lambda as services write them. */
public class UrlRoleRules {

    public SecurityFilterChain chain(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(
            auth -> auth.requestMatchers("/admin/**").hasRole("ADMIN").requestMatchers("/ops/**").hasAnyRole("ADMIN", "SUPER_ADMIN")
                .anyRequest().authenticated()
        ).build();
    }
}
