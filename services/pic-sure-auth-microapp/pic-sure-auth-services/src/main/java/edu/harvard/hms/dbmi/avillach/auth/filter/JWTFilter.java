package edu.harvard.hms.dbmi.avillach.auth.filter;

import edu.harvard.hms.dbmi.avillach.auth.entity.Application;
import edu.harvard.hms.dbmi.avillach.auth.entity.Role;
import edu.harvard.hms.dbmi.avillach.auth.exceptions.NotAuthorizedException;
import edu.harvard.hms.dbmi.avillach.auth.model.CustomApplicationDetails;
import edu.harvard.hms.dbmi.avillach.auth.model.CustomUserDetails;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.CustomUserDetailService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.TOSService;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming;
import edu.harvard.hms.dbmi.avillach.auth.utils.JWTUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;


/**
 * The main gate for PSAMA that filters all incoming requests against PSAMA.
 * <h3>Design Logic</h3>
 * <ul>
 *     <li>All incoming requests pass through this filter.</li>
 *     <li>To pass this filter, the incoming request needs a valid bearer token in its HTTP Authorization Header
 *     to represent a valid identity behind the token. </li>
 *     <li>In some cases, the incoming request doesn't need to hold a token. For example, when the request is to the <code>authentication</code>
 *     endpoint, <code>swagger.json</code>, or <code>swagger.html</code>.</li>
 * </ul>
 */

@Component
@Order(1)
public class JWTFilter extends OncePerRequestFilter {

    private final static Logger logger = LoggerFactory.getLogger(JWTFilter.class);

    private final TOSService tosService;

    private final String userClaimId;

    private final JWTUtil jwtUtil;
    private final CustomUserDetailService customUserDetailService;

    @Autowired
    public JWTFilter(TOSService tosService,
                     @Value("${application.user.id.claim}") String userClaimId, JWTUtil jwtUtil, CustomUserDetailService customUserDetailService) {
        this.tosService = tosService;
        this.userClaimId = userClaimId;
        this.jwtUtil = jwtUtil;
        this.customUserDetailService = customUserDetailService;
    }

    /**
     * Filter implementation that performs authentication and authorization checks based on the provided request headers.
     * The filter checks for the presence of the "Authorization" header and validates the token.
     * It sets the appropriate security context based on the type of token (long term token or PSAMA application token) and
     * performs the necessary checks to ensure that the user or application is authorized to access the requested resource.
     * This filter is called by the configured security filter chain in the SecurityConfig class.
     *
     * @param request     the HttpServletRequest object
     * @param response    the HttpServletResponse object
     * @param filterChain the FilterChain object
     * @throws IOException if an I/O error occurs during the execution of the filter
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain filterChain) throws IOException, ServletException {
        // Get headers from the request
        String authorizationHeader = request.getHeader("Authorization");

        if (!StringUtils.isNotBlank(authorizationHeader)) {
            // If the header is not present, we allow the request to pass through
            // without any authentication or authorization checks
            filterChain.doFilter(request, response);
        } else {
            // If the header is present, we need to check the token
            String token = authorizationHeader.substring(6).trim();
            logger.debug(" token: {}", token);

            // Parse the token
            Jws<Claims> jws = this.jwtUtil.parseToken(token);
            String userId = jws.getPayload().get(this.userClaimId, String.class);

            if (userId.startsWith(AuthNaming.LONG_TERM_TOKEN_PREFIX)) {
                // For profile information, we do indeed allow long term token
                // to be a valid token.
                if (request.getRequestURI().startsWith("/user/me")) {
                    // Get the subject claim, remove the LONG_TERM_TOKEN_PREFIX, and use that String value to
                    // look up the existing user.
                    String realClaimsSubject = jws.getPayload().getSubject().substring(AuthNaming.LONG_TERM_TOKEN_PREFIX.length() + 1);

                    setSecurityContextForUser(request, response, realClaimsSubject);
                } else {
                    logger.error("the long term token with subject, {}, cannot access to PSAMA.", userId);
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Long term tokens cannot be used to access to PSAMA.");
                }

            }

            if (userId.startsWith(AuthNaming.PSAMA_APPLICATION_TOKEN_PREFIX)) {
                logger.info("User Authentication Starts with {}", AuthNaming.PSAMA_APPLICATION_TOKEN_PREFIX);

                // Check if user is attempting to access the correct introspect endpoint. If not reject the request
                // log an error indicating the user's token may be being used by a malicious actor.
                if (!request.getRequestURI().endsWith("token/inspect")) {
                    logger.error("{} attempted to perform request {} token may be compromised.", userId, request.getRequestURI());
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "User is deactivated");
                }

                String applicationId = userId.split("\\|")[1];
                logger.info("Application ID: {}", applicationId);

                // Authenticate as Application
                CustomApplicationDetails customApplicationDetails = (CustomApplicationDetails) this.customUserDetailService.loadUserByUsername("application:" + applicationId);
                if (customApplicationDetails.getApplication() == null) {
                    logger.error("Cannot find an application by userId: {}", applicationId);
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Your token doesn't contain valid identical information, please contact admin.");
                    return;
                }

                Application application = customApplicationDetails.getApplication();

                if (!application.getToken().equals(token)) {
                    logger.error("filter() incoming application token - {} - is not the same as record, might because the token has been refreshed. Subject: {}", token, userId);
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Your token has been inactivated, please contact admin to grab you the latest one.");
                }

                // This is the application token that is being used to authenticate the user by other applications
                // Set the security context for the application
                setSecurityContextForApplication(request, customApplicationDetails);
            } else {
                logger.debug("UserID: {} is not a long term token and not a PSAMA application token.", userId);
                // Authenticate as User
                setSecurityContextForUser(request, response, jws.getPayload().getSubject());
            }

            filterChain.doFilter(request, response);
        }
    }

    private void setSecurityContextForApplication(HttpServletRequest request, CustomApplicationDetails authenticatedApplication) {
        logger.info("Setting security context for application: {}", authenticatedApplication.getApplication().getName());
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(authenticatedApplication, null, authenticatedApplication.getAuthorities());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        logger.info("Created authenticationToken object {} for application: {}", authentication, authenticatedApplication.getApplication().getName());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    /**
     * Sets the security context for the given user.
     * This method is responsible for validating the user claims, checking if the user is active,
     * ensuring that the user has accepted the terms of service (if enabled), validating user roles and privileges,
     * and setting the user object as an attribute in the request.
     *
     * @param request           the HttpServletRequest object
     * @param response          the HttpServletResponse object
     * @param realClaimsSubject the subject of the user's claims in the JWT token
     */
    private void setSecurityContextForUser(HttpServletRequest request, HttpServletResponse response, String realClaimsSubject) {
        logger.info("Setting security context for user: {}", realClaimsSubject);

        CustomUserDetails authenticatedUser = (CustomUserDetails) this.customUserDetailService.loadUserByUsername(realClaimsSubject);

        if (authenticatedUser == null) {
            logger.error("Cannot validate user claims, based on information stored in the JWT token.");
            throw new IllegalArgumentException("Cannot validate user claims, based on information stored in the JWT token.");
        }

        logger.info("User with email: {} is found.", authenticatedUser.getUser().getEmail());

        if (!authenticatedUser.getUser().isActive()) {
            logger.warn("User with ID: {} is deactivated.", authenticatedUser.getUser().getUuid());
            throw new NotAuthorizedException("User is deactivated");
        }

        logger.info("User with ID: {} is active.", authenticatedUser.getUser().getUuid());
        logger.info("Checking if user has accepted the latest terms of service.");
        if (!tosService.hasUserAcceptedLatest(authenticatedUser.getUser().getSubject())) {
            logger.info("User with ID: {} has not accepted the latest terms of service.", authenticatedUser.getUser().getUuid());
            //If user has not accepted terms of service and is attempted to get information other than the terms of service, don't authenticate
            try {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "User must accept terms of service");
            } catch (IOException e) {
                logger.error("Failed to send response.", e);
            }
        }

        // Get the user's roles
        Set<Role> userRoles = authenticatedUser.getUser().getRoles();

        // Check if the user has any roles and privileges associated with them
        if (userRoles == null || userRoles.isEmpty() || userRoles.stream().allMatch(role -> CollectionUtils.isEmpty(role.getPrivileges()))) {
            logger.error("User doesn't have any roles or privileges.");
            try {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "User doesn't have any roles or privileges.");
            } catch (IOException e) {
                logger.error("Failed to send response.", e);
            }
        }

        logger.info("User with email {} has roles {}.", authenticatedUser.getUser().getEmail(), userRoles != null ? userRoles.stream().map(Role::getName).collect(Collectors.joining(",")) : null);
        logger.info("User with email {} has privileges {}.", authenticatedUser.getUser().getEmail(), userRoles != null ? userRoles.stream().map(Role::getPrivileges).collect(Collectors.toSet()) : null);
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(authenticatedUser, null, authenticatedUser.getAuthorities());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);

    }

}