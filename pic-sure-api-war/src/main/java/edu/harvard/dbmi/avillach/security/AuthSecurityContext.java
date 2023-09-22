package edu.harvard.dbmi.avillach.security;

import edu.harvard.dbmi.avillach.data.entity.AuthUser;

import javax.json.Json;
import javax.ws.rs.core.SecurityContext;
import java.security.Principal;

public class AuthSecurityContext implements SecurityContext {
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
        if (user.getRoles() != null)
            return user.getRoles().contains(role);
        return false;
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