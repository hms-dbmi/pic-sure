package edu.harvard.hms.dbmi.avillach.commons.identity;

import java.util.Set;

/**
 * Immutable holder for the identity the gateway has already resolved (via header injection or introspection) and forwards downstream.
 * {@code privileges} are the real {@code @RolesAllowed} signal; {@code roles} is carried through as an opaque, comma-joined string for
 * logging/back-compat.
 */
public final class GatewayUser {

    private final String userId;
    private final String subject;
    private final String email;
    private final String roles;
    private final Set<String> privileges;

    public GatewayUser(String userId, String subject, String email, String roles, Set<String> privileges) {
        this.userId = userId;
        this.subject = subject;
        this.email = email;
        this.roles = roles;
        this.privileges = privileges == null ? Set.of() : Set.copyOf(privileges);
    }

    public String getUserId() {
        return userId;
    }

    public String getSubject() {
        return subject;
    }

    public String getEmail() {
        return email;
    }

    public String getRoles() {
        return roles;
    }

    public Set<String> getPrivileges() {
        return privileges;
    }
}
