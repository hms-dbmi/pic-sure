package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.harvard.hms.dbmi.avillach.auth.entity.*;
import edu.harvard.hms.dbmi.avillach.auth.model.CustomUserDetails;
import edu.harvard.hms.dbmi.avillach.auth.model.ras.RasDbgapPermission;
import edu.harvard.hms.dbmi.avillach.auth.repository.ConnectionRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.UserConsentsOverrideRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.UserConsentsRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.UserRepository;
import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingEvent;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.authorization.BdcConsentsBuilder;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming;
import edu.harvard.hms.dbmi.avillach.auth.utils.FenceMappingUtility;
import edu.harvard.hms.dbmi.avillach.auth.utils.JWTUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import jakarta.mail.MessagingException;
import jakarta.validation.constraints.NotNull;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static edu.harvard.hms.dbmi.avillach.auth.service.impl.RoleService.*;

@Service
public class UserService {

    private final Logger logger = LoggerFactory.getLogger(UserService.class.getName());

    private final BasicMailService basicMailService;
    private final TOSService tosService;
    private final UserRepository userRepository;
    private final ConnectionRepository connectionRepository;
    private final RoleService roleService;
    private final long tokenExpirationTime;
    private static final long defaultTokenExpirationTime = 1000L * 60 * 60; // 1 hour
    private final UserConsentsRepository userConsentsRepository;
    private final UserConsentsOverrideRepository userConsentsOverrideRepository;
    private final FenceMappingUtility fenceMappingUtility;

    public long longTermTokenExpirationTime;

    private final JWTUtil jwtUtil;

    private final List<String> tokenInclusionRoles;
    private final LoggingClient loggingClient;

    @Autowired
    public UserService(
        BasicMailService basicMailService, TOSService tosService, UserRepository userRepository, ConnectionRepository connectionRepository,
        RoleService roleService, UserConsentsRepository userConsentsRepository, UserConsentsOverrideRepository userConsentsOverrideRepository, FenceMappingUtility fenceMappingUtility,
        @Value("${application.token.expiration.time}") long tokenExpirationTime,
        @Value("${application.long.term.token.expiration.time}") long longTermTokenExpirationTime, JWTUtil jwtUtil,
        @Value("${application.token.inclusionRoles}") String tokenInclusionRoles, LoggingClient loggingClient
    ) {
        this.basicMailService = basicMailService;
        this.tosService = tosService;
        this.userRepository = userRepository;
        this.connectionRepository = connectionRepository;
        this.roleService = roleService;
        this.userConsentsRepository = userConsentsRepository;
        this.userConsentsOverrideRepository = userConsentsOverrideRepository;
        this.fenceMappingUtility = fenceMappingUtility;
        this.tokenExpirationTime = tokenExpirationTime > 0 ? tokenExpirationTime : defaultTokenExpirationTime;
        logger.info("Token Expiration Time : {}", tokenExpirationTime);
        this.jwtUtil = jwtUtil;

        long defaultLongTermTokenExpirationTime = 1000L * 60 * 60 * 24 * 30;
        this.longTermTokenExpirationTime =
            longTermTokenExpirationTime > 0 ? longTermTokenExpirationTime : defaultLongTermTokenExpirationTime;
        this.tokenInclusionRoles = Arrays.asList(tokenInclusionRoles.split(","));
        this.loggingClient = loggingClient;
    }

    public HashMap<String, String> getUserProfileResponse(UserClaims userClaims) {
        if (StringUtils.isBlank(userClaims.getSub())) {
            logger.warn("User subject is blank, cannot generate profile response");
            return null;
        }

        logger.debug("getUserProfileResponse() started");
        HashMap<String, String> responseMap = new HashMap<String, String>();

        HashMap<String, Object> claimsMap = userClaims.toHashMap();
        logger.debug("getUserProfileResponse() using claims:{}", claimsMap.toString());
        String token =
            this.jwtUtil.createJwtToken("whatever", "edu.harvard.hms.dbmi.psama", claimsMap, userClaims.getSub(), this.tokenExpirationTime);

        responseMap.put("token", token);
        logger.debug("getUserProfileResponse() .usedId field is set");
        responseMap.put("userId", userClaims.getSub());

        logger.debug("getUserProfileResponse() .email field is set");
        responseMap.put("email", userClaims.getEmail());

        logger.debug("getUserProfileResponse() acceptedTOS is set");
        boolean acceptedTOS = tosService.hasUserAcceptedLatest(userClaims.getSub());
        responseMap.put("acceptedTOS", "" + acceptedTOS);

        logger.debug("getUserProfileResponse() expirationDate is set");
        Date expirationDate = new Date(Calendar.getInstance().getTimeInMillis() + this.tokenExpirationTime);
        responseMap.put("expirationDate", ZonedDateTime.ofInstant(expirationDate.toInstant(), ZoneOffset.UTC).toString());

        logger.debug("getUserProfileResponse() uuid field is set");
        responseMap.put("uuid", userClaims.getUuid());

        logger.debug("getUserProfileResponse() finished");
        return responseMap;
    }

    public List<String> addRoleClaims(User user) {
        if (user != null && user.getRoles() != null) {
            return user.getRoles().stream().map(Role::getName).filter(tokenInclusionRoles::contains).collect(Collectors.toList());
        }

        return List.of();
    }

    public User getUserById(String userId) {
        Optional<User> user = this.userRepository.findById(UUID.fromString(userId));
        if (user.isEmpty()) {
            logger.error("getUserById() cannot find user by UUID: {}", userId);
            throw new IllegalArgumentException("Cannot find user by input UUID: " + userId);
        }

        return user.get();
    }

    public List<User> getAllUsers() {
        return this.userRepository.findAll();
    }

    public List<User> addUser(List<User> users) {
        return this.userRepository.saveAll(users);
    }

    /**
     * This check is to prevent non-super-admin user to create/remove a super admin role against a user(include themselves). Only super
     * admin user could perform such actions.
     *
     * <p> if operations not related to super admin role updates, this will return true. </p> <p> The logic here is checking the state of
     * the super admin role in the input and output users, if the state is changed, check if the user is a super admin to determine if the
     * user could perform the action.
     *
     * @param currentUser the user trying to perform the action
     * @param inputUser the user that is going to be updated
     * @param originalUser there could be no original user when adding a new user
     * @return true if the user could perform the action, false otherwise
     */
    private boolean allowUpdateSuperAdminRole(@NotNull User currentUser, @NotNull User inputUser, User originalUser) {

        // if current user is a super admin, this check will return true
        for (Role role : currentUser.getRoles()) {
            for (Privilege privilege : role.getPrivileges()) {
                if (privilege.getName().equals(AuthNaming.AuthRoleNaming.SUPER_ADMIN)) {
                    return true;
                }
            }
        }

        boolean inputUserHasSuperAdmin = false;
        boolean originalUserHasSuperAdmin = false;

        for (Role role : inputUser.getRoles()) {
            for (Privilege privilege : role.getPrivileges()) {
                if (privilege.getName().equals(AuthNaming.AuthRoleNaming.SUPER_ADMIN)) {
                    inputUserHasSuperAdmin = true;
                    break;
                }
            }
        }

        if (originalUser != null) {
            for (Role role : originalUser.getRoles()) {
                for (Privilege privilege : role.getPrivileges()) {
                    if (privilege.getName().equals(AuthNaming.AuthRoleNaming.SUPER_ADMIN)) {
                        originalUserHasSuperAdmin = true;
                        break;
                    }
                }
            }

            // when they equals, nothing has changed, a non super admin user could perform the action
            return inputUserHasSuperAdmin == originalUserHasSuperAdmin;
        } else {
            // if inputUser has super admin, it should return false
            return !inputUserHasSuperAdmin;
        }

    }

    @Transactional
    public List<User> addUsers(List<User> users) {
        SecurityContext securityContext = SecurityContextHolder.getContext();
        CustomUserDetails customUserDetails = (CustomUserDetails) securityContext.getAuthentication().getPrincipal();
        if (customUserDetails == null || customUserDetails.getUser() == null && customUserDetails.getUser().getUuid() == null) {
            logger.error("Security context didn't have a user stored.");
            return null;
        }

        User currentUser = customUserDetails.getUser();
        checkAssociation(users);
        for (User user : users) {
            logger.debug("Adding User {}", user);
            if (!allowUpdateSuperAdminRole(currentUser, user, null)) {
                logger.error(
                    "updateUser() user - {} - with roles [{}] - is not allowed to grant " + AuthNaming.AuthRoleNaming.SUPER_ADMIN
                        + " role when adding a user.",
                    currentUser.getUuid(), currentUser.getRoleString()
                );
                throw new IllegalArgumentException(
                    "Not allowed to add a user with a " + AuthNaming.AuthRoleNaming.SUPER_ADMIN + " privilege associated."
                );
            }

            if (user.getEmail() == null) {
                try {
                    logger.info("Parsing metadata for email address");
                    HashMap<String, String> metadata =
                        new HashMap<String, String>(new ObjectMapper().readValue(user.getGeneralMetadata(), Map.class));
                    List<String> emailKeys = metadata.keySet().stream().filter((key) -> key.toLowerCase().contains("email")).toList();
                    if (!emailKeys.isEmpty()) {
                        user.setEmail(metadata.get(emailKeys.getFirst()));
                    }
                } catch (IOException e) {
                    logger.error("Failed to parse metadata for email address", e);
                }
            }
        }

        users = addUser(users);
        return users;
    }

    /**
     * Check all referenced fields if they are already in a database. If they are in the database, then retrieve it by id and attach it to a
     * user object.
     *
     * @param users A list of users
     */
    private void checkAssociation(List<User> users) {
        for (User user : users) {
            if (user.getRoles() != null && !user.getRoles().isEmpty()) {
                Set<UUID> roleUuids = user.getRoles().stream().map(Role::getUuid).collect(Collectors.toSet());
                Set<Role> rolesFromDb = this.roleService.getRolesByIds(roleUuids);

                // If the size of the roles from the database is different from the input role UUIDs, then
                // we cannot find all roles by the input UUIDs.
                if (rolesFromDb.size() != roleUuids.size()) {
                    logger.error("checkAssociation() cannot find all roles by UUIDs: {}", roleUuids);
                    throw new IllegalArgumentException("Cannot find all roles by input UUIDs: " + roleUuids);
                }

                user.setRoles(rolesFromDb);
            } else {
                throw new IllegalArgumentException("User must have at least one role.");
            }

            if (user.getConnection() != null) {
                Optional<Connection> connection = this.connectionRepository.findById(user.getConnection().getId());
                user.setConnection(connection.orElse(null));
            }
        }
    }

    @Transactional
    public List<User> updateUser(List<User> users) {
        SecurityContext securityContext = SecurityContextHolder.getContext();
        CustomUserDetails customUserDetails = (CustomUserDetails) securityContext.getAuthentication().getPrincipal();
        if (customUserDetails == null || customUserDetails.getUser() == null && customUserDetails.getUser().getUuid() == null) {
            logger.error("Security context didn't have a user stored.");
            return null;
        }

        User currentUser = customUserDetails.getUser();
        checkAssociation(users);
        boolean allowUpdate = true;
        for (User user : users) {
            Optional<User> originalUser = this.userRepository.findById(user.getUuid());
            if (!allowUpdateSuperAdminRole(currentUser, user, originalUser.orElse(null))) {
                allowUpdate = false;
                break;
            }
        }

        if (allowUpdate) {
            users = this.userRepository.saveAll(users);
            return users;
        } else {
            logger.error(
                "updateUser() user - {} - with roles [{}] - is not allowed to grant or remove " + AuthNaming.AuthRoleNaming.SUPER_ADMIN
                    + " privilege.",
                currentUser.getUuid(), currentUser.getRoleString()
            );
            throw new IllegalArgumentException(
                "Not allowed to update a user with changes associated to " + AuthNaming.AuthRoleNaming.SUPER_ADMIN + " privilege."
            );
        }
    }

    public String sendUserUpdateEmailsFromResponse(List<User> addedUsers) {
        logger.debug("Sending email");
        try {
            for (User user : addedUsers) {
                try {
                    basicMailService.sendUsersAccessEmail(user);
                } catch (MessagingException e) {
                    logger.error("Failed to send email! {}", e.getLocalizedMessage());
                    logger.debug("Exception Trace: ", e);
                    return "  WARN - could not send email to user " + user.getEmail() + " see logs for more info";
                }
            }
        } catch (Exception e) {
            logger.error("Failed to send email - unhandled exception: ", e);
        }
        logger.debug("finished email sending method");
        return null;
    }

    @Transactional
    public User.UserForDisplay getCurrentUser(String authorizationHeader, Boolean hasToken) {
        SecurityContext securityContext = SecurityContextHolder.getContext();
        Optional<CustomUserDetails> customUserDetails =
            Optional.ofNullable((CustomUserDetails) securityContext.getAuthentication().getPrincipal());
        if (customUserDetails.isEmpty() || customUserDetails.get().getUser() == null) {
            logger.error("Security context didn't have a user stored.");
            return null;
        }

        User user = customUserDetails.get().getUser();
        if (user == null) {
            logger.error("When retrieving current user, it returned null");
            return null;
        }

        logger.info("getCurrentUser() user found: {}", user.getEmail());
        User.UserForDisplay userForDisplay = new User.UserForDisplay().setEmail(user.getEmail()).setPrivileges(user.getPrivilegeNameSet())
            .setUuid(user.getUuid().toString()).setAcceptedTOS(this.tosService.hasUserAcceptedLatest(user.getSubject()));

        if (user.getToken() != null && !user.getToken().isEmpty()) {
            userForDisplay.setToken(user.getToken());
        } else {
            user.setToken(generateUserLongTermToken(authorizationHeader, user));
            this.userRepository.save(user);
            userForDisplay.setToken(user.getToken());
        }

        return userForDisplay;
    }

    @Transactional
    public Map<String, String> refreshUserToken(HttpHeaders httpHeaders) {
        SecurityContext securityContext = SecurityContextHolder.getContext();
        CustomUserDetails customUserDetails = (CustomUserDetails) securityContext.getAuthentication().getPrincipal();
        if (customUserDetails == null || customUserDetails.getUser() == null || customUserDetails.getUser().getUuid() == null) {
            logger.error("Security context didn't have a user stored.");
            return null;
        }

        User user = customUserDetails.getUser();
        String authorizationHeader = httpHeaders.getFirst("Authorization");
        String longTermToken = generateUserLongTermToken(authorizationHeader, user);
        user.setToken(longTermToken);
        this.userRepository.save(user);

        return Map.of("userLongTermToken", longTermToken);
    }

    /**
     * Logic here is, retrieve the subject of the user from httpHeader. Then generate a long term one with LONG_TERM_TOKEN_PREFIX| in front
     * of the subject to be able to distinguish with regular ones, since long term token only generated for accessing certain things to, in
     * some degrees, decrease the insecurity.
     *
     * @param authorizationHeader the authorization header
     * @return the long term token
     * @throws IllegalArgumentException if the authorization header is not presented
     */
    private String generateUserLongTermToken(String authorizationHeader, User user) {
        if (!StringUtils.isNotBlank(authorizationHeader)) {
            throw new IllegalArgumentException("Authorization header is not presented.");
        }

        Optional<String> token = JWTUtil.getTokenFromAuthorizationHeader(authorizationHeader);
        if (token.isEmpty()) {
            throw new IllegalArgumentException("Token is not presented in the authorization header.");
        }

        Jws<Claims> jws = this.jwtUtil.parseToken(token.get());

        Claims claims = jws.getPayload();
        String tokenSubject = claims.getSubject();

        if (tokenSubject.startsWith(AuthNaming.LONG_TERM_TOKEN_PREFIX + "|")) {
            tokenSubject = tokenSubject.substring(AuthNaming.LONG_TERM_TOKEN_PREFIX.length() + 1);
        }

        Map<String, Object> claimsMap = new HashMap<>(claims);
        claimsMap.put("roles", addRoleClaims(user));

        return this.jwtUtil.createJwtToken(
            claims.getId(), claims.getIssuer(), claimsMap, AuthNaming.LONG_TERM_TOKEN_PREFIX + "|" + tokenSubject,
            this.longTermTokenExpirationTime
        );
    }

    public User changeRole(User currentUser, Set<Role> roles) {
        // set the users roles and merge the user
        currentUser.setRoles(roles);
        return this.userRepository.save(currentUser);
    }

    public User findBySubject(String username) {
        return this.userRepository.findBySubject(username);
    }

    public User save(User user) {
        return this.userRepository.save(user);
    }

    public User findOrCreate(User newUser) {
        logger.info(
            "findOrCreate(), trying to find user: {subject: {}}, and found a user with uuid: {}, subject: {}", newUser.getSubject(),
            newUser.getUuid(), newUser.getSubject()
        );
        // check if the user exist by subject
        Optional<User> user = Optional.ofNullable(findBySubject(newUser.getSubject()));
        if (user.isPresent()) {
            return user.orElse(null);
        }

        // check if the user exist by email and connection
        user = userRepository.findByEmailAndConnectionId(newUser.getEmail(), newUser.getConnection().getId());
        if (user.isPresent()) {
            if (StringUtils.isEmpty(user.get().getSubject())) {
                user.get().setSubject(newUser.getSubject());
                user.get().setGeneralMetadata(newUser.getGeneralMetadata());
            }

            return user.orElse(null);
        }

        user = Optional.ofNullable(save(newUser));
        logger.info(
            "createUser created user, uuid: {}, subject: {}, role: {}, privilege: {}", user.get().getUuid(), newUser.getSubject(),
            user.get().getRoleString(), user.get().getPrivilegeString()
        );
        // create a new user
        return user.orElse(null);
    }

    public Optional<User> findByEmailAndConnection(String email, String connectionId) {
        return this.userRepository.findByEmailAndConnectionId(email, connectionId);
    }

    public User findUserByUUID(String userUUID) {
        return this.userRepository.findById(UUID.fromString(userUUID)).orElse(null);
    }

    public User createOpenAccessUser(Role openAccessRole) {
        User user = new User();

        // Save the user to get a UUID
        user = save(user);
        user.setSubject("open_access|" + user.getUuid().toString());

        if (user.getRoles() == null) {
            user.setRoles(new HashSet<>());
        }

        if (openAccessRole != null) {
            user.getRoles().add(openAccessRole);
        }

        user.setEmail(user.getUuid() + "@open_access.com");
        user = save(user);

        logger.info(
            "createOpenAccessUser() created user, uuid: {}, subject: {}, role: {}, privilege: {}", user.getUuid(), user.getSubject(),
            user.getRoleString(), user.getPrivilegeString()
        );

        if (loggingClient != null && loggingClient.isEnabled()) {
            try {
                loggingClient.send(
                    LoggingEvent.builder("AUTHZ").action("user.open_access_created")
                        .metadata(Map.of("user_uuid", user.getUuid().toString(), "assigned_role", "MANAGED_OPEN_ACCESS")).build()
                );
            } catch (Exception e) {
                logger.warn("Failed to send open access user creation logging event", e);
            }
        }

        return user;
    }

    /**
     * Using the introspection token response, load the user from the database. If the user does not exist, we will reject their login
     * attempt. For the RAS integration here is a sample payload.
     *
     * @param node The response from the introspect endpoint
     * @return The user
     */
    public Optional<User> createRasUser(JsonNode node, Connection connection) {
        try {
            String userEmail = node.get("preferred_username").asText();
            logger.info("Loading user for sub: {}", userEmail);

            User new_user = new User();
            new_user.setSubject(connection.getSubPrefix() + userEmail);
            new_user.setEmail(userEmail);
            new_user.setConnection(connection);
            User actual_user = this.findOrCreate(new_user);

            if (actual_user.getRoles() == null) {
                actual_user.setRoles(new HashSet<>());
            }

            actual_user.setAcceptedTOS(new Date());
            logger.info("LOGIN SUCCESS ___ USER DATA: {}", actual_user);
            return Optional.of(actual_user);
        } catch (Exception e) {
            logger.error("Failed to create user from introspect response", e);
            return Optional.empty();
        }
    }

    public Set<User> getAllUsersWithAPassport() {
        return this.userRepository.findByPassportIsNotNull();
    }

    /**
     * Clears users session and merge template which effectively logs them out.
     *
     * @param user
     */
    public void logoutUser(User user) {
        this.removeUserPassport(user.getSubject());
    }

    /**
     * Attach the roles every authenticated user receives. Study-level authorization is carried by {@code user_consents} (see
     * {@link #updateUserConsents}), not by roles, so no per-study role is derived here.
     *
     * @param current_user User to be updated
     */
    public User ensureBaselineRoles(User current_user) {
        Set<String> baselineRoleNames = Set.of(MANAGED_AUTH_ACCESS_ROLE_NAME, MANAGED_OPEN_ACCESS_ROLE_NAME, MANAGED_ROLE_NAMED_DATASET);

        Map<String, Role> found = roleService.findByNames(baselineRoleNames);
        baselineRoleNames.stream().filter(name -> !found.containsKey(name))
            .forEach(name -> logger.warn("ensureBaselineRoles() unable to find role named {}", name));

        Set<Role> currentRoles = current_user.getRoles();
        Set<Role> added = found.values().stream().filter(role -> !currentRoles.contains(role)).collect(Collectors.toSet());
        currentRoles.addAll(found.values());

        if (loggingClient != null && loggingClient.isEnabled()) {
            try {
                loggingClient.send(
                    LoggingEvent.builder("AUTHZ").action("role.sync")
                        .metadata(
                            Map.of(
                                "user_id", current_user.getUuid().toString(), "user_email",
                                current_user.getEmail() != null ? current_user.getEmail() : "", "roles_added",
                                added.stream().map(Role::getName).collect(Collectors.joining(",")), "roles_removed", ""
                            )
                        ).build()
                );
            } catch (Exception e) {
                logger.warn("Failed to send role sync logging event", e);
            }
        }

        logger.debug(
            "User roles: {}", current_user.getRoles().stream().filter(Objects::nonNull).map(Role::getName).collect(Collectors.joining(", "))
        );
        try {
            current_user = this.changeRole(current_user, current_user.getRoles());
            logger.debug("ensureBaselineRoles() updated user, who now has {} roles.", current_user.getRoles().size());
            return current_user;
        } catch (Exception ex) {
            logger.error("ensureBaselineRoles() Could not add roles to user, because {}", ex.getMessage());
        }

        return null;
    }


    private String extractIdp(User current_user) {
        try {
            final ObjectNode node;
            node = new ObjectMapper().readValue(current_user.getGeneralMetadata(), ObjectNode.class);
            return node.get("idp").asText();
        } catch (JsonProcessingException e) {
            logger.warn("Error parsing idp value from medatada", e);
            return "";
        }
    }

    public void removeUserPassport(String subject) {
        User user = this.findBySubject(subject);
        if (user != null) {
            user.setPassport(null);
            this.save(user);
        }
    }

    public User updateUserConsents(User user, Set<String> userConsentStrings) {
        Set<String> consents =
                getUserConsents(user, userConsentStrings);

        UserConsents userConsents = userConsentsRepository.findByUserId(user.getUuid());
        if (userConsents == null) {
            logger.info("Creating user consents");
            userConsents = new UserConsents().setConsents(consents).setUserId(user.getUuid());
            logger.info("{} User consents created", userConsents.getConsents().size());
        }
        userConsents.setConsents(consents);
        logger.info("Saving {} user consents", userConsents.getConsents().size());
        userConsentsRepository.save(userConsents);

        return user;
    }

    private Set<String> getUserConsents(User user, Set<String> userConsentStrings) {
        UserConsentsOverride overrideConsents = userConsentsOverrideRepository.findByUserId(user.getUuid());
        if (overrideConsents != null && overrideConsents.isEnabled()) {
            return overrideConsents.getConsents();
        } else {
            return new BdcConsentsBuilder(fenceMappingUtility.getFENCEMapping(), userConsentStrings).createConsents();
        }
    }

    /**
     * Returns the consents of the currently authenticated user. The user is taken from the security context, so a caller can only ever read
     * its own consents.
     *
     * @return the user's consents, an empty set of consents if none are stored, or null if no user is authenticated
     */
    public UserConsents getUserConsents() {
        SecurityContext securityContext = SecurityContextHolder.getContext();
        Authentication authentication = securityContext.getAuthentication();
        // An unauthenticated request has either no Authentication at all or an AnonymousAuthenticationToken, whose principal is the
        // String "anonymousUser". Testing the type rather than casting keeps both cases on the documented null-returning path.
        if (
            authentication == null || !(authentication.getPrincipal() instanceof CustomUserDetails customUserDetails)
                || customUserDetails.getUser() == null || customUserDetails.getUser().getUuid() == null
        ) {
            logger.error("Security context didn't have a user stored.");
            return null;
        }

        UUID userId = customUserDetails.getUser().getUuid();
        UserConsents userConsents = userConsentsRepository.findByUserId(userId);
        if (userConsents == null) {
            // Not an error: a user with no stored record simply has no authorized studies. Returning an empty set lets clients treat
            // this as "nothing authorized" instead of failing outright.
            logger.info("No consents stored for user {}", userId);
            return new UserConsents().setUserId(userId).setConsents(Set.of());
        }

        return userConsents;
    }
}
