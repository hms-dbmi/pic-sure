package edu.harvard.hms.dbmi.avillach.conventions.securityfixtures.rolechecks;

import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManager;

/** Authorization managers built from role checks. */
public class ManagerRoleChecks {

    public AuthorizationManager<Object> admin() {
        return AuthorityAuthorizationManager.hasRole("ADMIN");
    }

    public AuthorizationManager<Object> anyAdmin() {
        return AuthorityAuthorizationManager.hasAnyRole("ADMIN", "SUPER_ADMIN");
    }
}
