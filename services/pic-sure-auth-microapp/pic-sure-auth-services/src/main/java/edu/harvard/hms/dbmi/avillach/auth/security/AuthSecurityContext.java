package edu.harvard.hms.dbmi.avillach.auth.security;

import edu.harvard.hms.dbmi.avillach.auth.data.entity.User;

import javax.ws.rs.core.SecurityContext;
import java.security.Principal;

/**
 * custom Security Context
 */
public class AuthSecurityContext implements SecurityContext {

    private User user;
    private String scheme;

    public AuthSecurityContext(User user, String scheme) {
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
            return user.getPrivilegeNameSet().contains(role);
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
}
