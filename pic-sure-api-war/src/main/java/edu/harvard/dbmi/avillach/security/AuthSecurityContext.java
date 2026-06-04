package edu.harvard.dbmi.avillach.security;

import edu.harvard.dbmi.avillach.data.entity.AuthUser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.json.Json;
import javax.ws.rs.core.SecurityContext;
import java.security.Principal;
import java.util.Arrays;

public class AuthSecurityContext implements SecurityContext {
    private static final Logger logger = LoggerFactory.getLogger(AuthSecurityContext.class);
    private AuthUser user;
    private String scheme;

    public AuthSecurityContext(AuthUser user, String scheme) {
        this.user = user;
        this.scheme = scheme;
    }

    @Override
    public Principal getUserPrincipal() {
        return this.user;
    }

    @Override
    public boolean isUserInRole(String role) {
        boolean result = false;
        if (user.getPrivileges() != null) {
            result = Arrays.stream(user.getPrivileges().split(","))
                         .map(String::trim)
                         .anyMatch(r -> r.equals(role));
        }

        logger.info("isUserInRole() check: requestedRole='{}', userRoles='{}', result={}", role, user.getPrivileges(), result);
        return result;
    }

    @Override
    public boolean isSecure() {
        return "https".equals(this.scheme);
    }

    @Override
    public String getAuthenticationScheme() {
        return SecurityContext.DIGEST_AUTH;
    }

    @Override
    public String toString(){
        return Json.createObjectBuilder()
            .add("scheme", scheme)
            .add("user", user.getName())
            .build().toString();
    }
}