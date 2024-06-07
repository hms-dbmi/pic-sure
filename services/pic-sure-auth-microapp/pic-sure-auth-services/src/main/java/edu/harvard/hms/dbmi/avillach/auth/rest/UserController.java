package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.hms.dbmi.avillach.auth.entity.*;
import edu.harvard.hms.dbmi.avillach.auth.model.response.PICSUREResponse;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.security.RolesAllowed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.*;

import static edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming.AuthRoleNaming.ADMIN;
import static edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming.AuthRoleNaming.SUPER_ADMIN;

/**
 * <p>Endpoint for service handling business logic for users.</p>
 */
@Tag(name = "User Management")
@Controller
@RequestMapping("/user")
public class UserController {

    private final static Logger logger = LoggerFactory.getLogger(UserController.class);

    private final UserService userService;


    @Autowired
    public UserController(UserService userService) {
        this.userService = userService;
    }

    @Operation(description = "GET information of one user with the UUID, requires ADMIN or SUPER_ADMIN roles")
    @RolesAllowed({ADMIN, SUPER_ADMIN})
    @GetMapping(path = "/{userId}", produces = "application/json")
    public ResponseEntity<User> getUserById(
            @Parameter(required = true, description = "The UUID of the user to fetch information about")
            @PathVariable("userId") String userId) {
        User userById = this.userService.getUserById(userId);
        return PICSUREResponse.success(userById);
    }

    @Operation(description = "GET a list of existing users, requires ADMIN or SUPER_ADMIN roles")
    @RolesAllowed({ADMIN, SUPER_ADMIN})
    @GetMapping(produces = "application/json")
    public ResponseEntity<List<User>> getUserAll() {
        List<User> entityAll = this.userService.getAllUsers();
        return PICSUREResponse.success(entityAll);
    }

    @Operation(description = "POST a list of users, requires ADMIN role")
    @RolesAllowed({ADMIN})
    @PostMapping(produces = "application/json")
    public ResponseEntity<?> addUser(
            @Parameter(required = true, description = "A list of user in JSON format")
            @RequestBody List<User> users) {
        List<User> addedUsers = this.userService.addUsers(users);
        if (addedUsers == null) {
            return PICSUREResponse.applicationError("Inner application error, please contact admin.");
        }

        String message = this.userService.sendUserUpdateEmailsFromResponse(addedUsers);
        if (message != null) {
            return PICSUREResponse.success(message, addedUsers);
        }

        return PICSUREResponse.success(addedUsers);
    }

    @Operation(description = "Update a list of users, will only update the fields listed, requires ADMIN role")
    @RolesAllowed({ADMIN})
    @PutMapping(produces = "application/json")
    public ResponseEntity<?> updateUser(
            @RequestBody List<User> users) {
        List<User> updatedUsers = this.userService.updateUser(users);
        if (updatedUsers == null) {
            return PICSUREResponse.applicationError("Inner application error, please contact admin.");
        }

        String message = this.userService.sendUserUpdateEmailsFromResponse(updatedUsers);
        if (message != null) {
            return PICSUREResponse.success(message, updatedUsers);
        }

        return PICSUREResponse.success(updatedUsers);
    }

    /**
     * For the long term token, current logic is,
     * every time a user hit this endpoint <code>/me</code> with the query parameter ?hasToken presented,
     * it will refresh the long term token.
     *
     */
    @Operation(description = "Retrieve information of current user")
    @GetMapping(produces = "application/json", path = "/me")
    public ResponseEntity<?> getCurrentUser(
            @RequestHeader("Authorization") String authorizationHeader,
            @Parameter(description = "Attribute that represents if a long term token will attach to the response")
            @RequestParam(name = "hasToken", required = false) Boolean hasToken) {
        logger.info("getCurrentUser() authorizationHeader: {}, hasToken {}", authorizationHeader, hasToken);
        User.UserForDisplay currentUser = this.userService.getCurrentUser(authorizationHeader, hasToken);

        if (currentUser == null) {
            return PICSUREResponse.applicationError("Inner application error, please contact admin.");
        }

        return PICSUREResponse.success(currentUser);
    }

    @Operation(description = "Retrieve the queryTemplate of certain application by given application Id for the currentUser ")
    @GetMapping(path = "/me/queryTemplate/{applicationId}", produces = "application/json")
    public ResponseEntity<?> getQueryTemplate(
            @Parameter(description = "Application Id for the returning queryTemplate")
            @PathVariable("applicationId") String applicationId) {
        logger.info("getQueryTemplate() applicationId: {}", applicationId);
        Optional<String> mergedTemplate = this.userService.getQueryTemplate(applicationId);

        if (mergedTemplate.isEmpty()) {
            logger.error("getDefaultQueryTemplate() cannot find corresponding application by UUID: {}", applicationId);
            return PICSUREResponse.applicationError("Inner application error, please contact admin.");
        }

        return PICSUREResponse.success(Map.of("queryTemplate", mergedTemplate.orElse(null)));
    }

    @Operation(description = "Retrieve the queryTemplate of default application")
    @GetMapping(path = "/me/queryTemplate", produces = "application/json")
    public ResponseEntity<?> getQueryTemplate() {
        Map<String, String> defaultQueryTemplate = userService.getDefaultQueryTemplate();

        if (defaultQueryTemplate == null) {
            return PICSUREResponse.applicationError("Inner application error, please contact admin.");
        }

        return PICSUREResponse.success(defaultQueryTemplate);
    }

    /**
     * For the long term token, current logic is,
     * every time a user hit this endpoint /me
     * with the query parameter ?hasToken presented,
     * it will refresh the long term token.
     *
     * @param httpHeaders the http headers
     * @return the refreshed long term token
     */
    @Operation(description = "refresh the long term tokne of current user")
    @GetMapping(path = "/me/refresh_long_term_token", produces = "application/json")
    public ResponseEntity<?> refreshUserToken(
            @RequestHeader HttpHeaders httpHeaders) {
        Map<String, String> stringStringMap = this.userService.refreshUserToken(httpHeaders);
        if (stringStringMap != null) {
            return PICSUREResponse.success(stringStringMap);
        }

        return PICSUREResponse.applicationError("Inner application error, please contact admin.");
    }

}
