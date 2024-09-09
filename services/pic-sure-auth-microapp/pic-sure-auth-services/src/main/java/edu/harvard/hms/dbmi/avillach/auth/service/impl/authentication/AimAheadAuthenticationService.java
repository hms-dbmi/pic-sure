package edu.harvard.hms.dbmi.avillach.auth.service.impl.authentication;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.harvard.hms.dbmi.avillach.auth.entity.Role;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.service.AuthenticationService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.RoleService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.UserService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.AccessRuleService;
import edu.harvard.hms.dbmi.avillach.auth.utils.RestClientUtil;
import jakarta.persistence.NoResultException;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

import static edu.harvard.hms.dbmi.avillach.auth.service.impl.RoleService.MANAGED_OPEN_ACCESS_ROLE_NAME;

@Service
public class AimAheadAuthenticationService extends OktaAuthenticationService implements AuthenticationService  {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private final UserService userService;
    private final RoleService roleService;

    private final String connectionId;
    private final boolean isOktaEnabled;
    private final AccessRuleService accessRuleService;

    /**
     * Constructor for the OktaOAuthAuthenticationService
     * @param userService The user service
     * @param roleService The role service
     * @param idp_provider_uri The IDP provider URI
     * @param connectionId The connection ID
     * @param clientId The client ID
     * @param spClientSecret The client secret
     */
    @Autowired
    public AimAheadAuthenticationService(UserService userService,
                                         RoleService roleService,
                                         AccessRuleService accessRuleService,
                                         RestClientUtil restClientUtil,
                                         @Value("${a4.okta.idp.provider.is.enabled}") boolean isOktaEnabled,
                                         @Value("${a4.okta.idp.provider.uri}") String idp_provider_uri,
                                         @Value("${a4.okta.connection.id}") String connectionId,
                                         @Value("${a4.okta.client.id}") String clientId,
                                         @Value("${a4.okta.client.secret}") String spClientSecret) {
        super(idp_provider_uri, clientId, spClientSecret, restClientUtil);

        this.userService = userService;
        this.roleService = roleService;
        this.connectionId = connectionId;
        this.isOktaEnabled = isOktaEnabled;

        logger.info("OktaOAuthAuthenticationService is enabled: {}", isOktaEnabled);
        logger.info("OktaOAuthAuthenticationService initialized");
        logger.info("idp_provider_uri: {}", idp_provider_uri);
        logger.info("connectionId: {}", connectionId);

        this.accessRuleService = accessRuleService;
    }

    /**
     * Authenticate the user using the code provided by the IDP. This code is exchanged for an access token.
     * The access token is then used to introspect the user. The user is then loaded from the database.
     * If the user does not exist, we will reject their login attempt.
     *
     * @param host       The host of the request
     * @param authRequest The request body
     * @return The response from the authentication attempt
     */
    @Override
    public HashMap<String, String> authenticate(Map<String, String> authRequest, String host) {
        logger.info("OKTA LOGIN ATTEMPT ___ {} ___", authRequest.get("code"));

        String code = authRequest.get("code");
        if (StringUtils.isNotBlank(code)) {
            JsonNode userToken = super.handleCodeTokenExchange(host, code);
            JsonNode introspectResponse = super.introspectToken(userToken);
            User user = initializeUser(introspectResponse);

            if (user == null) {
                return null;
            }

            HashMap<String, String> responseMap = createUserClaims(user);
            logger.info("LOGIN SUCCESS ___ {}:{} ___ Authorization will expire at  ___ {}___", user.getEmail(), user.getUuid().toString(), responseMap.get("expirationDate"));

            return responseMap;
        }

        logger.info("LOGIN FAILED ___ USER NOT AUTHENTICATED ___");
        return null;
    }

    @Override
    public String getProvider() {
        return "aimAheadOkta";
    }

    @Override
    public boolean isEnabled() {
        return this.isOktaEnabled;
    }

    private User initializeUser(JsonNode introspectResponse) {
        if (introspectResponse == null) {
            logger.info("FAILED TO INTROSPECT TOKEN ___ ");
            return null;
        }

        boolean isActive = introspectResponse.get("active").asBoolean();
        if (!isActive) {
            logger.info("LOGIN FAILED ___ USER IS NOT ACTIVE ___ ");
            return null;
        }

        User user = loadUser(introspectResponse);
        if (user == null) {
            return null;
        }

        clearCache(user);
        return user;
    }

    /**
     * Create user claims to return to the client
     *
     * @param user The user
     * @return The user claims as a HashMap
     */
    private HashMap<String, String> createUserClaims(User user) {
        HashMap<String, Object> claims = new HashMap<>();
        claims.put("name", user.getName());
        claims.put("email", user.getEmail());
        claims.put("sub", user.getSubject());
        return userService.getUserProfileResponse(claims);
    }

    private void clearCache(User user) {
        userService.evictFromCache(user.getSubject());
        accessRuleService.evictFromCache(user.getSubject());
    }

    /**
     * Using the introspection token response, load the user from the database. If the user does not exist, we
     * will reject their login attempt.
     * Documentation: <a href="https://developer.okta.com/docs/reference/api/oidc/#response-example-success-access-token">response-example-success-access-token</a>
     *
     * @param introspectResponse The response from the introspect endpoint
     * @return The user
     */
    private User loadUser(JsonNode introspectResponse) {
        String userEmail = introspectResponse.get("sub").asText();
        try {
            // connection id = okta
            Optional<User> user = userService.findByEmailAndConnection(userEmail, this.connectionId);

            // If the user does not yet have a subject, set it to the subject from the introspect response
            if (user.get().getSubject() == null) {
                user.get().setSubject("okta|" + introspectResponse.get("uid").asText());
            }

            // All users that login through OKTA should have the fence_open_access role, or they will not be able to interact with the UI
            Role fenceOpenAccessRole = roleService.getRoleByName(MANAGED_OPEN_ACCESS_ROLE_NAME);
            if (!user.get().getRoles().contains(fenceOpenAccessRole)) {
                logger.info("Adding fence_open_access role to user: {}", user.get().getUuid());
                Set<Role> roles = user.get().getRoles();
                roles.add(fenceOpenAccessRole);
                user = Optional.ofNullable(userService.changeRole(user.orElse(null), roles));
            }

            user.get().setGeneralMetadata(generateUserMetadata(introspectResponse, user.orElse(null)).toString());

            userService.save(user.orElse(null));
            logger.info("LOGIN SUCCESS ___ USER DATA: {}", user);
            return user.orElse(null);
        } catch (NoResultException ex) {
            logger.info("LOGIN FAILED ___ USER NOT FOUND ___ {} ___", userEmail);
            return null;
        }
    }

    /**
     * Generate the user metadata that will be stored in the database. This metadata is used to determine the user's
     * role and other information.
     *
     * @param introspectResponse The response from the introspect endpoint
     * @param user               The user
     * @return The user metadata as an ObjectNode
     */
    protected ObjectNode generateUserMetadata(JsonNode introspectResponse, User user) {
        // JsonNode is immutable, so we need to convert it to an ObjectNode
        ObjectNode objectNode = new ObjectMapper().createObjectNode();

        objectNode.put("role", "user");
        objectNode.put("sub", introspectResponse.get("sub").asText());
        objectNode.put("user_id", user.getUuid().toString());
        objectNode.put("username", user.getEmail());
        objectNode.put("email", user.getEmail());

        return objectNode;
    }

}
