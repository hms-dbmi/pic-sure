package edu.harvard.dbmi.avillach.security;

import edu.harvard.dbmi.avillach.PicSureWarInit;
import edu.harvard.dbmi.avillach.data.entity.AuthUser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Priority;
import javax.inject.Inject;
import javax.ws.rs.Priorities;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.UriInfo;
import javax.ws.rs.ext.Provider;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Rebuilds the JAX-RS {@link javax.ws.rs.core.SecurityContext} from the gateway-set identity headers ({@code X-User-Id},
 * {@code X-User-Subject}, {@code X-User-Email}, {@code X-User-Roles}, {@code X-User-Privileges}) for any request path the gateway owns
 * auth for (see {@link GatewayAuthDelegation}). These header names MUST match the gateway's
 * {@code edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUserResolver} constants exactly - the two modules agree on this contract
 * without sharing a dependency.
 * <p>
 * CRITICAL: {@link AuthSecurityContext#isUserInRole(String)} checks the user's <em>privileges</em>, not a separate roles collection
 * (WildFly's {@code standalone.xml} sets {@code roles_claim=privileges}), so {@code X-User-Privileges} - not {@code X-User-Roles} - is
 * what gets mapped into the {@code AuthUser} field that backs {@code @RolesAllowed} / {@code isUserInRole} checks. Getting this backwards
 * would silently make every {@code @RolesAllowed} check fail (or, worse, pass) for gateway-owned requests.
 * <p>
 * TRUST NOTE: these headers are only safe to trust because (a) the gateway's identity-propagation filter strips any client-supplied
 * {@code X-User-*} headers before setting its own from a verified token-introspection result, and (b) the Apache httpd reverse proxy
 * only exposes the gateway to the outside world - WildFly is never directly reachable, so a client can never inject these headers
 * itself. If that network topology ever changes (WildFly exposed directly, or another untrusted hop added in front of the gateway),
 * this filter becomes a privilege-escalation vector and must be revisited.
 * <p>
 * Runs at {@link Priorities#AUTHENTICATION} (same as {@link JWTFilter}) so the {@code SecurityContext} is installed before RESTEasy's
 * {@code RolesAllowed} enforcement (Priorities.AUTHORIZATION) evaluates it. For paths the gateway does not own (interim
 * result/signed-url in Phase 2), this filter is a no-op and leaves {@code JWTFilter} to install its own context.
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
public class GatewayHeaderFilter implements ContainerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(GatewayHeaderFilter.class);

    static final String HEADER_USER_ID = "X-User-Id";
    static final String HEADER_USER_SUBJECT = "X-User-Subject";
    static final String HEADER_USER_EMAIL = "X-User-Email";
    static final String HEADER_USER_ROLES = "X-User-Roles";
    static final String HEADER_USER_PRIVILEGES = "X-User-Privileges";

    @Context
    UriInfo uriInfo;

    @Inject
    PicSureWarInit picSureWarInit;

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        String rawPath = requestContext.getUriInfo().getPath();
        String path = rawPath.startsWith("/") ? rawPath : "/" + rawPath;

        GatewayAuthDelegation delegation =
            new GatewayAuthDelegation(picSureWarInit.isGatewayOwnsAuth(), picSureWarInit.isGatewayOwnsQueryReadAuth());
        if (!delegation.gatewayOwnsAuth(path)) {
            // Interim path (result/signed-url in Phase 2), or the master switch is off: JWTFilter owns full
            // auth and installs its own SecurityContext from token introspection. Do not touch it here.
            return;
        }

        String userId = requestContext.getHeaderString(HEADER_USER_ID);
        if (userId == null || userId.isBlank()) {
            // Gateway owns this path but there is no resolved identity (e.g. an open-access request it
            // already authorized). Nothing to rebuild - leave the SecurityContext as-is.
            return;
        }

        String subject = requestContext.getHeaderString(HEADER_USER_SUBJECT);
        String email = requestContext.getHeaderString(HEADER_USER_EMAIL);
        String roles = requestContext.getHeaderString(HEADER_USER_ROLES);
        Set<String> privileges = splitPrivileges(requestContext.getHeaderString(HEADER_USER_PRIVILEGES));

        AuthUser user = new AuthUser().setUserId(userId).setSubject(subject).setEmail(email).setRoles(roles).setPrivileges(privileges);

        requestContext.setProperty("username", userId);
        requestContext.setSecurityContext(new AuthSecurityContext(user, uriInfo.getRequestUri().getScheme()));
        logger.debug("GatewayHeaderFilter installed AuthSecurityContext for user '{}' from gateway headers.", userId);
    }

    private static Set<String> splitPrivileges(String header) {
        if (header == null || header.isBlank()) {
            return Collections.emptySet();
        }
        return Arrays.stream(header.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }
}
