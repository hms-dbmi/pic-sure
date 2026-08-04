package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.hms.dbmi.avillach.auth.entity.*;
import edu.harvard.hms.dbmi.avillach.auth.model.response.LongTermTokenResponse;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.UserService;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuditAttributes;
import edu.harvard.dbmi.avillach.logging.AuditEvent;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.*;

import static edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming.AuthRoleNaming.ADMIN;
import static edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming.AuthRoleNaming.SUPER_ADMIN;

/**
 * <p>Endpoint for service handling business logic for users.</p>
 */
@Tag(name = "User Management")
@RestController
@RequestMapping("/user")
public class UserController {

    private final static Logger logger = LoggerFactory.getLogger(UserController.class);

    private static final String INTERNAL_ERROR = "Inner application error, please contact admin.";

    private final UserService userService;


    @Autowired
    public UserController(UserService userService) {
        this.userService = userService;
    }

    @Operation(description = "GET information of one user with the UUID, requires ADMIN or SUPER_ADMIN roles")
    @AuditEvent(type = "OTHER", action = "user.read")
    @RolesAllowed({ADMIN, SUPER_ADMIN})
    @GetMapping(path = "/{userId}", produces = "application/json")
    public User getUserById(
        @Parameter(required = true, description = "The UUID of the user to fetch information about") @PathVariable("userId") String userId,
        HttpServletRequest request
    ) {
        AuditAttributes.putMetadata(request, "target_user_id", userId);
        return this.userService.getUserById(userId);
    }

    @Operation(description = "GET a list of existing users, requires ADMIN or SUPER_ADMIN roles")
    @AuditEvent(type = "OTHER", action = "user.list")
    @RolesAllowed({ADMIN, SUPER_ADMIN})
    @GetMapping(produces = "application/json")
    public List<User> getUserAll() {
        return this.userService.getAllUsers();
    }

    /**
     * The body is the saved users, always. It used to be either that list or a {@code {message, content}} envelope depending on whether an
     * email failed to send -- one endpoint, two shapes, decided by an unrelated side effect. The email warning is logged instead.
     */
    @Operation(description = "POST a list of users, requires ADMIN role")
    @AuditEvent(type = "ADMIN", action = "user.modify")
    @RolesAllowed({ADMIN})
    @PostMapping(produces = "application/json")
    public List<User> addUser(
        @Parameter(required = true, description = "A list of user in JSON format") @RequestBody List<User> users, HttpServletRequest request
    ) {
        AuditAttributes.putMetadata(request, "target_user_count", String.valueOf(users.size()));
        List<User> addedUsers = this.userService.addUsers(users);
        if (addedUsers == null) {
            throw new PicsureException(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", INTERNAL_ERROR);
        }

        logEmailWarning(addedUsers);
        return addedUsers;
    }

    @Operation(description = "Update a list of users, will only update the fields listed, requires ADMIN role")
    @AuditEvent(type = "ADMIN", action = "user.modify")
    @RolesAllowed({ADMIN})
    @PutMapping(produces = "application/json")
    public List<User> updateUser(@RequestBody List<User> users, HttpServletRequest request) {
        AuditAttributes.putMetadata(request, "target_user_count", String.valueOf(users.size()));
        List<User> updatedUsers = this.userService.updateUser(users);
        if (updatedUsers == null) {
            throw new PicsureException(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", INTERNAL_ERROR);
        }

        logEmailWarning(updatedUsers);
        return updatedUsers;
    }

    private void logEmailWarning(List<User> users) {
        String message = this.userService.sendUserUpdateEmailsFromResponse(users);
        if (message != null) {
            logger.warn("User update email(s) did not send: {}", message);
        }
    }

    /**
     * For the long term token, current logic is, every time a user hit this endpoint <code>/me</code> with the query parameter ?hasToken
     * presented, it will refresh the long term token.
     *
     */
    @Operation(description = "Retrieve information of current user")
    @AuditEvent(type = "ACCESS", action = "user.profile")
    @GetMapping(produces = "application/json", path = "/me")
    public User.UserForDisplay getCurrentUser(
        @RequestHeader("Authorization") String authorizationHeader,
        @Parameter(description = "Attribute that represents if a long term token will attach to the response") @RequestParam(
            name = "hasToken", required = false
        ) Boolean hasToken
    ) {
        User.UserForDisplay currentUser = this.userService.getCurrentUser(authorizationHeader, hasToken);

        if (currentUser == null) {
            throw new PicsureException(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", INTERNAL_ERROR);
        }

        return currentUser;
    }

    /**
     * For the long term token, current logic is, every time a user hit this endpoint /me with the query parameter ?hasToken presented, it
     * will refresh the long term token.
     *
     * @param httpHeaders the http headers
     * @return the refreshed long term token
     */
    @Operation(description = "refresh the long term token of current user")
    @AuditEvent(type = "ACCESS", action = "user.profile")
    @GetMapping(path = "/me/refresh_long_term_token", produces = "application/json")
    public LongTermTokenResponse refreshUserToken(@RequestHeader HttpHeaders httpHeaders, HttpServletRequest request) {
        AuditAttributes.putMetadata(request, "token_type", "long_term");
        LongTermTokenResponse refreshed = this.userService.refreshUserToken(httpHeaders);
        if (refreshed == null) {
            throw new PicsureException(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", INTERNAL_ERROR);
        }

        return refreshed;
    }

    /**
     * The consents of the caller. This declared {@code @PathVariable("userId")} against a path with no {@code {userId}} template, so Spring
     * could not resolve the argument and the endpoint answered 500 to everyone; the user now comes from the security context, exactly as
     * {@link #getCurrentUser} does.
     */
    @Operation(description = "Retrieve consents of current user")
    @AuditEvent(type = "ACCESS", action = "user.profile")
    @GetMapping(path = "/me/consents", produces = "application/json")
    public UserConsents getUserConsents() {
        UserConsents userConsents = this.userService.getUserConsents();

        if (userConsents == null) {
            throw new PicsureException(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", INTERNAL_ERROR);
        }

        return userConsents;
    }

}
