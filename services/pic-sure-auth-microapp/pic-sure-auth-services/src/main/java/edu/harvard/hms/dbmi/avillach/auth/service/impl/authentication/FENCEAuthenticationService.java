package edu.harvard.hms.dbmi.avillach.auth.service.impl.authentication;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.harvard.hms.dbmi.avillach.auth.entity.Connection;
import edu.harvard.hms.dbmi.avillach.auth.entity.Role;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.exceptions.NotAuthorizedException;
import edu.harvard.hms.dbmi.avillach.auth.model.fenceMapping.StudyMetaData;
import edu.harvard.hms.dbmi.avillach.auth.service.AuthenticationService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.*;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.AccessRuleService;
import edu.harvard.hms.dbmi.avillach.auth.utils.FenceMappingUtility;
import edu.harvard.hms.dbmi.avillach.auth.utils.RestClientUtil;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class FENCEAuthenticationService implements AuthenticationService {

    private final Logger logger = LoggerFactory.getLogger(FENCEAuthenticationService.class);

    private final UserService userService;
    private final RoleService roleService;
    private final ConnectionWebService connectionService; // We will need to investigate if the ConnectionWebService will need to be versioned as well.
    private final AccessRuleService accessRuleService;
    private final FenceMappingUtility fenceMappingUtility;

    private Connection fenceConnection;

    private final Set<String> openAccessIdpValues = Set.of("fence", "ras");

    private final boolean isFenceEnabled;
    private final String idp_provider_uri;
    private final String fence_client_id;
    private final String fence_client_secret;


    public static final String fence_open_access_role_name = "MANAGED_ROLE_OPEN_ACCESS";
    private final RestClientUtil restClientUtil;

    @Autowired
    public FENCEAuthenticationService(UserService userService,
                                      RoleService roleService,
                                      ConnectionWebService connectionService,
                                      RestClientUtil restClientUtil,
                                      @Value("${fence.idp.provider.is.enabled}") boolean isFenceEnabled,
                                      @Value("${fence.idp.provider.uri}") String idpProviderUri,
                                      @Value("${fence.client.id}") String fenceClientId,
                                      @Value("${fence.client.secret}") String fenceClientSecret,
                                      AccessRuleService accessRuleService,
                                      FenceMappingUtility fenceMappingUtility) {
        this.userService = userService;
        this.roleService = roleService;
        this.connectionService = connectionService;
        this.idp_provider_uri = idpProviderUri;
        this.fence_client_id = fenceClientId;
        this.fence_client_secret = fenceClientSecret;
        this.restClientUtil = restClientUtil;
        this.accessRuleService = accessRuleService;
        this.fenceMappingUtility = fenceMappingUtility;
        this.isFenceEnabled = isFenceEnabled;
    }

    @EventListener(ContextRefreshedEvent.class)
    public void initializeFenceService() {
        fenceConnection = connectionService.getConnectionByLabel("FENCE");

        // log all the properties
        logger.info("isFenceEnabled: {}", isFenceEnabled);
        logger.info("idp_provider_uri: {}", idp_provider_uri);

        if (fenceMappingUtility.getFENCEMapping() != null && fenceMappingUtility.getFenceMappingByAuthZ() != null
                && !fenceMappingUtility.getFENCEMapping().isEmpty() && !fenceMappingUtility.getFenceMappingByAuthZ().isEmpty()) {
            // Create all potential access rules using the fence mapping
            Set<Role> roles = fenceMappingUtility.getFenceMappingByAuthZ().values().parallelStream().map(projectMetadata -> {
                if (projectMetadata == null) {
                    logger.error("initializeFenceService() -> createAndUpsertRole could not find study in FENCE mapping SKIPPING: {}", projectMetadata);
                    return null;
                }

                if (projectMetadata.getStudyIdentifier() == null || projectMetadata.getStudyIdentifier().isEmpty()) {
                    logger.error("initializeFenceService() -> createAndUpsertRole could not find study identifier in FENCE mapping SKIPPING: {}", projectMetadata);
                    return null;
                }

                if (projectMetadata.getAuthZ() == null || projectMetadata.getAuthZ().isEmpty()) {
                    logger.error("initializeFenceService() -> createAndUpsertRole could not find authZ in FENCE mapping SKIPPING: {}", projectMetadata);
                    return null;
                }

                String projectId = projectMetadata.getStudyIdentifier();
                String consentCode = projectMetadata.getConsentGroupCode();
                String newRoleName = StringUtils.isNotBlank(consentCode) ? "MANAGED_" + projectId + "_" + consentCode : "MANAGED_" + projectId;

                return this.roleService.createRole(newRoleName, "MANAGED role " + newRoleName);
            }).filter(Objects::nonNull).collect(Collectors.toSet());

            roleService.persistAll(roles);
        } else {
            logger.error("initializeFenceService() -> createAndUpsertRole could not find any studies in FENCE mapping");
        }
    }

    @Override
    public HashMap<String, String> authenticate(Map<String, String> authRequest, String host) {
        String callBackUrl = "https://" + host + "/psamaui/login/";

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
            fence_user_profile = getFENCEUserProfile(getFENCEAccessToken(callBackUrl, fence_code).get("access_token").asText());
            logger.info(fence_user_profile.toPrettyString());
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

        User current_user;
        try {
            // Create or retrieve the user profile from our database, based on the the key
            // in the Gen3/FENCE profile
            current_user = createUserFromFENCEProfile(fence_user_profile);
            logger.info("getFENCEProfile() saved details for user with e-mail:{} and subject:{}", current_user.getEmail(), current_user.getSubject());

            if (!current_user.getEmail().isEmpty()) {
                String email = current_user.getEmail();
                accessRuleService.evictFromCache(email);
                userService.evictFromCache(email);
            }
        } catch (Exception ex) {
            logger.error("getFENCEToken() Could not persist the user information, because {}", ex.getMessage());
            throw new NotAuthorizedException("The user details could not be persisted. Please contact the administrator.");
        }

        // Update the user's roles (or create them if none exists)
        Iterator<String> project_access_names = fence_user_profile.get("authz").fieldNames();

        // I want to parallelize this, but I'm not sure if it's safe to do so.
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

        // convert roles to string list
        String roles = current_user.getRoles().stream().map(Role::getName).collect(Collectors.joining(","));
        logger.info("Current User Roles: " + roles);

        // find roles that are in the user's roles but not in the project_access_names. These are the roles that need to be removed.
        // exclude userRole -> "PIC-SURE Top Admin".equals(userRole.getName()) || "Admin".equals(userRole.getName()) || userRole.getName().startsWith("MANUAL_")
        Set<Role> rolesToRemove = current_user.getRoles().parallelStream()
                .filter(role -> !roleNames.contains(role.getName()) && !role.getName().equals(fence_open_access_role_name) && !role.getName().startsWith("MANUAL_") && !role.getName().equals("PIC-SURE Top Admin") && !role.getName().equals("Admin"))
                .collect(Collectors.toSet());

        if (!rolesToRemove.isEmpty()) {
            current_user.getRoles().removeAll(rolesToRemove);
            logger.debug("upsertRole() removed {} roles from user", rolesToRemove.size());
            logger.debug("User roles after removal: {}", current_user.getRoles().size());
        }

        // find roles that are in the project_access_names but not in the user's roles. These are the roles that need to be added.
        List<Role> newRoles = roleNames.parallelStream()
                .map(roleName -> this.roleService.createRole(roleName, "MANAGED role " + roleName))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        if (!newRoles.isEmpty()) {
            newRoles = roleService.persistAll(newRoles);
            current_user.getRoles().addAll(newRoles);
        }

        final String idp = extractIdp(current_user);
        if (current_user.getRoles() != null && (!current_user.getRoles().isEmpty() || openAccessIdpValues.contains(idp))) {
            Role openAccessRole = roleService.findByName(fence_open_access_role_name);
            if (openAccessRole != null) {
                current_user.getRoles().add(openAccessRole);
            } else {
                logger.warn("Unable to find fence OPEN ACCESS role");
            }
        }

        try {
            current_user = userService.changeRole(current_user, current_user.getRoles());
            logger.debug("upsertRole() updated user, who now has {} roles.", current_user.getRoles().size());
        } catch (Exception ex) {
            logger.error("upsertRole() Could not add roles to user, because {}", ex.getMessage());
        }
        HashMap<String, Object> claims = new HashMap<String, Object>();
        claims.put("name", fence_user_profile.get("name"));
        claims.put("email", current_user.getEmail());
        claims.put("sub", current_user.getSubject());
        HashMap<String, String> responseMap = userService.getUserProfileResponse(claims);
        logger.info("LOGIN SUCCESS ___ {}:{}:{} ___ Authorization will expire at  ___ {}___", current_user.getEmail(), current_user.getUuid().toString(), current_user.getSubject(), responseMap.get("expirationDate"));
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

        logger.debug("getFENCEUserProfile() getting user profile from uri:{}/user/user", this.idp_provider_uri);
        ResponseEntity<String> fence_user_profile_response = this.restClientUtil.retrieveGetResponseWithRequestConfiguration(
                this.idp_provider_uri + "/user/user",
                headers,
                RestClientUtil.createRequestConfigWithCustomTimeout(10000)
        );

        // Map the response to a JsonNode object
        JsonNode fence_user_profile_response_json = null;
        try {
            ObjectMapper mapper = new ObjectMapper();
            fence_user_profile_response_json = mapper.readTree(fence_user_profile_response.getBody());
        } catch (JsonProcessingException e) {
            logger.error("getFENCEUserProfile() failed to parse the user profile response from FENCE, {}", e.getMessage());
        }

        logger.debug("getFENCEUserProfile() finished, returning user profile{}", fence_user_profile_response_json != null ? fence_user_profile_response_json.asText() : null);
        return fence_user_profile_response_json;
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
        JsonNode respJson = null;
        ResponseEntity<String> resp;
        try {
            resp = this.restClientUtil.retrievePostResponse(
                    fence_token_url,
                    request
            );
            respJson = new ObjectMapper().readTree(resp.getBody());
        } catch (Exception ex) {
            logger.error("getFENCEAccessToken() failed to call FENCE token service, {}", ex.getMessage());
        }

        logger.debug("getFENCEAccessToken() finished: {}", respJson.asText());
        return respJson;
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

    /**
     * Create or update a user record, based on the FENCE user profile, which is in JSON format.
     *
     * @param node User profile, as it is received from Gen3 FENCE, in JSON format
     * @return User The actual entity, as it is persisted (if no errors) in the PSAMA database
     */
    private User createUserFromFENCEProfile(JsonNode node) {
        logger.debug("createUserFromFENCEProfile() starting...");

        User new_user = new User();
        new_user.setSubject("fence|" + node.get("user_id").asText());
        // This is not always an email address, but it is the only attribute other than the sub claim
        // that is guaranteed to be populated by Fence and which makes sense as a display name for a
        // user.
        new_user.setEmail(node.get("username").asText());
        new_user.setGeneralMetadata(node.toString());
        // This is a hack, but someone has to do it.
        new_user.setAcceptedTOS(new Date());
        new_user.setConnection(fenceConnection);
        logger.debug("createUserFromFENCEProfile() finished setting fields");

        User actual_user = userService.findOrCreate(new_user);

        if (actual_user.getRoles() == null) {
            actual_user.setRoles(new HashSet<>());
        }

        logger.debug("createUserFromFENCEProfile() finished, user record inserted");
        return actual_user;
    }

}
