package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.hms.dbmi.avillach.auth.entity.*;
import edu.harvard.hms.dbmi.avillach.auth.exceptions.PicSureResponseException;
import edu.harvard.hms.dbmi.avillach.auth.model.request.UserCreateRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.request.UserUpdateRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.response.LongTermTokenResponse;
import edu.harvard.hms.dbmi.avillach.auth.model.response.PICSUREResponse;
import edu.harvard.hms.dbmi.avillach.auth.model.response.UserConsentsResponse;
import edu.harvard.hms.dbmi.avillach.auth.model.response.UserProfileResponse;
import edu.harvard.hms.dbmi.avillach.auth.model.response.UserResponse;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.UserService;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuditAttributes;
import edu.harvard.dbmi.avillach.logging.AuditEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.*;


/**
 * <p>Endpoint for service handling business logic for users.</p>
 */
@Tag(name = "User Management", description = "Users, their roles, and the caller's own profile")
@Controller
@RequestMapping("/user")
public class UserController {

    private final static Logger logger = LoggerFactory.getLogger(UserController.class);

    private final UserService userService;


    @Autowired
    public UserController(UserService userService) {
        this.userService = userService;
    }

    @Operation(summary = "Read one user", description = "GET information of one user with the UUID")
    @ApiResponse(responseCode = "200", description = "The user, without the long-term token, passport and identity provider metadata")
    @ApiResponse(responseCode = "400", description = "The id is not a UUID, or no user has it")
    @AuditEvent(type = "OTHER", action = "user.read")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'SUPER_ADMIN')")
    @GetMapping(path = "/{userId}", produces = "application/json")
    public ResponseEntity<UserResponse> getUserById(
        @Parameter(required = true, description = "The UUID of the user to fetch information about") @PathVariable("userId") String userId,
        HttpServletRequest request
    ) {
        AuditAttributes.putMetadata(request, "target_user_id", userId);
        return PICSUREResponse.success(UserResponse.from(this.userService.getUserById(userId)));
    }

    @Operation(summary = "List every user", description = "GET a list of existing users")
    @ApiResponse(responseCode = "200", description = "Every user, as a bare array")
    @AuditEvent(type = "OTHER", action = "user.list")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'SUPER_ADMIN')")
    @GetMapping(produces = "application/json")
    public ResponseEntity<List<UserResponse>> getUserAll() {
        return PICSUREResponse.success(this.userService.getAllUsers().stream().map(UserResponse::from).toList());
    }

    @Operation(summary = "Create users", description = "POST a list of users")
    @ApiResponse(responseCode = "200", description = "The created users, as a bare array")
    @AuditEvent(type = "ADMIN", action = "user.modify")
    @PreAuthorize("hasAnyAuthority('ADMIN')")
    @PostMapping(produces = "application/json")
    public ResponseEntity<List<UserResponse>> addUser(
        @Parameter(
            required = true, description = "The users to create, each naming its connection by id and its roles by UUID"
        ) @RequestBody List<@NotNull @Valid UserCreateRequest> users, HttpServletRequest request
    ) {
        AuditAttributes.putMetadata(request, "target_user_count", String.valueOf(users.size()));
        return respondWithSavedUsers(this.userService.createFrom(users));
    }

    @Operation(summary = "Update the given fields of users", description = "Update a list of users, will only update the fields listed")
    @ApiResponse(responseCode = "200", description = "The updated users, as a bare array")
    @AuditEvent(type = "ADMIN", action = "user.modify")
    @PreAuthorize("hasAnyAuthority('ADMIN')")
    @PutMapping(produces = "application/json")
    public ResponseEntity<List<UserResponse>> updateUser(
        @Parameter(
            required = true, description = "The users to update, each named by UUID; a field left out keeps its stored value"
        ) @RequestBody List<@NotNull @Valid UserUpdateRequest> users, HttpServletRequest request
    ) {
        AuditAttributes.putMetadata(request, "target_user_count", String.valueOf(users.size()));
        return respondWithSavedUsers(this.userService.updateFrom(users));
    }

    /**
     * Sends each saved user the access email and returns the users as a bare array. A failed email is logged and does not change the
     * response, so a client that reads the first element finds it whether or not the mail server answered.
     *
     * @param savedUsers the users the service persisted, or {@code null} when the security context held no caller
     * @return the saved users in their response shape
     * @throws PicSureResponseException with a 500 when {@code savedUsers} is {@code null}
     */
    private ResponseEntity<List<UserResponse>> respondWithSavedUsers(List<User> savedUsers) {
        if (savedUsers == null) {
            throw new PicSureResponseException(
                HttpStatus.INTERNAL_SERVER_ERROR, "Application error", "Inner application error, please contact admin."
            );
        }

        if (this.userService.sendUserUpdateEmailsFromResponse(savedUsers) != null) {
            logger.warn("Saved {} user(s) but could not send every access email", savedUsers.size());
        }

        return PICSUREResponse.success(savedUsers.stream().map(UserResponse::from).toList());
    }

    /**
     * Returns the caller's profile. The profile always carries the caller's long-term token, which is issued and saved on the first read.
     * The {@code hasToken} query parameter is accepted and has no effect.
     */
    @Operation(summary = "The caller's profile, with the long-term token", description = "Retrieve information of current user")
    @ApiResponse(responseCode = "200", description = "The caller's profile, with the long-term token")
    @AuditEvent(type = "ACCESS", action = "user.profile")
    @GetMapping(produces = "application/json", path = "/me")
    public ResponseEntity<UserProfileResponse> getCurrentUser(
        @RequestHeader("Authorization") String authorizationHeader,
        @Parameter(description = "Accepted for compatibility; the long-term token is included whether or not it is sent") @RequestParam(
            name = "hasToken", required = false
        ) Boolean hasToken
    ) {
        logger.info("getCurrentUser() authorizationHeader: {}, hasToken {}", authorizationHeader, hasToken);
        UserProfileResponse currentUser = this.userService.getCurrentUser(authorizationHeader, hasToken);

        if (currentUser == null) {
            throw new PicSureResponseException(
                HttpStatus.INTERNAL_SERVER_ERROR, "Application error", "Inner application error, please contact admin."
            );
        }

        return PICSUREResponse.success(currentUser);
    }

    /**
     * Issues the caller a new long-term token and returns it. The previous long-term token stops working.
     *
     * @param httpHeaders the http headers
     * @return the refreshed long term token
     */
    @Operation(summary = "Issue the caller a new long-term token", description = "refresh the long term tokne of current user")
    @ApiResponse(responseCode = "200", description = "A new long term token for the caller")
    @AuditEvent(type = "ACCESS", action = "user.profile")
    @GetMapping(path = "/me/refresh_long_term_token", produces = "application/json")
    public ResponseEntity<LongTermTokenResponse> refreshUserToken(@RequestHeader HttpHeaders httpHeaders, HttpServletRequest request) {
        AuditAttributes.putMetadata(request, "token_type", "long_term");
        Map<String, String> refreshed = this.userService.refreshUserToken(httpHeaders);
        if (refreshed == null) {
            throw new PicSureResponseException(
                HttpStatus.INTERNAL_SERVER_ERROR, "Application error", "Inner application error, please contact admin."
            );
        }

        return PICSUREResponse.success(new LongTermTokenResponse(refreshed.get("userLongTermToken")));
    }

    @Operation(summary = "The caller's consents", description = "Retrieve consents of current user")
    @ApiResponse(responseCode = "200", description = "The caller's consents")
    @AuditEvent(type = "ACCESS", action = "user.profile")
    @GetMapping(path = "/me/consents", produces = "application/json")
    public ResponseEntity<UserConsentsResponse> getUserConsents() {
        UserConsents userConsents = this.userService.getUserConsents();

        if (userConsents == null) {
            throw new PicSureResponseException(
                HttpStatus.INTERNAL_SERVER_ERROR, "Application error", "Inner application error, please contact admin."
            );
        }

        return PICSUREResponse.success(UserConsentsResponse.from(userConsents));
    }

}
