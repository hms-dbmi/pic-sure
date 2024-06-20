package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import edu.harvard.hms.dbmi.avillach.auth.entity.Application;
import edu.harvard.hms.dbmi.avillach.auth.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.exceptions.NotAuthorizedException;
import edu.harvard.hms.dbmi.avillach.auth.model.CustomApplicationDetails;
import edu.harvard.hms.dbmi.avillach.auth.model.CustomUserDetails;
import edu.harvard.hms.dbmi.avillach.auth.model.TokenInspection;
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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;


@Service
public class TokenService {

    private final static Logger logger = LoggerFactory.getLogger(TokenService.class);

    private final AuthorizationService authorizationService;

    private final UserRepository userRepository;

    private final long tokenExpirationTime;

    private static final long defaultTokenExpirationTime = 1000L * 60 * 60; // 1 hour
    private final JWTUtil jwtUtil;

    @Autowired
    public TokenService(AuthorizationService authorizationService, UserRepository userRepository,
                        @Value("${application.token.expiration.time}") long tokenExpirationTime, JWTUtil jwtUtil) {
        this.authorizationService = authorizationService;
        this.userRepository = userRepository;
        this.tokenExpirationTime = tokenExpirationTime > 0 ? tokenExpirationTime : defaultTokenExpirationTime;
        this.jwtUtil = jwtUtil;
    }

    public Map<String, Object> inspectToken(Map<String, Object> inputMap) {
        logger.info("TokenInspect starting...");
        TokenInspection tokenInspection;
        try {
            tokenInspection = validateToken(inputMap);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        if (tokenInspection.getMessage() != null) {
            tokenInspection.addField("message", tokenInspection.getMessage());
        }

        logger.info("Finished token introspection.");
        return tokenInspection.getResponseMap();
    }

    private TokenInspection validateToken(Map<String, Object> inputMap) throws IllegalAccessException {
        logger.debug("_inspectToken, the incoming token map is: {}", inputMap.entrySet()
                .stream()
                .map(entry -> entry.getKey() + " - " + entry.getValue())
                .collect(Collectors.joining(", ")));

        TokenInspection tokenInspection = new TokenInspection();
        String token = (String) inputMap.get("token");
        if (token == null || token.isEmpty()) {
            logger.error("Token - {} is blank", token);
            tokenInspection.setMessage("Token not found");
            return tokenInspection;
        }

        // parse the token based on client secret
        // don't need to check if jws is null or not, since parse function has already checked
        Jws<Claims> jws;
        try {
            jws = this.jwtUtil.parseToken(token);

            /*
             * token has been verified, now we remove it from inputMap, so further logs will not be able to log
             * the token accidentally!
             */
            inputMap.remove("token");
        } catch (NotAuthorizedException ex) {
            // only when the token is for sure invalid, we can dump it into the log.
            logger.error("_inspectToken() the token - {} - is invalid with exception: {}", token, ex.getMessage());
            tokenInspection.setMessage(ex.getMessage());
            return tokenInspection;
        }


        Application application;
        try {
            CustomApplicationDetails customApplicationDetails = (CustomApplicationDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            application = customApplicationDetails.getApplication();
        } catch (ClassCastException ex) {
            SecurityContext securityContext = SecurityContextHolder.getContext();
            String principalName = securityContext.getAuthentication().getName();
            logger.error("{} - {} - is trying to use token introspection endpoint, but it is not an application", principalName, principalName);
            throw new IllegalAccessException("The application token does not associate with an application but " + principalName);
        }

        // application null check should be finished when application token goes through the JWTFilter authentication process,
        // here we just double-check it to prevent a null application object goes further.
        if (application == null) {
            logger.error("_inspectToken() There is no application in securityContext, which shall not be.");
            throw new NullPointerException("Inner application error, please ask admin to check the log.");
        }

        String subject = jws.getPayload().getSubject();

        // get the user based on subject field in token
        User user;

        // check if the token is the special LONG_TERM_TOKEN,
        // the differences between this special token and normal token is
        // one user only has one long_term_token stored in database,
        // this token needs to be exactly the same as the database one.
        // If the token refreshed, the old one will be invalid. But normal
        // token will not invalid the old ones if refreshed.
        boolean isLongTermToken = false;
        if (subject.startsWith(AuthNaming.LONG_TERM_TOKEN_PREFIX)) {
            subject = subject.substring(AuthNaming.LONG_TERM_TOKEN_PREFIX.length() + 1);
            isLongTermToken = true;
        }

        user = this.userRepository.findBySubject(subject);
        logger.info("_inspectToken() does user with subject - {} - exists in database", subject);
        if (user == null) {
            logger.error("_inspectToken() could not find user with subject {}", subject);
            tokenInspection.setMessage("user doesn't exist");
            return tokenInspection;
        }

        //Essentially we want to return jws.getBody() with an additional active: true field
        //only under certain circumstances, the token will return active
        boolean isAuthorizationPassed = false;
        String errorMsg = null;

        // long term token needs to be the same as the token in the database user table, if
        // not the token might has been compromised, which will not go through the authorization check
        boolean isLongTermTokenCompromised = false;
        if (isLongTermToken && !token.equals(user.getToken())) {
            // in long_term_token mode, the token needs to be exactly the same as the token in user table
            isLongTermTokenCompromised = true;
            logger.error("_inspectToken User {}|{}is sending a long term token that is not matching the record in database user table.", user.getUuid(), user.getSubject());
            errorMsg = "Cannot find matched long term token, your token might have been refreshed.";
        }

        // we go through the authorization layer check only if we need to in order to improve the performance
        // the logic here, if the token associated with a user, we will start the authorization check.
        // If the current application has at least one privilege, the user must have one privilege associated to the application
        // pass the accessRule check if there is any accessRules associated with.
        if (application.getPrivileges() == null || application.getPrivileges().isEmpty()) {
            // if no privileges associated
            isAuthorizationPassed = true;
            //we still want to log this, though.
            logger.info("ACCESS_LOG ___ {},{},{} ___ has been granted access to execute query ___ {} ___ in application ___ {} ___ NO APP PRIVILEGES DEFINED", user.getUuid(), user.getEmail(), user.getName(), inputMap.get("request"), application.getName());
        } else if (!isLongTermTokenCompromised
                && user.getRoles() != null
                // The protocol between applications and PSAMA is application will
                // attach everything that needs to be verified in request field of inputMap
                // besides token. So here we should attach everything in request.
                && authorizationService.isAuthorized(application, inputMap.get("request"), user)) {
            isAuthorizationPassed = true;
        } else {
            // if isLongTermTokenCompromised flag is true,
            // the error message has already been set previously
            if (!isLongTermTokenCompromised)
                errorMsg = "User doesn't have enough privileges.";
        }

        if (isAuthorizationPassed) {
            tokenInspection.addField("active", true);
            ArrayList<String> roles = new ArrayList<>();
            for (Privilege p : user.getTotalPrivilege()) {
                roles.add(p.getName());
            }
            tokenInspection.addField("roles", String.join(",", roles));
        } else {
            tokenInspection.setMessage(errorMsg);
            return tokenInspection;
        }

        tokenInspection.addAllFields(jws.getPayload());

        // attach all privileges associated with the application to the responseMap
        tokenInspection.addField("privileges", user.getPrivilegeNameSetByApplication(application));

        logger.debug("_inspectToken() Successfully inspect and return response map: {}", tokenInspection.getResponseMap().entrySet()
                .stream()
                .map(entry -> entry.getKey() + " - " + entry.getValue())
                .collect(Collectors.joining(", ")));
        return tokenInspection;
    }

    public Map<String, String> refreshToken(String authorizationHeader) {
        logger.debug("RefreshToken starting...");

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication.getPrincipal();
        if (!(principal instanceof CustomUserDetails customUserDetails)) {
            logger.error("refreshToken() Principal is not an instance of User.");
            throw new NotAuthorizedException("User not found");
        }

        User user = customUserDetails.getUser();
        if (user == null || user.getUuid() == null) {
            logger.error("refreshToken() Stored user doesn't have a uuid.");
            return Map.of("error", "Inner application error, please contact admin.");
        }

        Optional<User> loadUser = this.userRepository.findById(user.getUuid());
        if (loadUser.isEmpty()) {
            logger.error("refreshToken() When retrieving current user, it returned null, the user might be removed from database");
            throw new NotAuthorizedException("User doesn't exist anymore");
        }

        if (!loadUser.get().isActive()) {
            logger.error("refreshToken() The user has just been deactivated.");
            throw new NotAuthorizedException("User has been deactivated.");
        }

        String subject = loadUser.get().getSubject();
        if (subject == null || subject.isEmpty()) {
            logger.error("refreshToken() subject doesn't exist in the user.");
            return Map.of("error", "Inner application error, please contact admin.");
        }

        // parse origin token
        Jws<Claims> jws;
        try {
            String token = JWTUtil.getTokenFromAuthorizationHeader(authorizationHeader).orElseThrow(() -> new NotAuthorizedException("Token not found"));
            jws = this.jwtUtil.parseToken(token);
        } catch (NotAuthorizedException ex) {
            return Map.of("error", "Cannot parse original token");
        }

        Claims claims = jws.getPayload();
        if (!subject.equals(claims.getSubject())) {
            logger.error("refreshToken() user subject is not the same as the subject of the input token");
            return Map.of("error", "Inner application error, try again or contact admin.");
        }

        Date expirationDate = new Date(Calendar.getInstance().getTimeInMillis() + this.tokenExpirationTime);
        String refreshedToken = this.jwtUtil.createJwtToken(
                claims.getId(),
                claims.getIssuer(),
                claims,
                subject,
                this.tokenExpirationTime);

        logger.debug("Finished RefreshToken and new token has been generated.");
        return Map.of(
                "token", refreshedToken,
                "expirationDate", ZonedDateTime.ofInstant(expirationDate.toInstant(), ZoneOffset.UTC).toString()
        );
    }


}
