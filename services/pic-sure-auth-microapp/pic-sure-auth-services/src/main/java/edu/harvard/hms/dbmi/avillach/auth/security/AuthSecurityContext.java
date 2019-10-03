package edu.harvard.hms.dbmi.avillach.auth.security;

import edu.harvard.hms.dbmi.avillach.auth.data.entity.Application;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.User;

import javax.ws.rs.core.SecurityContext;
import java.security.Principal;

/**
 * <p>Implements the SecurityContext interface for JWTFilter to use.</p>
 */
public class AuthSecurityContext implements SecurityContext {

    private User user;
    private Application application;
    private String scheme;

    public AuthSecurityContext(User user, String scheme) {
        this.user = user;
        this.scheme = scheme;
    }

    public AuthSecurityContext(Application application, String scheme) {
        this.application = application;
        this.scheme = scheme;
    }

    @Override
    public Principal getUserPrincipal() {
        return this.user == null ? this.application : this.user;
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
