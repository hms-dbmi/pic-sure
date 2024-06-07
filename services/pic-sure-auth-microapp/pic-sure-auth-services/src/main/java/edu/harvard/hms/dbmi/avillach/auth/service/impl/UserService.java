package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.auth.entity.Application;
import edu.harvard.hms.dbmi.avillach.auth.entity.Connection;
import edu.harvard.hms.dbmi.avillach.auth.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.entity.Role;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.model.CustomUserDetails;
import edu.harvard.hms.dbmi.avillach.auth.repository.ApplicationRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.ConnectionRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.UserRepository;
import edu.harvard.hms.dbmi.avillach.auth.service.RoleService;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming;
import edu.harvard.hms.dbmi.avillach.auth.utils.JWTUtil;
import edu.harvard.hms.dbmi.avillach.auth.utils.JsonUtils;
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
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class UserService {

    private final Logger logger = LoggerFactory.getLogger(UserService.class.getName());

    private final BasicMailService basicMailService;
    private final TOSService tosService;
    private final UserRepository userRepository;
    private final ConnectionRepository connectionRepository;
    private final ApplicationRepository applicationRepository;
    private final RoleService roleService;
    private final long tokenExpirationTime;
    private static final long defaultTokenExpirationTime = 1000L * 60 * 60; // 1 hour

    public long longTermTokenExpirationTime;

    private final String applicationUUID;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JWTUtil jwtUtil;

    @Autowired
    public UserService(BasicMailService basicMailService, TOSService tosService,
                       UserRepository userRepository,
                       ConnectionRepository connectionRepository,
                       ApplicationRepository applicationRepository,
                       RoleService roleService,
                       @Value("${application.token.expiration.time}") long tokenExpirationTime,
                       @Value("${application.default.uuid}") String applicationUUID,
                       @Value("${application.long.term.token.expiration.time}") long longTermTokenExpirationTime,
                       JWTUtil jwtUtil) {
        this.basicMailService = basicMailService;
        this.tosService = tosService;
        this.userRepository = userRepository;
        this.connectionRepository = connectionRepository;
        this.roleService = roleService;
        this.tokenExpirationTime = tokenExpirationTime > 0 ? tokenExpirationTime : defaultTokenExpirationTime;
        this.applicationRepository = applicationRepository;
        this.applicationUUID = applicationUUID;
        this.jwtUtil = jwtUtil;

        long defaultLongTermTokenExpirationTime = 1000L * 60 * 60 * 24 * 30; //
        this.longTermTokenExpirationTime = longTermTokenExpirationTime > 0 ? longTermTokenExpirationTime : defaultLongTermTokenExpirationTime;

    }

    public HashMap<String, String> getUserProfileResponse(Map<String, Object> claims) {
        logger.info("getUserProfileResponse() starting...");

        HashMap<String, String> responseMap = new HashMap<String, String>();
        logger.info("getUserProfileResponse() initialized map");

        logger.info("getUserProfileResponse() using claims:{}", claims.toString());

        String token = this.jwtUtil.createJwtToken(
                "whatever",
                "edu.harvard.hms.dbmi.psama",
                claims,
                claims.get("sub").toString(),
                this.tokenExpirationTime
        );
        logger.info("getUserProfileResponse() PSAMA JWT token has been generated. Token:{}", token);
        responseMap.put("token", token);

        logger.info("getUserProfileResponse() .usedId field is set");
        responseMap.put("userId", claims.get("sub").toString());

        logger.info("getUserProfileResponse() .email field is set");
        responseMap.put("email", claims.get("email").toString());

        logger.info("getUserProfileResponse() acceptedTOS is set");

        boolean acceptedTOS = tosService.hasUserAcceptedLatest(claims.get("sub").toString());

        responseMap.put("acceptedTOS", "" + acceptedTOS);

        logger.info("getUserProfileResponse() expirationDate is set");
        Date expirationDate = new Date(Calendar.getInstance().getTimeInMillis() + this.tokenExpirationTime);
        responseMap.put("expirationDate", ZonedDateTime.ofInstant(expirationDate.toInstant(), ZoneOffset.UTC).toString());

        logger.info("getUserProfileResponse() finished");
        return responseMap;
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
     * This check is to prevent non-super-admin user to create/remove a super admin role
     * against a user(include themselves). Only super admin user could perform such actions.
     *
     * <p>
     * if operations not related to super admin role updates, this will return true.
     * </p>
     * <p>
     * The logic here is checking the state of the super admin role in the input and output users,
     * if the state is changed, check if the user is a super admin to determine if the user could perform the action.
     *
     * @param currentUser  the user trying to perform the action
     * @param inputUser    the user that is going to be updated
     * @param originalUser there could be no original user when adding a new user
     * @return true if the user could perform the action, false otherwise
     */
    private boolean allowUpdateSuperAdminRole(
            @NotNull User currentUser,
            @NotNull User inputUser,
            User originalUser) {

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
                logger.error("updateUser() user - {} - with roles [{}] - is not allowed to grant " + AuthNaming.AuthRoleNaming.SUPER_ADMIN + " role when adding a user.", currentUser.getUuid(), currentUser.getRoleString());
                throw new IllegalArgumentException("Not allowed to add a user with a " + AuthNaming.AuthRoleNaming.SUPER_ADMIN + " privilege associated.");
            }

            if (user.getEmail() == null) {
                try {
                    logger.info("Parsing metadata for email address");
                    HashMap<String, String> metadata = new HashMap<String, String>(new ObjectMapper().readValue(user.getGeneralMetadata(), Map.class));
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
     * check all referenced field if they are already in database. If
     * they are in database, then retrieve it by id, and attach it to
     * user object.
     *
     * @param users A list of users
     */
    private void checkAssociation(List<User> users) {
        for (User user : users) {
            if (user.getRoles() != null) {
                Set<UUID> roleUuids = user.getRoles().stream().map(Role::getUuid).collect(Collectors.toSet());
                Set<Role> rolesFromDb = this.roleService.getRolesByIds(roleUuids);

                // If the size of the roles from the database is not the same as the input role UUIDs, then
                // we cannot find all roles by the input UUIDs.
                if (rolesFromDb.size() != roleUuids.size()) {
                    logger.error("checkAssociation() cannot find all roles by UUIDs: {}", roleUuids);
                    throw new IllegalArgumentException("Cannot find all roles by input UUIDs: " + roleUuids);
                }

                user.setRoles(rolesFromDb);
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
        logger.info("Security context: {}", securityContext.getAuthentication().getPrincipal());
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
            logger.error("updateUser() user - {} - with roles [{}] - is not allowed to grant or remove " + AuthNaming.AuthRoleNaming.SUPER_ADMIN + " privilege.", currentUser.getUuid(), currentUser.getRoleString());
            throw new IllegalArgumentException("Not allowed to update a user with changes associated to " + AuthNaming.AuthRoleNaming.SUPER_ADMIN + " privilege.");
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
        logger.info("Security context: {}", securityContext);

        Optional<CustomUserDetails> customUserDetails = Optional.ofNullable((CustomUserDetails) securityContext.getAuthentication().getPrincipal());
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
        User.UserForDisplay userForDisplay = new User.UserForDisplay()
                .setEmail(user.getEmail())
                .setPrivileges(user.getPrivilegeNameSet())
                .setUuid(user.getUuid().toString())
                .setAcceptedTOS(this.tosService.hasUserAcceptedLatest(user.getSubject()));

        // currently, the queryScopes are simple combination of queryScope string together as a set.
        // We are expecting the queryScope string as plain string. If it is a JSON, we could change the
        // code to use JsonUtils.mergeTemplateMap(Map, Map)
        Set<Privilege> privileges = user.getTotalPrivilege();
        if (privileges != null && !privileges.isEmpty()) {
            Set<String> scopes = new TreeSet<>();
            privileges.stream().filter(privilege -> privilege.getQueryScope() != null).forEach(privilege -> {
                try {
                    Arrays.stream(objectMapper.readValue(privilege.getQueryScope(), String[].class))
                            .filter(Objects::nonNull)
                            .forEach(scopes::add);
                } catch (IOException e) {
                    logger.error("Parsing issue for privilege {} queryScope", privilege.getUuid(), e);
                }
            });
            userForDisplay.setQueryScopes(scopes);
        }

        if (user.getToken() != null && !user.getToken().isEmpty()) {
            userForDisplay.setToken(user.getToken());
        } else {
            user.setToken(generateUserLongTermToken(authorizationHeader));
            this.userRepository.save(user);
            userForDisplay.setToken(user.getToken());
        }

        return userForDisplay;
    }

    public Optional<String> getQueryTemplate(String applicationId) {
        if (applicationId == null || applicationId.trim().isEmpty()) {
            logger.error("getQueryTemplate() input application UUID is null or empty.");
            throw new IllegalArgumentException("Input application UUID is incorrect.");
        }

        SecurityContext securityContext = SecurityContextHolder.getContext();
        Optional<CustomUserDetails> customUserDetails = Optional.ofNullable((CustomUserDetails) securityContext.getAuthentication().getPrincipal());
        if (customUserDetails.isEmpty() || customUserDetails.get().getUser() == null){
            logger.error("Security context didn't have a user stored.");
            return Optional.empty();
        }

        User user = customUserDetails.get().getUser();
        Optional<Application> application = this.applicationRepository.findById(UUID.fromString(applicationId));
        if (application.isEmpty()) {
            logger.error("getQueryTemplate() cannot find corresponding application by UUID: {}", UUID.fromString(applicationId));
            throw new IllegalArgumentException("Cannot find application by input UUID: " + UUID.fromString(applicationId));
        }

        return Optional.ofNullable(mergeTemplate(user, application.orElse(null)));
    }

    public Map<String, String> getDefaultQueryTemplate() {
        Optional<String> mergedTemplate = getQueryTemplate(this.applicationUUID);

        if (mergedTemplate.isEmpty()) {
            logger.error("getDefaultQueryTemplate() cannot find corresponding application by UUID: {}", this.applicationUUID);
            return null;
        }

        return Map.of("queryTemplate", mergedTemplate.orElse(null));
    }

    private String mergeTemplate(User user, Application application) {
        String resultJSON;
        Map mergedTemplateMap = null;
        for (Privilege privilege : user.getPrivilegesByApplication(application)) {
            String template = privilege.getQueryTemplate();
            logger.debug("mergeTemplate() processing template:" + template);
            if (template == null || template.trim().isEmpty()) {
                continue;
            }
            Map<String, Object> templateMap = null;
            try {
                templateMap = objectMapper.readValue(template, Map.class);
            } catch (IOException ex) {
                logger.error("mergeTemplate() cannot convert stored queryTemplate using Jackson, the queryTemplate is: {}", template);
                throw new IllegalArgumentException("Inner application error, please contact admin.");
            }

            if (templateMap == null) {
                continue;
            }

            if (mergedTemplateMap == null) {
                mergedTemplateMap = templateMap;
                continue;
            }

            mergedTemplateMap = JsonUtils.mergeTemplateMap(mergedTemplateMap, templateMap);
        }

        try {
            resultJSON = objectMapper.writeValueAsString(mergedTemplateMap);
        } catch (JsonProcessingException ex) {
            logger.error("mergeTemplate() cannot convert map to json string. The map mergedTemplate is: {}", mergedTemplateMap);
            throw new IllegalArgumentException("Inner application error, please contact admin.");
        }

        return resultJSON;

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
        String longTermToken = generateUserLongTermToken(authorizationHeader);
        user.setToken(longTermToken);
        this.userRepository.save(user);

        return Map.of("userLongTermToken", longTermToken);
    }

    /**
     * Logic here is, retrieve the subject of the user from httpHeader. Then generate a long term one
     * with LONG_TERM_TOKEN_PREFIX| in front of the subject to be able to distinguish with regular ones, since
     * long term token only generated for accessing certain things to, in some degrees, decrease the insecurity.
     *
     * @param authorizationHeader the authorization header
     * @return the long term token
     * @throws IllegalArgumentException if the authorization header is not presented
     */
    private String generateUserLongTermToken(String authorizationHeader) {
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
            // considering the subject already contains a "|"
            // to prevent infinitely adding the long term token prefix
            // we will grab the real subject here
            tokenSubject = tokenSubject.substring(AuthNaming.LONG_TERM_TOKEN_PREFIX.length() + 1);
        }

        return this.jwtUtil.createJwtToken(
                claims.getId(),
                claims.getIssuer(),
                claims,
                AuthNaming.LONG_TERM_TOKEN_PREFIX + "|" + tokenSubject,
                this.longTermTokenExpirationTime);
    }

    public void changeRole(User currentUser, Set<Role> roles) {
        // set the users roles and merge the user
        currentUser.setRoles(roles);
        this.userRepository.save(currentUser);
    }

    public User findBySubject(String username) {
        return this.userRepository.findBySubject(username);
    }

    public User save(User user) {
        return this.userRepository.save(user);
    }
}
