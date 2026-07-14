package edu.harvard.hms.dbmi.avillach.commons.identity;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Header-name contract between the gateway's identity/introspection filters (which set these headers on the way in) and the WAR's
 * GatewayHeaderFilter (which rebuilds the SecurityContext from them).
 */
public final class GatewayUserResolver {

    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_USER_SUBJECT = "X-User-Subject";
    public static final String HEADER_USER_EMAIL = "X-User-Email";
    public static final String HEADER_USER_ROLES = "X-User-Roles";
    public static final String HEADER_USER_PRIVILEGES = "X-User-Privileges";

    private GatewayUserResolver() {}

    /**
     * Builds a {@link GatewayUser} from the identity headers on the given request. Empty when {@link #HEADER_USER_ID} is missing or blank,
     * since a request without a resolved user id carries no identity to rebuild.
     */
    public static Optional<GatewayUser> resolve(HttpServletRequest request) {
        String userId = request.getHeader(HEADER_USER_ID);
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }

        String subject = request.getHeader(HEADER_USER_SUBJECT);
        String email = request.getHeader(HEADER_USER_EMAIL);
        String roles = request.getHeader(HEADER_USER_ROLES);
        Set<String> privileges = splitPrivileges(request.getHeader(HEADER_USER_PRIVILEGES));

        return Optional.of(new GatewayUser(userId, subject, email, roles, privileges));
    }

    private static Set<String> splitPrivileges(String header) {
        if (header == null || header.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(header.split(",")).map(String::trim).filter(value -> !value.isEmpty())
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
