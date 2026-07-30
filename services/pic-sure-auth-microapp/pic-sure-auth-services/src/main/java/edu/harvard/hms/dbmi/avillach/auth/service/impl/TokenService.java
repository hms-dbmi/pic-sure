package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.contracts.auth.IntrospectionRequest;
import edu.harvard.dbmi.avillach.contracts.auth.IntrospectionResponse;
import edu.harvard.dbmi.avillach.contracts.auth.TargetedRequest;
import edu.harvard.hms.dbmi.avillach.auth.entity.Application;
import edu.harvard.hms.dbmi.avillach.auth.entity.Role;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.exceptions.NotAuthorizedException;
import edu.harvard.hms.dbmi.avillach.auth.model.*;
import edu.harvard.hms.dbmi.avillach.auth.repository.UserRepository;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.authorization.AuthorizationService;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming;
import edu.harvard.hms.dbmi.avillach.auth.utils.JWTUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.*;


@Service
public class TokenService {

    private final static Logger logger = LoggerFactory.getLogger(TokenService.class);

    /**
     * Only ever used to turn the consent-mutated {@code Query} into a tree. It must stay {@code valueToTree} -- writing the query as a
     * string would make {@code $.query.<field>} unresolvable for every caller that re-authorizes the returned query.
     */
    private final static ObjectMapper objectMapper = new ObjectMapper();

    private final AuthorizationService authorizationService;

    private final UserRepository userRepository;

    private final long tokenExpirationTime;

    // Default token expiration time set to 1 hour
    private static final long defaultTokenExpirationTime = 1000L * 60 * 60;
    private final JWTUtil jwtUtil;
    private final SessionService sessionService;
    private final UserService userService;

    @Autowired
    public TokenService(
        AuthorizationService authorizationService, UserRepository userRepository,
        @Value("${application.token.expiration.time}") long tokenExpirationTime, JWTUtil jwtUtil, SessionService sessionService,
        UserService userService
    ) {
        this.authorizationService = authorizationService;
        this.userRepository = userRepository;
        this.tokenExpirationTime = tokenExpirationTime > 0 ? tokenExpirationTime : defaultTokenExpirationTime;
        this.jwtUtil = jwtUtil;
        this.sessionService = sessionService;
        this.userService = userService;
    }

    public TokenIntrospectionResponse inspectToken(IntrospectionRequest introspectionRequest) {
        logger.info("TokenInspect starting...");
        try {
            return validateToken(introspectionRequest);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } finally {
            logger.info("Finished token introspection.");
        }
    }

    private TokenIntrospectionResponse validateToken(IntrospectionRequest introspectionRequest) throws IllegalAccessException {
        TargetedRequest targetedRequest = introspectionRequest == null ? null : introspectionRequest.request();
        logger.debug("_inspectToken, the incoming request is: {}", targetedRequest);

        String token = introspectionRequest == null ? null : introspectionRequest.token();
        if (token == null || token.isEmpty()) {
            logger.error("Token is blank");
            return TokenIntrospectionResponse.denied("Token not found");
        }

        // Parse token using client secret and verify signature. The token itself is never logged.
        Jws<Claims> jws;
        try {
            jws = this.jwtUtil.parseToken(token);
        } catch (NotAuthorizedException ex) {
            logger.error("_inspectToken() the presented token is invalid with exception: {}", ex.getMessage());
            return TokenIntrospectionResponse.denied(ex.getMessage());
        }

        Application application;
        try {
            CustomApplicationDetails customApplicationDetails =
                (CustomApplicationDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            application = customApplicationDetails.getApplication();
        } catch (ClassCastException ex) {
            SecurityContext securityContext = SecurityContextHolder.getContext();
            String principalName = securityContext.getAuthentication().getName();
            logger.error(
                "{} - {} - is trying to use token introspection endpoint, but it is not an application", principalName, principalName
            );
            throw new IllegalAccessException("The application token does not associate with an application but " + principalName);
        }

        // Verify application exists after JWT authentication
        if (application == null) {
            logger.error("_inspectToken() There is no application in securityContext, which shall not be.");
            throw new NullPointerException("Inner application error, please ask admin to check the log.");
        }

        // The verbatim token subject, long-term prefix included. This -- not the stripped lookup key below -- is what has always been
        // echoed back as "sub", and the gateway forwards it downstream as X-User-Sub.
        String tokenSubject = jws.getPayload().getSubject();
        String subject = tokenSubject;

        // Extract user from token subject
        User user;

        // Check for long-term token type
        // Long-term tokens:
        // - One per user stored in database
        // - Must match database token exactly
        // - Previous token invalidated on refresh
        // Regular tokens remain valid after refresh
        boolean isLongTermToken = false;
        if (subject.startsWith(AuthNaming.LONG_TERM_TOKEN_PREFIX)) {
            subject = subject.substring(AuthNaming.LONG_TERM_TOKEN_PREFIX.length() + 1);
            isLongTermToken = true;
        }

        user = this.userRepository.findBySubject(subject);
        logger.info("_inspectToken() does user with subject - {} - exists in database", subject);
        if (user == null) {
            logger.error("_inspectToken() could not find user with subject {}", subject);
            return TokenIntrospectionResponse.denied("user doesn't exist");
        }

        // Verify token is active and authorized
        boolean isAuthorizationPassed = false;
        String errorMsg = null;
        JsonNode mutatedQuery = null;
        boolean tokenRefreshed = false;
        String refreshedToken = null;

        // Verify long-term token matches database
        boolean isLongTermTokenCompromised = false;
        if (isLongTermToken && !token.equals(user.getToken())) {
            isLongTermTokenCompromised = true;
            logger.error(
                "_inspectToken User {}|{}is sending a long term token that is not matching the record in database user table.",
                user.getUuid(), user.getSubject()
            );
            errorMsg = "Cannot find matched long term token, your token might have been refreshed.";
        }

        // Authorize token based on application privileges
        if (application.getPrivileges() == null || application.getPrivileges().isEmpty()) {
            isAuthorizationPassed = true;
            logger.info(
                "ACCESS_LOG ___ {},{},{} ___ has been granted access to execute query ___ {} ___ in application ___ {} ___ NO APP PRIVILEGES DEFINED",
                user.getUuid(), user.getEmail(), user.getName(), targetedRequest, application.getName()
            );
        } else if (!isLongTermTokenCompromised && user.getRoles() != null) {
            EvaluateAccessRuleResult evaluateAccessRuleResult =
                authorizationService.isAuthorized(application, targetedRequest, user, isLongTermToken);
            isAuthorizationPassed = evaluateAccessRuleResult.result();
            // The consent-mutated query goes out as a JSON OBJECT. Serializing it as a string would break every
            // $.query.<field> access rule the caller's next hop is evaluated against.
            if (evaluateAccessRuleResult.query().isPresent()) {
                mutatedQuery = objectMapper.valueToTree(evaluateAccessRuleResult.query().get());
            }
        } else if (!isLongTermTokenCompromised) {
            errorMsg = "User doesn't have enough privileges.";
        }

        boolean active;
        String message = null;
        if (isLongTermToken && isAuthorizationPassed) {
            active = true;
        } else if (isAuthorizationPassed) {
            active = true;

            // Refresh token if expiring soon
            Date expiration = jws.getPayload().getExpiration();
            if (jwtUtil.shouldRefreshToken(expiration, tokenExpirationTime)) {
                logger.info("_inspectToken() Token is about to expire, refreshing token...");
                RefreshToken refreshResponse = refreshToken(token);
                if (refreshResponse instanceof ValidRefreshToken validRefreshToken) {
                    refreshedToken = validRefreshToken.token();
                    tokenRefreshed = true;
                } else if (refreshResponse instanceof InvalidRefreshToken invalidRefreshToken) {
                    message = invalidRefreshToken.error();
                    active = false;
                }
            }
        } else {
            message = errorMsg;
            active = false;
        }

        Set<String> userPrivileges = user.getPrivilegeNameSetByApplication(application);
        userPrivileges.addAll(user.getPrivilegeNameSet());

        IntrospectionResponse introspectionResponse = new IntrospectionResponse(
            active, userId(jws.getPayload(), user), tokenSubject, email(jws.getPayload(), user), roleNames(user),
            List.copyOf(userPrivileges), tokenRefreshed, refreshedToken, mutatedQuery
        );

        logger.debug("_inspectToken() Successfully inspected token; active={}, message={}", active, message);

        return new TokenIntrospectionResponse(introspectionResponse, message);
    }

    /**
     * The user UUID the gateway forwards as {@code X-User-Id}. The {@code uuid} claim minted at login stays authoritative -- it is what
     * PSAMA has always echoed -- with the resolved user row as a fallback for tokens minted before the claim existed.
     */
    private static String userId(Claims claims, User user) {
        Object claimed = claims.get("uuid");
        if (claimed != null) {
            return claimed.toString();
        }
        return user.getUuid() == null ? null : user.getUuid().toString();
    }

    private static String email(Claims claims, User user) {
        Object claimed = claims.get("email");
        return claimed != null ? claimed.toString() : user.getEmail();
    }

    /**
     * Role NAMES as a JSON array. This used to be {@code User#getRoleString()}, a comma-joined string that every consumer had to split back
     * apart -- and that silently corrupted any role name containing a comma.
     */
    private static List<String> roleNames(User user) {
        return user.getRoles() == null ? null : user.getRoles().stream().map(Role::getName).toList();
    }

    public RefreshToken refreshToken(String authorizationHeader) {
        logger.debug("RefreshToken starting...");

        String subject;
        Jws<Claims> jws;
        try {
            String token = JWTUtil.getTokenFromAuthorizationHeader(authorizationHeader)
                .orElseThrow(() -> new NotAuthorizedException("Token not found"));
            jws = this.jwtUtil.parseToken(token);
        } catch (NotAuthorizedException ex) {
            return new InvalidRefreshToken("Cannot parse original token.");
        }

        Claims claims = jws.getPayload();
        subject = claims.getSubject();
        if (subject == null || subject.isEmpty()) {
            logger.error("refreshToken() subject doesn't exist in the user.");
            return new InvalidRefreshToken("Inner application error, please contact admin.");
        }

        User loadUser = this.userRepository.findBySubject(subject);
        if (loadUser == null) {
            logger.error("refreshToken() When retrieving current user, it returned null, the user might be removed from database");
            return new InvalidRefreshToken("User doesn't exist anymore.");
        }

        if (!loadUser.isActive()) {
            logger.error("refreshToken() The user has just been deactivated.");
            return new InvalidRefreshToken("User has been deactivated.");
        }

        if (!JWTUtil.isLongTermToken(claims.getSubject()) && sessionService.isSessionExpired(claims.getSubject())) {
            logger.info("refreshToken() The user has just is being logged out. The user's session has expired.");
            return new InvalidRefreshToken("Your session has expired. Please log in again.");
        }

        Map<String, Object> claimsMap = new HashMap<>(claims);
        claimsMap.put("roles", userService.addRoleClaims(loadUser));

        Date expirationDate = new Date(Calendar.getInstance().getTimeInMillis() + this.tokenExpirationTime);
        String refreshedToken =
            this.jwtUtil.createJwtToken(claims.getId(), claims.getIssuer(), claimsMap, subject, this.tokenExpirationTime);

        logger.debug("Finished RefreshToken and new token has been generated.");
        return new ValidRefreshToken(refreshedToken, ZonedDateTime.ofInstant(expirationDate.toInstant(), ZoneOffset.UTC).toString());
    }

}
