package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.auth.entity.*;
import edu.harvard.hms.dbmi.avillach.auth.exceptions.NotAuthorizedException;
import edu.harvard.hms.dbmi.avillach.auth.repository.*;
import edu.harvard.hms.dbmi.avillach.auth.utils.RestClientUtil;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.io.File;
import java.io.IOException;
import java.util.*;

@Service
public class FENCEAuthenticationService {

    private final Logger logger = LoggerFactory.getLogger(FENCEAuthenticationService.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final UserRepository userRepo;

    private final RoleRepository roleRepo;

    private final ConnectionRepository connectionRepo;

    private final AccessRuleRepository accessRuleRepo;

    private final ApplicationRepository applicationRepo;

    private final PrivilegeRepository privilegeRepo;

    private final UserService userService;

    private final RestClientUtil restClientUtil;

    private Application picSureApp;
    private Connection fenceConnection;
    private Map<String, String> fenceMapping;

    // ----------------- FENCE Configuration -----------------
    private final String idp_provider_uri;
    private final String fence_client_id;
    private final String fence_client_secret;
    private final String fence_redirect_url;
    private final String fence_consent_group_concept_path;
    private final String fence_harmonized_concept_path;
    private final String fence_standard_access_rules;

    // ----------------- Template Path -----------------
    private final String templatePath;

    @Autowired
    public FENCEAuthenticationService(UserRepository userRepo, RoleRepository roleRepo, ConnectionRepository connectionRepo,
                                      AccessRuleRepository accessRuleRepo, ApplicationRepository applicationRepo,
                                      PrivilegeRepository privilegeRepo, UserService userService, RestClientUtil restClientUtil,
                                      @Value("${application.idp.provider}") String idpProviderUri,
                                      @Value("${application.fence.client.id") String fenceClientId,
                                      @Value("${application.fence.client.secret}") String fenceClientSecret,
                                      @Value("${application.fence.redirect.url}") String fenceRedirectUrl,
                                      @Value("${application.fence.consent.group.concept.path}") String fenceConsentGroupConceptPath,
                                      @Value("${application.fence.harmonized.concept.path}") String fenceHarmonizedConceptPath,
                                      @Value("${application.fence.standard.access.rules}") String fenceStandardAccessRules,
                                      @Value("${application.template.path}") String templatePath) {
        this.userRepo = userRepo;
        this.roleRepo = roleRepo;
        this.connectionRepo = connectionRepo;
        this.accessRuleRepo = accessRuleRepo;
        this.applicationRepo = applicationRepo;
        this.privilegeRepo = privilegeRepo;
        this.userService = userService;
        this.restClientUtil = restClientUtil;
        idp_provider_uri = idpProviderUri;
        fence_client_id = fenceClientId;
        fence_client_secret = fenceClientSecret;
        fence_redirect_url = fenceRedirectUrl;
        fence_consent_group_concept_path = fenceConsentGroupConceptPath;
        fence_harmonized_concept_path = fenceHarmonizedConceptPath;
        fence_standard_access_rules = fenceStandardAccessRules;
        this.templatePath = templatePath;
    }

    @PostConstruct
    public void initializeFenceService() {
        picSureApp = applicationRepo.findByName("PICSURE");
        fenceConnection = connectionRepo.findByLabel("FENCE");
        fenceMapping = getFENCEMapping();
    }

    private JsonNode getFENCEUserProfile(String access_token) {
        logger.debug("getFENCEUserProfile() starting");
        logger.debug("getFENCEUserProfile() getting user profile from uri:{}/user/user", this.idp_provider_uri);

        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", "Bearer " + access_token);

        JsonNode fence_user_profile_response = null;
        try {
            ResponseEntity<String> response = this.restClientUtil.retrieveGetResponse(
                    this.idp_provider_uri + "/user/user",
                    headers
            );

            fence_user_profile_response = objectMapper.readTree(response.getBody());
        } catch (RestClientException ex) {
            logger.error("getFENCEUserProfile() failed to call FENCE user service, {}", ex.getMessage());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        logger.debug("getFENCEUserProfile() finished, returning user profile" + fence_user_profile_response.asText());
        return fence_user_profile_response;
    }

    private JsonNode getFENCEAccessToken(String fence_code) {
        logger.debug("getFENCEAccessToken() starting, using FENCE code");

        Base64.Encoder encoder = Base64.getEncoder();
        String fence_auth_header = this.fence_client_id + ":" + this.fence_client_secret;

        HttpHeaders headers = new HttpHeaders();
        headers.add("Content-Type", "application/x-www-form-urlencoded");
        headers.add("Authorization", "Basic " + encoder.encodeToString(fence_auth_header.getBytes()));

        // Build the request body, as JSON
        String query_string =
                "grant_type=authorization_code"
                        + "&code=" + fence_code
                        + "&redirect_uri=" + this.fence_redirect_url;

        String fence_token_url = this.idp_provider_uri + "/user/oauth2/token";

        JsonNode resp = null;
        try {
            ResponseEntity<String> response = this.restClientUtil.retrievePostResponse(
                    fence_token_url,
                    headers,
                    query_string
            );

            resp = objectMapper.readTree(response.getBody());
        } catch (Exception ex) {
            logger.error("getFENCEAccessToken() failed to call FENCE token service, {}", ex.getMessage());
        }
        logger.debug("getFENCEAccessToken() finished. {}", resp.asText());
        return resp;
    }

    // Get access_token from FENCE, based on the provided `code`
    public HashMap<String, String> getFENCEProfile(Map<String, String> authRequest) {
        logger.debug("getFENCEProfile() starting...");
        String fence_code = authRequest.get("code");

        JsonNode fence_user_profile = null;
        // Get the Gen3/FENCE user profile. It is a JsonNode object
        try {
            logger.debug("getFENCEProfile() query FENCE for user profile with code");
            fence_user_profile = getFENCEUserProfile(getFENCEAccessToken(fence_code).get("access_token").asText());
            logger.debug("getFENCEProfile() user profile structure:{}", fence_user_profile.asText());
            logger.debug("getFENCEProfile() .username:{}", fence_user_profile.get("username"));
            logger.debug("getFENCEProfile() .user_id:{}", fence_user_profile.get("user_id"));
            logger.debug("getFENCEProfile() .email:{}", fence_user_profile.get("email"));
        } catch (Exception ex) {
            logger.error("getFENCEToken() could not retrieve the user profile from the auth provider, because {}", ex.getMessage(), ex);
            throw new NotAuthorizedException("Could not get the user profile " +
                    "from the Gen3 authentication provider." + ex.getMessage());
        }

        User current_user = null;
        try {
            // Create or retrieve the user profile from our database, based on the the key
            // in the Gen3/FENCE profile
            current_user = createUserFromFENCEProfile(fence_user_profile);
            logger.info("getFENCEProfile() saved details for user with e-mail:{} and subject:{}", current_user.getEmail(), current_user.getSubject());

        } catch (Exception ex) {
            logger.error("getFENCEToken() Could not persist the user information, because {}", ex.getMessage());
            throw new NotAuthorizedException("The user details could not be persisted. Please contact the administrator.");
        }

        // Update the user's roles (or create them if none exists)
        //Set<Role> actual_user_roles = u.getRoles();
        Iterator<String> access_role_names = fence_user_profile.get("project_access").fieldNames();
        while (access_role_names.hasNext()) {
            String access_role_name = access_role_names.next();

            // These two special access does not matter. We are not using it.
            if (access_role_name.equals("admin") || access_role_name.equals("parent")) {
                continue;
            }

            logger.debug("getFENCEProfile() AccessRole:" + access_role_name);
            String[] parts = access_role_name.split("\\.");

            String newRoleName;
            if (parts.length > 1) {
                newRoleName = "FENCE_" + parts[0] + "_" + parts[parts.length - 1];
            } else {
                newRoleName = "FENCE_" + access_role_name;
            }
            logger.info("getFENCEProfile() New PSAMA role name:" + newRoleName);

            if (upsertRole(current_user, newRoleName, "FENCE role " + newRoleName)) {
                logger.info("getFENCEProfile() Updated user role. Now it includes `" + newRoleName + "`");
            } else {
                logger.error("getFENCEProfile() could not add roles to user's profile");
            }
        }
        try {
            userService.changeRole(current_user, current_user.getRoles());
            logger.debug("upsertRole() updated user, who now has {} roles.", current_user.getRoles().size());
        } catch (Exception ex) {
            logger.error("upsertRole() Could not add roles to user, because {}", ex.getMessage());
        }
        HashMap<String, Object> claims = new HashMap<String, Object>();
        claims.put("name", fence_user_profile.get("name"));
        claims.put("email", current_user.getEmail());
        claims.put("sub", current_user.getSubject());
        HashMap<String, String> responseMap = userService.getUserProfileResponse(claims);
        logger.debug("getFENCEProfile() UserProfile response object has been generated");

        logger.debug("getFENCEToken() finished");
        return responseMap;
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
        new_user.setEmail(node.get("email").asText());
        new_user.setGeneralMetadata(node.toString());
        // This is a hack, but someone has to do it.
        new_user.setAcceptedTOS(new Date());
        new_user.setConnection(fenceConnection);
        logger.debug("createUserFromFENCEProfile() finished setting fields");

        // Clear current set of roles every time we create or retrieve a user
        new_user.setRoles(new HashSet<>());
        logger.debug("createUserFromFENCEProfile() cleared roles");

        User actual_user = userRepo.save(new_user);
        logger.debug("createUserFromFENCEProfile() finished, user record inserted");
        return actual_user;
    }

    /**
     * Insert or Update the User object's list of Roles in the database.
     *
     * @param u               The User object the generated Role will be added to
     * @param roleName        Name of the Role
     * @param roleDescription Description of the Role
     * @return boolean Whether the Role was successfully added to the User or not
     */
    private boolean upsertRole(User u, String roleName, String roleDescription) {
        boolean status = false;
        logger.debug("upsertRole() starting for user subject:{}", u.getSubject());

        // Get the User's list of Roles. The first time, this will be an empty Set.
        // This method is called for every Role, and the User's list of Roles will
        // be updated for all subsequent calls.
        try {
            Role r = null;
            // Create the Role in the repository, if it does not exist. Otherwise, add it.
            Role existing_role = roleRepo.findByName(roleName);
            if (existing_role != null) {
                // Role already exists
                logger.info("upsertRole() role already exists");
                r = existing_role;
            } else {
                // This is a new Role
                r = new Role();
                r.setName(roleName);
                r.setDescription(roleDescription);
                // Since this is a new Role, we need to ensure that the
                // corresponding Privilege (with gates) and AccessRule is added.
                //r.setPrivileges(upsertPrivilege(u, r));
                roleRepo.save(r);
                logger.info("upsertRole() created new role");
            }
            u.getRoles().add(r);
            status = true;
        } catch (Exception ex) {
            logger.error("upsertRole() Could not inser/update role {} to repo, because {}", roleName, ex.getMessage());
        }


        logger.debug("upsertRole() finished");
        return status;
    }

    private Set<Privilege> upsertPrivilege(User u, Role r) {
        String roleName = r.getName();
        logger.info("upsertPrivilege() starting, adding privilege to role {}", roleName);

        String[] parts = roleName.split("_");
        String project_name = parts[1];
        String consent_group = parts[2];
        String concept_path = fenceMapping.get(project_name);

        // Get privilege and assign it to this role.
        String privilegeName = r.getName().replaceFirst("FENCE_*", "PRIV_FENCE_");
        logger.info("upsertPrivilege() Looking for privilege, with name : {}", privilegeName);

        Set<Privilege> privs = r.getPrivileges();
        if (privs == null) {
            privs = new HashSet<Privilege>();
        }

        Privilege p = privilegeRepo.findByName(privilegeName);
        if (p != null) {
            logger.info("upsertPrivilege() Assigning privilege {} to role {}", p.getName(), r.getName());
            privs.add(p);

        } else {
            logger.info("upsertPrivilege() This is a new privilege");
            logger.info("upsertPrivilege() project:{} consent_group:{} concept_path:{}", project_name, consent_group, concept_path);

            // Add new privilege PRIV_FENCE_phs######_c# and PRIV_FENCE_phs######_c#_HARMONIZED
            privs.add(createNewPrivilege(project_name, consent_group, concept_path, false));
            privs.add(createNewPrivilege(project_name, consent_group, fence_harmonized_concept_path, true));
        }
        logger.info("upsertPrivilege() Finished");
        return privs;
    }

    private Privilege createNewPrivilege(String project_name, String consent_group, String queryScopeConceptPath, boolean isHarmonized) {
        Privilege priv = new Privilege();

        // Build Privilege Object
        try {
            priv.setApplication(picSureApp);
            priv.setName("PRIV_FENCE_" + project_name + "_" + consent_group + (isHarmonized ? "_HARMONIZED" : ""));
            priv.setDescription("FENCE privilege for " + project_name + "/" + consent_group);
            priv.setQueryScope(queryScopeConceptPath);

            String consent_concept_path = fence_consent_group_concept_path;
            String queryTemplateText = "{\"categoryFilters\": {\""
                    + consent_concept_path
                    + "\":\""
                    + project_name + "." + consent_group
                    + "\"},"
                    + "\"numericFilters\":{},\"requiredFields\":[],"
                    + "\"variantInfoFilters\":[{\"categoryVariantInfoFilters\":{},\"numericVariantInfoFilters\":{}}],"
                    + "\"expectedResultType\": \"COUNT\""
                    + "}";
            priv.setQueryTemplate(queryTemplateText);
            priv.setQueryScope(queryScopeConceptPath);

            AccessRule ar = upsertAccessRule(project_name, consent_group);
            Set<AccessRule> accessRules = new HashSet<AccessRule>();
            accessRules.add(ar);
            // Add additionanl access rules
            for (String arName : fence_standard_access_rules.split(",")) {
                if (arName.startsWith("AR_")) {
                    logger.info("Adding AccessRule {} to privilege {}", arName, priv.getName());
                    accessRules.add(accessRuleRepo.findByName(arName));
                }
            }
            priv.setAccessRules(accessRules);
            logger.info("createNewPrivilege() Added {} access_rules to privilege", accessRules.size());

            privilegeRepo.save(priv);
            logger.info("createNewPrivilege() Added new privilege {} to DB", priv.getName());
        } catch (Exception ex) {
            ex.printStackTrace();
            logger.error("createNewPrivilege() could not save privilege");
        }
        return priv;
    }

    private AccessRule upsertAccessRule(String project_name, String consent_group) {
        logger.debug("upsertAccessRule() starting");
        String ar_name = "AR_" + project_name + "_" + consent_group;
        AccessRule ar = accessRuleRepo.findByName(ar_name);
        if (ar != null) {
            logger.info("upsertAccessRule() AccessRule {} already exists.", ar_name);
            return ar;
        }

        logger.info("upsertAccessRule() Creating new access rule {}", ar_name);
        ar = new AccessRule();
        ar.setName(ar_name);
        ar.setDescription("FENCE AR for " + project_name + "/" + consent_group);
        String ruleText = "$..categoryFilters.['" +
                fence_consent_group_concept_path +
                "']";
        ar.setRule(ruleText);
        ar.setType(AccessRule.TypeNaming.ALL_EQUALS);
        ar.setValue(project_name + "." + consent_group);
        ar.setCheckMapKeyOnly(false);
        ar.setCheckMapNode(true);
        ar.setEvaluateOnlyByGates(false);
        ar.setGateAnyRelation(false);

        // Assign all GATE_ access rules to this AR access rule.
        Set<AccessRule> gates = new HashSet<AccessRule>();
        for (String accessruleName : fence_standard_access_rules.split("\\,")) {
            if (accessruleName.startsWith("GATE_")) {
                logger.info("upsertAccessRule() Assign gate {} to access_rule {}", accessruleName, ar.getName());
                gates.add(accessRuleRepo.findByName(accessruleName));
            }
        }
        ar.setGates(gates);

        accessRuleRepo.save(ar);

        logger.debug("upsertAccessRule() finished");
        return ar;
    }

    /*
     * Get the mappings of fence privileges to paths
     */
    @SuppressWarnings("unchecked")
    private Map<String, String> getFENCEMapping() {
        try {
            return this.objectMapper.readValue(
                    new File(String.join(File.separator,
                            new String[]{this.templatePath, "fence_mapping.json"}))
                    , Map.class);
        } catch (IOException e) {
            logger.error("fence_mapping.json not found at {}", this.templatePath);
        }
        return Map.of();
    }

}
