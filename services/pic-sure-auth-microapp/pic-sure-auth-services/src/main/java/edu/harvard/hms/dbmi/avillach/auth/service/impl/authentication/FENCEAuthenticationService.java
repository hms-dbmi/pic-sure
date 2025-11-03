package edu.harvard.hms.dbmi.avillach.auth.service.impl.authentication;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.auth.entity.Connection;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.exceptions.NotAuthorizedException;
import edu.harvard.hms.dbmi.avillach.auth.model.fenceMapping.StudyMetaData;
import edu.harvard.hms.dbmi.avillach.auth.service.AuthenticationService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.*;
import edu.harvard.hms.dbmi.avillach.auth.utils.FenceMappingUtility;
import edu.harvard.hms.dbmi.avillach.auth.utils.RestClientUtil;
import jakarta.annotation.PostConstruct;
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
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class FENCEAuthenticationService implements AuthenticationService {

    private final Logger logger = LoggerFactory.getLogger(FENCEAuthenticationService.class);

    private final UserService userService;
    private final ConnectionWebService connectionService; // We will need to investigate if the ConnectionWebService will need to be versioned as well.
    private final FenceMappingUtility fenceMappingUtility;
    private final CacheEvictionService cacheEvictionService;

    private Connection fenceConnection;

    private final boolean isFenceEnabled;
    private final String idp_provider_uri;
    private final String fence_client_id;
    private final String fence_client_secret;


    private final RestClientUtil restClientUtil;

    @Autowired
    public FENCEAuthenticationService(UserService userService,
                                      ConnectionWebService connectionService,
                                      RestClientUtil restClientUtil,
                                      @Value("${fence.idp.provider.is.enabled}") boolean isFenceEnabled,
                                      @Value("${fence.idp.provider.uri}") String idpProviderUri,
                                      @Value("${fence.client.id}") String fenceClientId,
                                      @Value("${fence.client.secret}") String fenceClientSecret,
                                      FenceMappingUtility fenceMappingUtility, CacheEvictionService cacheEvictionService) {
        this.userService = userService;
        this.connectionService = connectionService;
        this.idp_provider_uri = idpProviderUri;
        this.fence_client_id = fenceClientId;
        this.fence_client_secret = fenceClientSecret;
        this.restClientUtil = restClientUtil;
        this.fenceMappingUtility = fenceMappingUtility;
        this.isFenceEnabled = isFenceEnabled;
        this.cacheEvictionService = cacheEvictionService;
    }

    @PostConstruct
    public void initializeFenceService() {
        fenceConnection = connectionService.getConnectionByLabel("FENCE");
        logger.info("isFenceEnabled: {}", isFenceEnabled);
        logger.info("idp_provider_uri: {}", idp_provider_uri);
    }

    @Override
    public HashMap<String, String> authenticate(Map<String, String> authRequest, String host) {
        String callBackUrl = "https://" + host + "/login/loading/";

        logger.debug("getFENCEProfile() starting...");
        String fence_code = authRequest.get("code");

        // Validate that the fence code is alphanumeric
        if (!fence_code.matches("[a-zA-Z0-9]+")) {
            logger.error("getFENCEProfile() fence code is not alphanumeric");
            throw new NotAuthorizedException("The fence code is not alphanumeric");
        }

        JsonNode fence_user_profile;
        // Get the Gen3/FENCE user profile. It is a JsonNode object
        try {
            logger.debug("getFENCEProfile() query FENCE for user profile with code");
            JsonNode tokenJson = getFENCEAccessToken(callBackUrl, fence_code);
            JsonNode accessTokenNode = tokenJson.get("access_token");
            if (accessTokenNode == null || accessTokenNode.isNull() || accessTokenNode.asText().isBlank()) {
                logger.error("getFENCEProfile() access_token missing in token response: {}", tokenJson.toString());
                throw new NotAuthorizedException("Token response missing access_token");
            }
            String accessToken = accessTokenNode.asText();
            fence_user_profile = getFENCEUserProfile(accessToken);

            if (logger.isTraceEnabled()) {
                // create object mapper instance
                ObjectMapper mapper = new ObjectMapper();
                // `JsonNode` to JSON string
                String prettyString = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(fence_user_profile);

                logger.trace("getFENCEProfile() user profile structure:{}", prettyString);
            }
            logger.debug("getFENCEProfile() .username:{}", fence_user_profile.get("username"));
            logger.debug("getFENCEProfile() .user_id:{}", fence_user_profile.get("user_id"));
            logger.debug("getFENCEProfile() .email:{}", fence_user_profile.get("email"));
        } catch (Exception ex) {
            logger.error("getFENCEProfile() could not retrieve the user profile from the auth provider, because {}", ex.getMessage(), ex);
            throw new NotAuthorizedException("Could not get the user profile " +
                    "from the Gen3 authentication provider." + ex.getMessage());
        }

        User currentUser;
        try {
            // Create or retrieve the user profile from our database, based on the the key
            // in the Gen3/FENCE profile
            currentUser = createUserFromFENCEProfile(fence_user_profile);
            logger.info("getFENCEProfile() saved details for user with e-mail:{} and subject:{}", currentUser.getEmail(), currentUser.getSubject());
            cacheEvictionService.evictCache(currentUser);
        } catch (Exception ex) {
            logger.error("getFENCEToken() Could not persist the user information, because {}", ex.getMessage());
            throw new NotAuthorizedException("The user details could not be persisted. Please contact the administrator.");
        }

        // Update the user's roles (or create them if none exists)
        Iterator<String> project_access_names = fence_user_profile.get("authz").fieldNames();
        Set<String> roleNames = new HashSet<>();
        project_access_names.forEachRemaining(roleName -> {
            // We need to add/remove the users roles based on what is in the project_access_names list
            StudyMetaData projectMetadata = this.fenceMappingUtility.getFenceMappingByAuthZ().get(roleName);
            if (projectMetadata == null) {
                logger.error("getFENCEProfile() -> createAndUpsertRole could not find study in FENCE mapping SKIPPING: {}", roleName);
                return;
            }

            String projectId = projectMetadata.getStudyIdentifier();
            String consentCode = projectMetadata.getConsentGroupCode();
            String newRoleName = StringUtils.isNotBlank(consentCode) ? "MANAGED_" + projectId + "_" + consentCode : "MANAGED_" + projectId;

            roleNames.add(newRoleName);
        });

        currentUser = userService.updateUserRoles(currentUser, roleNames);
        HashMap<String, Object> claims = new HashMap<String, Object>();
        claims.put("name", fence_user_profile.get("name"));
        claims.put("email", currentUser.getEmail());
        claims.put("sub", currentUser.getSubject());
        HashMap<String, String> responseMap = userService.getUserProfileResponse(claims);
        logger.info("LOGIN SUCCESS ___ {}:{}:{} ___ WITH ROLES ___ {} ___ Authorization will expire at  ___ {}___",
                currentUser.getEmail(),
                currentUser.getUuid().toString(),
                currentUser.getSubject(),
                currentUser.getRoles().stream().map(role -> role.getName().replace("MANAGED_", "")).collect(Collectors.joining(",")),
                responseMap.get("expirationDate"));
        logger.debug("getFENCEProfile() UserProfile response object has been generated");
        logger.debug("getFENCEToken() finished");

        return responseMap;
    }


    @Override
    public String getProvider() {
        return "fence";
    }

    @Override
    public boolean isEnabled() {
        return this.isFenceEnabled;
    }

    private JsonNode getFENCEUserProfile(String access_token) {
        logger.debug("getFENCEUserProfile() starting");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(access_token);

        String url = this.idp_provider_uri + "/user/user";
        logger.debug("getFENCEUserProfile() getting user profile from uri:{}", url);

        try {
            ResponseEntity<String> resp = this.restClientUtil.retrieveGetResponseWithRequestConfiguration(
                    url,
                    headers,
                    10000
            );

            if (!resp.getStatusCode().is2xxSuccessful()) {
                logger.error("getFENCEUserProfile() non-2xx: status={}, body={}", resp.getStatusCode(), resp.getBody());
                throw new NotAuthorizedException("User profile request failed: " + resp.getStatusCode());
            }
            String body = resp.getBody();
            if (body == null || body.isBlank()) {
                logger.error("getFENCEUserProfile() empty response body");
                throw new NotAuthorizedException("Empty user profile response");
            }
            JsonNode json = new ObjectMapper().readTree(body);
            logger.debug("getFENCEUserProfile() finished, returning user profile: {}", json.toString());
            return json;
        } catch (HttpClientErrorException e) {
            logger.error("getFENCEUserProfile() HttpClientErrorException: status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new NotAuthorizedException("User profile request failed: " + e.getStatusCode());
        } catch (Exception e) {
            logger.error("getFENCEUserProfile() error: {}", e.getMessage(), e);
            throw new NotAuthorizedException("Failed to parse user profile");
        }
    }

    private JsonNode getFENCEAccessToken(String callback_url, String fence_code) {
        logger.debug("getFENCEAccessToken() starting, using FENCE code");

        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(this.fence_client_id, this.fence_client_secret);
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> queryMap = new LinkedMultiValueMap<>();
        queryMap.add("grant_type", "authorization_code");
        queryMap.add("code", fence_code);
        queryMap.add("redirect_uri", callback_url);

        String fence_token_url = this.idp_provider_uri + "/user/oauth2/token";

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(queryMap, headers);

        try {
            ResponseEntity<String> resp = this.restClientUtil.retrievePostResponse(
                    fence_token_url,
                    request
            );

            if (!resp.getStatusCode().is2xxSuccessful()) {
                String body = resp.getBody();
                logger.error("getFENCEAccessToken() non-2xx from FENCE: status={}, body={}", resp.getStatusCode(), body);
                throw new NotAuthorizedException("Token endpoint returned status " + resp.getStatusCode());
            }

            String body = resp.getBody();
            if (body == null || body.isBlank()) {
                logger.error("getFENCEAccessToken() empty response body from FENCE token endpoint");
                throw new NotAuthorizedException("Empty response from token endpoint");
            }

            JsonNode respJson = new ObjectMapper().readTree(body);
            logger.debug("getFENCEAccessToken() finished: {}", respJson.toString());
            return respJson;

        } catch (HttpClientErrorException e) {
            String body = e.getResponseBodyAsString();
            logger.error("getFENCEAccessToken() HttpClientErrorException: status={}, body={}", e.getStatusCode(), body);
            throw new NotAuthorizedException("Token request failed: " + e.getStatusCode() + ": " + e.getMessage());
        } catch (Exception ex) {
            logger.error("getFENCEAccessToken() failed to call FENCE token service: {}", ex.getMessage(), ex);
            throw new NotAuthorizedException("Token request failed: " + ex.getMessage());
        }
    }

    /**
     * Create or update a user record, based on the FENCE user profile, which is in JSON format.
     *
     * @param node User profile, as it is received from Gen3 FENCE, in JSON format
     * @return User The actual entity, as it is persisted (if no errors) in the PSAMA database
     */
    private User createUserFromFENCEProfile(JsonNode node) {
        logger.debug("createUserFromFENCEProfile() starting...");

        User newUser = new User();
        newUser.setSubject("fence|" + node.get("user_id").asText());
        // This is not always an email address, but it is the only attribute other than the sub claim
        // that is guaranteed to be populated by Fence and which makes sense as a display name for a
        // user.
        newUser.setEmail(node.get("username").asText());
        newUser.setGeneralMetadata(node.toString());
        // This is a hack, but someone has to do it.
        newUser.setAcceptedTOS(new Date());
        newUser.setConnection(fenceConnection);
        logger.debug("createUserFromFENCEProfile() finished setting fields");

        User actual_user = userService.findOrCreate(newUser);

        if (actual_user.getRoles() == null) {
            actual_user.setRoles(new HashSet<>());
        }

        logger.debug("createUserFromFENCEProfile() finished, user record inserted");
        return actual_user;
    }

}
