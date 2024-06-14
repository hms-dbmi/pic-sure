package edu.harvard.hms.dbmi.avillach.auth.service.impl.authentication;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.harvard.hms.dbmi.avillach.auth.entity.Role;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.RoleService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.UserService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.authorization.AccessRuleService;
import edu.harvard.hms.dbmi.avillach.auth.utils.RestClientUtil;
import jakarta.persistence.NoResultException;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class OktaOAuthAuthenticationService {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private final UserService userService;
    private final RoleService roleService;

    private final String idp_provider_uri;
    private final String connectionId;
    private final String clientId;
    private final String spClientSecret;
    private final AccessRuleService accessRuleService;
    private final RestClientUtil restClientUtil;

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
    public OktaOAuthAuthenticationService(UserService userService, RoleService roleService,
                                          @Value("${okta.idp.provider.uri}") String idp_provider_uri,
                                          @Value("${okta.connection.id}") String connectionId,
                                          @Value("${okta.client.id}") String clientId,
                                          @Value("${okta.client.secret}") String spClientSecret, AccessRuleService accessRuleService, RestClientUtil restClientUtil) {
        this.userService = userService;
        this.roleService = roleService;
        this.idp_provider_uri = idp_provider_uri;
        this.connectionId = connectionId;
        this.clientId = clientId;
        this.spClientSecret = spClientSecret;

        logger.info("OktaOAuthAuthenticationService initialized");
        logger.info("idp_provider_uri: {}", idp_provider_uri);
        logger.info("connectionId: {}", connectionId);
        logger.info("clientId: {}", clientId);
        logger.info("spClientSecret: {}", spClientSecret);
        this.accessRuleService = accessRuleService;
        this.restClientUtil = restClientUtil;
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
    public HashMap<String, String> authenticate(String host, Map<String, String> authRequest) {
        String code = authRequest.get("code");
        if (StringUtils.isNotBlank(code)) {
            JsonNode userToken = handleCodeTokenExchange(host, code);
            JsonNode introspectResponse = introspectToken(userToken);
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
        userService.evictFromCache(user);
        accessRuleService.evictFromCache(user);
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
            User user = userService.findByEmailAndConnection(userEmail, this.connectionId);

            // If the user does not yet have a subject, set it to the subject from the introspect response
            if (user.getSubject() == null) {
                user.setSubject("okta|" + introspectResponse.get("uid").asText());
            }

            // All users that login through OKTA should have the fence_open_access role, or they will not be able to interact with the UI
            Role fenceOpenAccessRole = roleService.getRoleByName(FENCEAuthenticationService.fence_open_access_role_name);
            if (!user.getRoles().contains(fenceOpenAccessRole)) {
                logger.info("Adding fence_open_access role to user: {}", user.getUuid());
                Set<Role> roles = user.getRoles();
                roles.add(fenceOpenAccessRole);
                user = userService.changeRole(user, roles);
            }

            user.setGeneralMetadata(generateUserMetadata(introspectResponse, user).toString());

            userService.save(user);
            logger.info("LOGIN SUCCESS ___ USER DATA: {}", user);
            return user;
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

    /**
     * Introspect the token to get the user's email address. This is a call to the OKTA introspect endpoint.
     * Documentation: <a href="https://developer.okta.com/docs/reference/api/oidc/#introspect">/introspect</a>
     *
     * @param userToken The token to introspect
     * @return The response from the introspect endpoint as a JsonNode
     */
    private JsonNode introspectToken(JsonNode userToken) {
        JsonNode accessTokenNode = userToken.get("access_token");
        if (accessTokenNode == null) {
            logger.info("USER TOKEN DOES NOT HAVE ACCESS TOKEN ___ {}", userToken);
            return null;
        }

        String accessToken = accessTokenNode.asText();
        // get the access token string from the response
        String oktaIntrospectUrl = "https://" + this.idp_provider_uri + "/oauth2/default/v1/introspect";
        String payload = "token_type_hint=access_token&token=" + accessToken;
        return doOktaRequest(oktaIntrospectUrl, payload);
    }

    /**
     * Exchange the code for an access token. This is a call to the OKTA token endpoint.
     * Documentation: <a href="https://developer.okta.com/docs/reference/api/oidc/#token">Token</a>
     *
     * @param host The UriInfo object from the JAX-RS context
     * @param code    The code to exchange
     * @return The response from the token endpoint as a JsonNode
     */
    private JsonNode handleCodeTokenExchange(String host, String code) {
        String redirectUri = "https://" + host + "/psamaui/login";
        String queryString = "grant_type=authorization_code" + "&code=" + code + "&redirect_uri=" + redirectUri;
        String oktaTokenUrl = "https://" + this.idp_provider_uri + "/oauth2/default/v1/token";

        return doOktaRequest(oktaTokenUrl, queryString);
    }

    /**
     * Perform a request to the OKTA API using the provided URL and parameters. The request will be a POST request.
     * It is using Authorization Basic authentication. The client ID and client secret are base64 encoded and sent
     * in the Authorization header.
     *
     * @param requestUrl    The URL to call
     * @param requestParams The parameters to send
     * @return The response from the OKTA API as a JsonNode
     */
    private JsonNode doOktaRequest(String requestUrl, String requestParams) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(this.clientId, this.spClientSecret);
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        ResponseEntity<?> resp;
        JsonNode response = null;
        try {
            resp = this.restClientUtil.retrievePostResponse(requestUrl, headers, requestParams);
            response = new ObjectMapper().readTree(Objects.requireNonNull(resp.getBody()).toString());
        } catch (Exception ex) {
            logger.error("handleCodeTokenExchange() failed to call OKTA token endpoint, {}", ex.getMessage());
        }

        logger.debug("getFENCEAccessToken() finished: {}", response);
        return response;
    }
}
