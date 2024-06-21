package edu.harvard.hms.dbmi.avillach.auth.service.impl.authentication;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.harvard.hms.dbmi.avillach.auth.entity.AccessRule;
import edu.harvard.hms.dbmi.avillach.auth.entity.Application;
import edu.harvard.hms.dbmi.avillach.auth.entity.Connection;
import edu.harvard.hms.dbmi.avillach.auth.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.entity.Role;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.exceptions.NotAuthorizedException;
import edu.harvard.hms.dbmi.avillach.auth.model.fenceMapping.StudyMetaData;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.*;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.authorization.AccessRuleService;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class FENCEAuthenticationService {

    private final Logger logger = LoggerFactory.getLogger(FENCEAuthenticationService.class);

    private final UserService userService;
    private final RoleService roleService;
    private final ConnectionWebService connectionService; // We will need to investigate if the ConnectionWebService will need to be versioned as well.
    private final ApplicationService applicationService;
    private final PrivilegeService privilegeService;
    private final AccessRuleService accessRuleService;
    private final FenceMappingUtility fenceMappingUtility;

    private Application picSureApp;
    private Connection fenceConnection;

    private final Set<String> openAccessIdpValues = Set.of("fence", "ras");

    private final String idp_provider_uri;
    private final String fence_client_id;
    private final String fence_client_secret;
    private final String idp_provider;
    private final String fence_standard_access_rules;
    private final String fence_allowed_query_types;
    private final String variantAnnotationColumns;
    private final String templatePath;
    private final String fence_harmonized_consent_group_concept_path;
    private final String fence_parent_consent_group_concept_path;
    private final String fence_topmed_consent_group_concept_path;
    private String fence_harmonized_concept_path;

    private static final String parentAccessionField = "\\\\_Parent Study Accession with Subject ID\\\\";
    private static final String topmedAccessionField = "\\\\_Topmed Study Accession with Subject ID\\\\";
    public static final String fence_open_access_role_name = "FENCE_ROLE_OPEN_ACCESS";

    private String[] underscoreFields;

    private final RestClientUtil restClientUtil;

    private final ConcurrentHashMap<String, AccessRule> accessRuleCache = new ConcurrentHashMap<>();
    private Set<AccessRule> allowQueryTypeRules;

    @Autowired
    public FENCEAuthenticationService(UserService userService,
                                      RoleService roleService,
                                      ConnectionWebService connectionService,
                                      ApplicationService applicationService,
                                      PrivilegeService privilegeService,
                                      RestClientUtil restClientUtil,
                                      @Value("${application.idp.provider.uri}") String idpProviderUri,
                                      @Value("${fence.client.id}") String fenceClientId,
                                      @Value("${fence.client.secret}") String fenceClientSecret,
                                      @Value("${application.idp.provider}") String idpProvider,
                                      @Value("${fence.standard.access.rules}") String fenceStandardAccessRules,
                                      @Value("${fence.allowed.query.types}") String fenceAllowedQueryTypes,
                                      @Value("${fence.variant.annotation.columns}") String variantAnnotationColumns,
                                      @Value("${application.template.path}") String templatePath,
                                      @Value("${fence.harmonized.consent.group.concept.path}") String fenceHarmonizedConsentGroupConceptPath,
                                      @Value("${fence.parent.consent.group.concept.path}") String fenceParentConceptPath,
                                      @Value("${fence.topmed.consent.group.concept.path}") String fenceTopmedConceptPath,
                                      @Value("${fence.consent.group.concept.path}") String fenceHarmonizedConceptPath,
                                      AccessRuleService accessRuleService, FenceMappingUtility fenceMappingUtility) {
        this.userService = userService;
        this.roleService = roleService;
        this.connectionService = connectionService;
        this.applicationService = applicationService;
        this.privilegeService = privilegeService;
        this.idp_provider_uri = idpProviderUri;
        this.fence_client_id = fenceClientId;
        this.fence_client_secret = fenceClientSecret;
        this.idp_provider = idpProvider;
        this.fence_standard_access_rules = fenceStandardAccessRules;
        this.fence_allowed_query_types = fenceAllowedQueryTypes;
        this.variantAnnotationColumns = variantAnnotationColumns;
        this.templatePath = templatePath;
        this.restClientUtil = restClientUtil;
        this.accessRuleService = accessRuleService;
        this.fence_parent_consent_group_concept_path = fenceParentConceptPath;
        this.fence_topmed_consent_group_concept_path = fenceTopmedConceptPath;
        this.fence_harmonized_concept_path = fenceHarmonizedConceptPath;
        this.fence_harmonized_consent_group_concept_path = fenceHarmonizedConsentGroupConceptPath;
        this.fenceMappingUtility = fenceMappingUtility;
    }

    @EventListener(ContextRefreshedEvent.class)
    public void initializeFenceService() {
        picSureApp = applicationService.getApplicationByName("PICSURE");
        fenceConnection = connectionService.getConnectionByLabel("FENCE");

        // We need to set the underscoreFields here so that we can use them in the access rules during PostConstruct
        // If we don't set them here, we will get a NullPointerException when we try to use them in the access rules
        underscoreFields = new String[]{
                parentAccessionField,
                topmedAccessionField,
                fence_harmonized_consent_group_concept_path,
                fence_parent_consent_group_concept_path,
                fence_topmed_consent_group_concept_path,
                "\\\\_VCF Sample Id\\\\",
                "\\\\_studies\\\\",
                "\\\\_studies_consents\\\\",  //used to provide consent-level counts for open access
                "\\\\_parent_consents\\\\",  //parent consents not used for auth (use combined _consents)
                "\\\\_Consents\\\\"   ///old _Consents\Short Study... path no longer used, but still present in examples.
        };

        // log all the properties
        logger.info("idp_provider_uri: {}", idp_provider_uri);
        logger.info("idp_provider: {}", idp_provider);
        logger.info("fence_standard_access_rules: {}", fence_standard_access_rules);
        logger.info("fence_allowed_query_types: {}", fence_allowed_query_types);
        logger.info("variantAnnotationColumns: {}", variantAnnotationColumns);
        logger.info("templatePath: {}", templatePath);
        logger.info("fence_harmonized_consent_group_concept_path: {}", fence_harmonized_consent_group_concept_path);
        logger.info("fence_parent_consent_group_concept_path: {}", fence_parent_consent_group_concept_path);
        logger.info("fence_topmed_consent_group_concept_path: {}", fence_topmed_consent_group_concept_path);
        logger.info("fence_harmonized_concept_path: {}", fence_harmonized_concept_path);
        logger.info("underscoreFields: {}", Arrays.toString(underscoreFields));

        if (!fenceMappingUtility.getFENCEMapping().isEmpty() && !fenceMappingUtility.getFenceMappingByAuthZ().isEmpty()) {
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
                String newRoleName = StringUtils.isNotBlank(consentCode) ? "FENCE_" + projectId + "_" + consentCode : "FENCE_" + projectId;

                return createRole(newRoleName, "FENCE role " + newRoleName);
            }).filter(Objects::nonNull).collect(Collectors.toSet());

            roleService.persistAll(roles);
        } else {
            logger.error("initializeFenceService() -> createAndUpsertRole could not find any studies in FENCE mapping");
        }
    }

    public HashMap<String, String> getFENCEProfile(String callback_url, Map<String, String> authRequest) {
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
            fence_user_profile = getFENCEUserProfile(getFENCEAccessToken(callback_url, fence_code).get("access_token").asText());

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

            accessRuleService.evictFromCache(current_user);
            userService.evictFromCache(current_user);
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
            String newRoleName = StringUtils.isNotBlank(consentCode) ? "FENCE_" + projectId + "_" + consentCode : "FENCE_" + projectId;

            roleNames.add(newRoleName);
        });

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
                .map(roleName -> createRole(roleName, "FENCE role " + roleName))
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

    private Role createRole(String roleName, String roleDescription) {
        if (roleName.isEmpty()) {
            logger.error("createRole() roleName is empty");
            return null;
        }
        logger.info("getFENCEProfile() New PSAMA role name:{}", roleName);
        Role r;
        // Create the Role in the repository, if it does not exist. Otherwise, add it.
        Role existing_role = roleService.findByName(roleName);
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
            r.setPrivileges(addFENCEPrivileges(r));
            logger.info("upsertRole() created new role");
        }

        return r;
    }

    private JsonNode getFENCEUserProfile(String access_token) {
        logger.debug("getFENCEUserProfile() starting");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(access_token);

        logger.debug("getFENCEUserProfile() getting user profile from uri:{}/user/user", this.idp_provider_uri);
        ResponseEntity<String> fence_user_profile_response = this.restClientUtil.retrieveGetResponse(
                this.idp_provider_uri + "/user/user",
                headers
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

    /**
     * Insert or Update the User object's list of Roles in the database.
     *
     * @param u               The User object the generated Role will be added to
     * @param roleName        Name of the Role
     * @param roleDescription Description of the Role
     * @return boolean Whether the Role was successfully added to the User or not
     */
    public boolean upsertRole(User u, String roleName, String roleDescription) {
        boolean status = false;

        // Get the User's list of Roles. The first time, this will be an empty Set.
        // This method is called for every Role, and the User's list of Roles will
        // be updated for all subsequent calls.
        try {
            Role r = null;
            // Create the Role in the Servicesitory, if it does not exist. Otherwise, add it.
            Role existing_role = roleService.getRoleByName(roleName);
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
                r.setPrivileges(addFENCEPrivileges(r));
                roleService.save(r);
                logger.info("upsertRole() created new role");
            }
            if (u != null) {
                u.getRoles().add(r);
            }
            status = true;
        } catch (Exception ex) {
            logger.error("upsertRole() Could not inser/update role {} to Service", roleName, ex);
        }


        logger.debug("upsertRole() finished");
        return status;
    }

    private Set<Privilege> addFENCEPrivileges(Role r) {
        String roleName = r.getName();
        logger.info("addFENCEPrivileges() starting, adding privilege(s) to role {}", roleName);

        //each project can have up to three privileges: Parent  |  Harmonized  | Topmed
        //harmonized has 2 ARs for parent + harminized and harmonized only
        //Topmed has up to three ARs for topmed / topmed + parent / topmed + harmonized
        Set<Privilege> privs = r.getPrivileges();
        if (privs == null) {
            privs = new HashSet<Privilege>();
        }

        //e.g. FENCE_phs0000xx_c2 or FENCE_tutorial-biolinc_camp
        String project_name = extractProject(roleName);
        if (project_name.length() <= 0) {
            logger.warn("addFENCEPrivileges() role name: {} returned an empty project name", roleName);
        }
        String consent_group = extractConsentGroup(roleName);
        if (consent_group.length() <= 0) {
            logger.warn("addFENCEPrivileges() role name: {} returned an empty consent group", roleName);
        }
        logger.info("addFENCEPrivileges() project name: {} consent group: {}", project_name, consent_group);

        // Look up the metadata by consent group.
        StudyMetaData projectMetadata = getFENCEMappingforProjectAndConsent(project_name, consent_group);

        if (projectMetadata == null) {
            //no privileges means no access to this project.  just return existing set of privs.
            logger.warn("No metadata available for project {}.{}", project_name, consent_group);
            return privs;
        }

        logger.info("addPrivileges() This is a new privilege");

        String dataType = projectMetadata.getDataType();
        Boolean isHarmonized = projectMetadata.getIsHarmonized();
        String concept_path = projectMetadata.getTopLevelPath();
        String projectAlias = projectMetadata.getAbbreviatedName();

        // we need to add escape sequence back in to the path for parsing later (also need to double escape the regex)
        // we need to do this for the query Template and scopes, but should NOT do this for the rules.
        if (concept_path != null) {
            concept_path = concept_path.replaceAll("\\\\", "\\\\\\\\");
        }

        if (dataType != null && dataType.contains("G")) {
            //insert genomic/topmed privs - this will also add rules for including harmonized & parent data if applicable
            privs.add(upsertTopmedPrivilege(project_name, projectAlias, consent_group, concept_path, isHarmonized));
        }

        if (dataType != null && dataType.contains("P")) {
            //insert clinical privs
            logger.info("addPrivileges() project:{} consent_group:{} concept_path:{}", project_name, consent_group, concept_path);
            privs.add(upsertClinicalPrivilege(project_name, projectAlias, consent_group, concept_path, false));

            //if harmonized study, also create harmonized privileges
            if (Boolean.TRUE.equals(isHarmonized)) {
                privs.add(upsertClinicalPrivilege(project_name, projectAlias, consent_group, concept_path, true));
            }
        }

        //projects without G or P in data_type are skipped
        if (dataType == null || (!dataType.contains("P") && !dataType.contains("G"))) {
            logger.warn("Missing study type for {} {}. Skipping.", project_name, consent_group);
        }

        logger.info("addPrivileges() Finished");
        return privs;
    }

    /**
     * Creates a privilege with a set of access rules that allow queries containing a consent group to pass if the query only contains valid entries that match conceptPath.  If the study is harmonized,
     * this also creates an access rule to allow access when using the harmonized consent concept path.
     * Privileges created with this method will deny access if any genomic filters (topmed data) are included.
     *
     * @param studyIdentifier The study identifier
     * @param consent_group   The consent group
     * @param conceptPath     The concept path
     * @param isHarmonized    Whether the study is harmonized
     * @return The created privilege
     */
    private Privilege upsertClinicalPrivilege(String studyIdentifier, String projectAlias, String consent_group, String conceptPath, boolean isHarmonized) {
        // Construct the privilege name
        String privilegeName = (consent_group != null && !consent_group.isEmpty()) ?
                "PRIV_FENCE_" + studyIdentifier + "_" + consent_group + (isHarmonized ? "_HARMONIZED" : "") :
                "PRIV_FENCE_" + studyIdentifier + (isHarmonized ? "_HARMONIZED" : "");

        // Check if the Privilege already exists
        Privilege priv = privilegeService.findByName(privilegeName);
        if (priv != null) {
            logger.info("{} already exists", privilegeName);
            return priv;
        }

        priv = new Privilege();
        try {
            priv.setApplication(picSureApp);
            priv.setName(privilegeName);

            // Set consent concept path
            String consent_concept_path = isHarmonized ? fence_harmonized_consent_group_concept_path : fence_parent_consent_group_concept_path;
            if (!consent_concept_path.contains("\\\\")) {
                consent_concept_path = consent_concept_path.replaceAll("\\\\", "\\\\\\\\");
                logger.debug("Escaped consent concept path: {}", consent_concept_path);
            }

            if (fence_harmonized_concept_path != null && !fence_harmonized_concept_path.contains("\\\\")) {
                //these have to be escaped again so that jaxson can convert it correctly
                fence_harmonized_concept_path = fence_harmonized_concept_path.replaceAll("\\\\", "\\\\\\\\");
                logger.debug("upsertTopmedPrivilege(): escaped harmonized consent path" + fence_harmonized_concept_path);
            }


            String studyIdentifierField = (consent_group != null && !consent_group.isEmpty()) ? studyIdentifier + "." + consent_group : studyIdentifier;
            String queryTemplateText = String.format(
                    "{\"categoryFilters\": {\"%s\":[\"%s\"]},\"numericFilters\":{},\"requiredFields\":[],\"fields\":[],\"variantInfoFilters\":[{\"categoryVariantInfoFilters\":{},\"numericVariantInfoFilters\":{}}],\"expectedResultType\": \"COUNT\"}",
                    consent_concept_path, studyIdentifierField
            );

            priv.setQueryTemplate(queryTemplateText);
            priv.setQueryScope(isHarmonized ? String.format("[\"%s\",\"_\",\"%s\"]", conceptPath, fence_harmonized_concept_path) : String.format("[\"%s\",\"_\"]", conceptPath));

            // Initialize the set of AccessRules
            Set<AccessRule> accessrules = new HashSet<>();

            // Create and add the parent consent access rule
            AccessRule ar = createConsentAccessRule(studyIdentifier, consent_group, "PARENT", fence_parent_consent_group_concept_path);
            configureAccessRule(ar, studyIdentifier, consent_group, conceptPath, projectAlias, true, false, false);
            accessrules.add(ar);

            // Create and add the Topmed+Parent access rule
            ar = upsertTopmedAccessRule(studyIdentifier, consent_group, "TOPMED+PARENT");
            configureAccessRule(ar, studyIdentifier, consent_group, conceptPath, projectAlias, true, false, true);
            accessrules.add(ar);

            // If harmonized, create and add the harmonized access rule
            if (isHarmonized) {
                ar = createConsentAccessRule(studyIdentifier, consent_group, "HARMONIZED", fence_harmonized_consent_group_concept_path);
                configureHarmonizedAccessRule(ar, studyIdentifier, consent_group, conceptPath, projectAlias);
                accessrules.add(ar);
            }

            // Add standard access rules
            addStandardAccessRules(accessrules);

            priv.setAccessRules(accessrules);
            logger.info("Added {} access_rules to privilege", accessrules.size());

            privilegeService.save(priv);
            logger.info("Added new privilege {} to DB", priv.getName());
        } catch (Exception ex) {
            logger.error("Could not save privilege", ex);
        }
        return priv;
    }

    /**
     * Configures the AccessRule with gates and sub-rules.
     *
     * @param ar              The AccessRule to configure.
     * @param studyIdentifier The study identifier.
     * @param consent_group   The consent group.
     * @param conceptPath     The concept path.
     * @param projectAlias    The project alias.
     * @param parent          Whether to include parent gates.
     * @param harmonized      Whether to include harmonized gates.
     * @param topmed          Whether to include Topmed gates.
     */
    private void configureAccessRule(AccessRule ar, String studyIdentifier, String consent_group, String conceptPath, String projectAlias, boolean parent, boolean harmonized, boolean topmed) {
        if (ar.getGates() == null) {
            ar.setGates(new HashSet<>());
            ar.getGates().addAll(getGates(parent, harmonized, topmed));

            if (ar.getSubAccessRule() == null) {
                ar.setSubAccessRule(new HashSet<>());
            }
            ar.getSubAccessRule().addAll(getAllowedQueryTypeRules());
            ar.getSubAccessRule().addAll(getPhenotypeSubRules(studyIdentifier, conceptPath, projectAlias));
            ar.getSubAccessRule().addAll(getTopmedRestrictedSubRules());
            accessRuleService.save(ar);
        }
    }

    /**
     * Configures the harmonized AccessRule with gates and sub-rules.
     *
     * @param ar              The AccessRule to configure.
     * @param studyIdentifier The study identifier.
     * @param consent_group   The consent group.
     * @param conceptPath     The concept path.
     * @param projectAlias    The project alias.
     */
    private void configureHarmonizedAccessRule(AccessRule ar, String studyIdentifier, String consent_group, String conceptPath, String projectAlias) {
        if (ar.getGates() == null) {
            ar.setGates(new HashSet<>());
            ar.getGates().add(upsertConsentGate("HARMONIZED_CONSENT", "$.query.query.categoryFilters." + fence_harmonized_consent_group_concept_path + "[*]", true, "harmonized data"));

            if (ar.getSubAccessRule() == null) {
                ar.setSubAccessRule(new HashSet<>());
            }
            ar.getSubAccessRule().addAll(getAllowedQueryTypeRules());
            ar.getSubAccessRule().addAll(getHarmonizedSubRules());
            ar.getSubAccessRule().addAll(getPhenotypeSubRules(studyIdentifier, conceptPath, projectAlias));
            accessRuleService.save(ar);
        }
    }

    private Set<AccessRule> getAllowedQueryTypeRules() {
        if (allowQueryTypeRules == null) {
            allowQueryTypeRules = loadAllowedQueryTypeRules();
        }

        return allowQueryTypeRules;
    }

    /**
     * Retrieves or creates AccessRules for allowed query types.
     *
     * @return A set of AccessRules for allowed query types.
     */
    private Set<AccessRule> loadAllowedQueryTypeRules() {
        // Initialize a set to hold the AccessRules
        Set<AccessRule> rules = new HashSet<>();
        // Split the allowed query types from the configuration
        String[] allowedTypes = this.fence_allowed_query_types.split(",");

        // Iterate over each allowed query type
        for (String queryType : allowedTypes) {
            // Construct the AccessRule name
            String ar_name = "AR_ALLOW_" + queryType;

            // Log the creation of a new AccessRule
            AccessRule ar = getOrCreateAccessRule(
                    ar_name,
                    "FENCE SUB AR to allow " + queryType + " Queries",
                    "$.query.query.expectedResultType",
                    AccessRule.TypeNaming.ALL_EQUALS,
                    queryType,
                    false,
                    false,
                    false,
                    false
            );

            // Add the newly created rule to the set
            rules.add(ar);
        }
        // Return the set of AccessRules
        return rules;
    }

    private Collection<? extends AccessRule> getTopmedRestrictedSubRules() {
        Set<AccessRule> rules = new HashSet<AccessRule>();
        rules.add(upsertTopmedRestrictedSubRule("CATEGORICAL", "$.query.query.variantInfoFilters[*].categoryVariantInfoFilters.*"));
        rules.add(upsertTopmedRestrictedSubRule("NUMERIC", "$.query.query.variantInfoFilters[*].numericVariantInfoFilters.*"));

        return rules;
    }

    /**
     * Creates and returns a restricted sub-rule AccessRule for Topmed.
     * topmed restriction rules don't need much configuration.  Just deny all access.
     *
     * @param type The type of the Topmed restriction.
     * @param rule The rule expression.
     * @return The created AccessRule.
     */
    private AccessRule upsertTopmedRestrictedSubRule(String type, String rule) {
        // Construct the AccessRule name
        String ar_name = "AR_TOPMED_RESTRICTED_" + type;
        // Check if the AccessRule already exists
        AccessRule ar = accessRuleService.getAccessRuleByName(ar_name);
        if (ar != null) {
            // Log and return the existing rule
            logger.debug("Found existing rule: {}", ar.getName());
            return ar;
        }

        // Log the creation of a new AccessRule
        // Create the AccessRule using the createAccessRule method
        return getOrCreateAccessRule(
                ar_name,
                "FENCE SUB AR for restricting " + type + " genomic concepts",
                rule,
                AccessRule.TypeNaming.IS_EMPTY,
                null,
                false,
                false,
                false,
                false
        );
    }

    private Collection<? extends AccessRule> getPhenotypeSubRules(String studyIdentifier, String conceptPath, String alias) {

        Set<AccessRule> rules = new HashSet<AccessRule>();
        //categorical filters will always contain at least one entry (for the consent groups); it will never be empty
        rules.add(createPhenotypeSubRule(fence_parent_consent_group_concept_path, "ALLOW_PARENT_CONSENT", "$.query.query.categoryFilters", AccessRule.TypeNaming.ALL_CONTAINS, "", true));

        for (String underscorePath : underscoreFields) {
            rules.add(createPhenotypeSubRule(underscorePath, "ALLOW " + underscorePath, "$.query.query.fields.[*]", AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY, "FIELDS", false));
            rules.add(createPhenotypeSubRule(underscorePath, "ALLOW " + underscorePath, "$.query.query.categoryFilters", AccessRule.TypeNaming.ALL_CONTAINS, "CATEGORICAL", true));
            rules.add(createPhenotypeSubRule(underscorePath, "ALLOW " + underscorePath, "$.query.query.requiredFields.[*]", AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY, "REQ_FIELDS", false));
        }

        rules.add(createPhenotypeSubRule(conceptPath, alias + "_" + studyIdentifier, "$.query.query.categoryFilters", AccessRule.TypeNaming.ALL_CONTAINS, "CATEGORICAL", true));
        rules.add(createPhenotypeSubRule(conceptPath, alias + "_" + studyIdentifier, "$.query.query.numericFilters", AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY, "NUMERIC", true));
        rules.add(createPhenotypeSubRule(conceptPath, alias + "_" + studyIdentifier, "$.query.query.fields.[*]", AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY, "FIELDS", false));
        rules.add(createPhenotypeSubRule(conceptPath, alias + "_" + studyIdentifier, "$.query.query.requiredFields.[*]", AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY, "REQUIRED_FIELDS", false));

        return rules;
    }

    /**
     * Harmonized rules should allow the user to supply paretn and top med consent groups;  this allows a single harmonized
     * rules instead of splitting between a topmed+harmonized and parent+harmonized
     *
     * @return
     */
    private Collection<? extends AccessRule> getHarmonizedSubRules() {

        Set<AccessRule> rules = new HashSet<AccessRule>();
        //categorical filters will always contain at least one entry (for the consent groups); it will never be empty
        rules.add(createPhenotypeSubRule(fence_parent_consent_group_concept_path, "ALLOW_PARENT_CONSENT", "$.query.query.categoryFilters", AccessRule.TypeNaming.ALL_CONTAINS, "", true));
        rules.add(createPhenotypeSubRule(fence_harmonized_consent_group_concept_path, "ALLOW_HARMONIZED_CONSENT", "$.query.query.categoryFilters", AccessRule.TypeNaming.ALL_CONTAINS, "", true));
        rules.add(createPhenotypeSubRule(fence_topmed_consent_group_concept_path, "ALLOW_TOPMED_CONSENT", "$.query.query.categoryFilters", AccessRule.TypeNaming.ALL_CONTAINS, "", true));

        for (String underscorePath : underscoreFields) {
            rules.add(createPhenotypeSubRule(underscorePath, "ALLOW " + underscorePath, "$.query.query.fields.[*]", AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY, "FIELDS", false));
            rules.add(createPhenotypeSubRule(underscorePath, "ALLOW " + underscorePath, "$.query.query.categoryFilters", AccessRule.TypeNaming.ALL_CONTAINS, "CATEGORICAL", true));
            rules.add(createPhenotypeSubRule(underscorePath, "ALLOW " + underscorePath, "$.query.query.requiredFields.[*]", AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY, "REQ_FIELDS", false));
        }

        rules.add(createPhenotypeSubRule(fence_harmonized_concept_path, "HARMONIZED", "$.query.query.categoryFilters", AccessRule.TypeNaming.ALL_CONTAINS, "CATEGORICAL", true));
        rules.add(createPhenotypeSubRule(fence_harmonized_concept_path, "HARMONIZED", "$.query.query.numericFilters", AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY, "NUMERIC", true));
        rules.add(createPhenotypeSubRule(fence_harmonized_concept_path, "HARMONIZED", "$.query.query.fields.[*]", AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY, "FIELDS", false));
        rules.add(createPhenotypeSubRule(fence_harmonized_concept_path, "HARMONIZED", "$.query.query.requiredFields.[*]", AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY, "REQUIRED_FIELDS", false));

        return rules;
    }


    /**
     * generate and return a set of rules that disallow access to phenotype data (only genomic filters allowed)
     *
     * @return
     */
    private Collection<? extends AccessRule> getPhenotypeRestrictedSubRules(String studyIdentifier, String consentCode, String alias) {
        Set<AccessRule> rules = new HashSet<AccessRule>();
        //categorical filters will always contain at least one entry (for the consent groups); it will never be empty
        rules.add(createPhenotypeSubRule(fence_topmed_consent_group_concept_path, "ALLOW_TOPMED_CONSENT", "$.query.query.categoryFilters", AccessRule.TypeNaming.ALL_CONTAINS, "", true));

        for (String underscorePath : underscoreFields) {
            rules.add(createPhenotypeSubRule(underscorePath, "ALLOW " + underscorePath, "$.query.query.fields.[*]", AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY, "FIELDS", false));
            rules.add(createPhenotypeSubRule(underscorePath, "ALLOW " + underscorePath, "$.query.query.categoryFilters", AccessRule.TypeNaming.ALL_CONTAINS, "CATEGORICAL", true));
            rules.add(createPhenotypeSubRule(underscorePath, "ALLOW " + underscorePath, "$.query.query.requiredFields.[*]", AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY, "REQ_FIELDS", false));
        }

        rules.add(createPhenotypeSubRule(null, alias + "_" + studyIdentifier + "_" + consentCode, "$.query.query.numericFilters.[*]", AccessRule.TypeNaming.IS_EMPTY, "DISALLOW_NUMERIC", false));
        rules.add(createPhenotypeSubRule(null, alias + "_" + studyIdentifier + "_" + consentCode, "$.query.query.requiredFields.[*]", AccessRule.TypeNaming.IS_EMPTY, "DISALLOW_REQUIRED_FIELDS", false));

        return rules;
    }

    /**
     * Return a set of gates that identify which consent values have been provided.  the boolean parameters indicate
     * if a value in the specified consent location should allow this gate to pass.
     *
     * @param parent
     * @param harmonized
     * @param topmed
     * @return
     */
    private Collection<? extends AccessRule> getGates(boolean parent, boolean harmonized, boolean topmed) {
        Set<AccessRule> gates = new HashSet<AccessRule>();
        gates.add(upsertConsentGate("PARENT_CONSENT", "$.query.query.categoryFilters." + fence_parent_consent_group_concept_path + "[*]", parent, "parent study data"));
        gates.add(upsertConsentGate("HARMONIZED_CONSENT", "$.query.query.categoryFilters." + fence_harmonized_consent_group_concept_path + "[*]", harmonized, "harmonized data"));
        gates.add(upsertConsentGate("TOPMED_CONSENT", "$.query.query.categoryFilters." + fence_topmed_consent_group_concept_path + "[*]", topmed, "Topmed data"));

        return gates;
    }

    /**
     * Creates a privilege for Topmed access. This has (up to) three access rules:
     * 1) topmed only 2) topmed + parent 3) topmed + harmonized.
     *
     * @param studyIdentifier
     * @param projectAlias
     * @param consentGroup
     * @param parentConceptPath
     * @param isHarmonized
     * @return Privilege
     */
    private Privilege upsertTopmedPrivilege(String studyIdentifier, String projectAlias, String consentGroup, String parentConceptPath, boolean isHarmonized) {
        String privilegeName = "PRIV_FENCE_" + studyIdentifier + "_" + consentGroup + "_TOPMED";
        Privilege priv = privilegeService.findByName(privilegeName);

        if (priv != null) {
            logger.info("upsertTopmedPrivilege() {} already exists", privilegeName);
            return priv;
        }

        priv = new Privilege();

        try {
            buildPrivilegeObject(priv, privilegeName, studyIdentifier, consentGroup);

            Set<AccessRule> accessRules = new HashSet<>();
            AccessRule topmedRule = upsertTopmedAccessRule(studyIdentifier, consentGroup, "TOPMED");

            populateAccessRule(topmedRule, false, false, true);
            topmedRule.getSubAccessRule().addAll(getPhenotypeRestrictedSubRules(studyIdentifier, consentGroup, projectAlias));
            accessRules.add(topmedRule);

            if (parentConceptPath != null) {
                AccessRule topmedParentRule = upsertTopmedAccessRule(studyIdentifier, consentGroup, "TOPMED+PARENT");
                populateAccessRule(topmedParentRule, true, false, true);
                topmedParentRule.getSubAccessRule().addAll(getPhenotypeSubRules(studyIdentifier, parentConceptPath, projectAlias));
                accessRules.add(topmedParentRule);

                if (isHarmonized) {
                    AccessRule harmonizedRule = upsertHarmonizedAccessRule(studyIdentifier, consentGroup, "HARMONIZED");
                    populateHarmonizedAccessRule(harmonizedRule, parentConceptPath, studyIdentifier, projectAlias);
                    accessRules.add(harmonizedRule);
                }
            }

            addStandardAccessRules(accessRules);

            priv.setAccessRules(accessRules);
            logger.info("upsertTopmedPrivilege() Added {} access_rules to privilege", accessRules.size());

            privilegeService.save(priv);
            logger.info("upsertTopmedPrivilege() Added new privilege {} to DB", priv.getName());
        } catch (Exception ex) {
            logger.error("upsertTopmedPrivilege() could not save privilege", ex);
        }

        return priv;
    }

    private void buildPrivilegeObject(Privilege priv, String privilegeName, String studyIdentifier, String consentGroup) {
        priv.setApplication(picSureApp);
        priv.setName(privilegeName);
        priv.setDescription("FENCE privilege for Topmed " + studyIdentifier + "." + consentGroup);

        String consentConceptPath = escapePath(fence_topmed_consent_group_concept_path);
        fence_harmonized_concept_path = escapePath(fence_harmonized_concept_path);

        String queryTemplateText = "{\"categoryFilters\": {\"" + consentConceptPath + "\":[\"" + studyIdentifier + "." + consentGroup + "\"]},"
                + "\"numericFilters\":{},\"requiredFields\":[],"
                + "\"fields\":[\"" + topmedAccessionField + "\"],"
                + "\"variantInfoFilters\":[{\"categoryVariantInfoFilters\":{},\"numericVariantInfoFilters\":{}}],"
                + "\"expectedResultType\": \"COUNT\""
                + "}";

        priv.setQueryTemplate(queryTemplateText);

        priv.setQueryScope(buildQueryScope(this.variantAnnotationColumns));
    }

    private String escapePath(String path) {
        if (path != null && !path.contains("\\\\")) {
            return path.replaceAll("\\\\", "\\\\\\\\");
        }
        return path;
    }

    private String buildQueryScope(String variantColumns) {
        if (variantColumns == null || variantColumns.isEmpty()) {
            return "[\"_\"]";
        }

        return Arrays.stream(variantColumns.split(","))
                .map(path -> "\"" + path + "\"")
                .collect(Collectors.joining(",", "[", ",\"_\"]"));
    }

    private void populateAccessRule(AccessRule rule, boolean includeParent, boolean includeHarmonized, boolean includeTopmed) {
        if (rule.getGates() == null) {
            rule.setGates(new HashSet<>(getGates(includeParent, includeHarmonized, includeTopmed)));
        }

        if (rule.getSubAccessRule() == null) {
            rule.setSubAccessRule(new HashSet<>(getAllowedQueryTypeRules()));
        }

        accessRuleService.save(rule);
    }

    private void populateHarmonizedAccessRule(AccessRule rule, String parentConceptPath, String studyIdentifier, String projectAlias) {
        if (rule.getGates() == null) {
            rule.setGates(new HashSet<>(Collections.singletonList(
                    upsertConsentGate("HARMONIZED_CONSENT", "$.query.query.categoryFilters." + fence_harmonized_consent_group_concept_path + "[*]", true, "harmonized data")
            )));
        }

        if (rule.getSubAccessRule() == null) {
            rule.setSubAccessRule(new HashSet<>(getAllowedQueryTypeRules()));
            rule.getSubAccessRule().addAll(getHarmonizedSubRules());
            rule.getSubAccessRule().addAll(getPhenotypeSubRules(studyIdentifier, parentConceptPath, projectAlias));
        }

        accessRuleService.save(rule);
    }

    // A set of standard access rules that are added to all privileges
    // to cache the standard access rules
    private Set<AccessRule> standardAccessRules;

    private void addStandardAccessRules(Set<AccessRule> accessRules) {
        if (standardAccessRules != null && !standardAccessRules.isEmpty()) {
            accessRules.addAll(standardAccessRules);
        } else {
            standardAccessRules = new HashSet<>();
            for (String arName : fence_standard_access_rules.split(",")) {
                if (arName.startsWith("AR_")) {
                    logger.info("Adding AccessRule {} to privilege", arName);
                    AccessRule ar = accessRuleService.getAccessRuleByName(arName);
                    if (ar != null) {
                        standardAccessRules.add(ar);
                    } else {
                        logger.warn("Unable to find an access rule with name {}", arName);
                    }
                }
            }

            accessRules.addAll(standardAccessRules);
        }
    }


    /**
     * Creates and returns a consent access rule AccessRule.
     * Generates Main rule only; gates & sub-rules attached after calling this
     * prentRule should be null if this is the main rule, or the appropriate value if this is a sub-rule
     *
     * @param studyIdentifier The study identifier.
     * @param consent_group   The consent group.
     * @param label           The label for the rule.
     * @param consent_path    The consent path.
     * @return The created AccessRule.
     */
    private AccessRule createConsentAccessRule(String studyIdentifier, String consent_group, String label, String consent_path) {
        String ar_name = (consent_group != null && !consent_group.isEmpty()) ? "AR_CONSENT_" + studyIdentifier + "_" + consent_group + "_" + label : "AR_CONSENT_" + studyIdentifier;
        String description = (consent_group != null && !consent_group.isEmpty()) ? "FENCE AR for " + studyIdentifier + "." + consent_group + " clinical concepts" : "FENCE AR for " + studyIdentifier + " clinical concepts";
        String ruleText = "$.query.query.categoryFilters." + consent_path + "[*]";
        String arValue = (consent_group != null && !consent_group.isEmpty()) ? studyIdentifier + "." + consent_group : studyIdentifier;

        return getOrCreateAccessRule(
                ar_name,
                description,
                ruleText,
                AccessRule.TypeNaming.ALL_CONTAINS,
                arValue,
                false,
                false,
                false,
                false
        );
    }

    /**
     * Creates and returns a Topmed access rule AccessRule.
     * Generates Main Rule only; gates & sub-rules attached by calling method
     *
     * @param project_name  The name of the project.
     * @param consent_group The consent group.
     * @param label         The label for the rule.
     * @return The created AccessRule.
     */
    private AccessRule upsertTopmedAccessRule(String project_name, String consent_group, String label) {
        String ar_name = (consent_group != null && !consent_group.isEmpty()) ? "AR_TOPMED_" + project_name + "_" + consent_group + "_" + label : "AR_TOPMED_" + project_name + "_" + label;
        String description = "FENCE AR for " + project_name + "." + consent_group + " Topmed data";
        String ruleText = "$.query.query.categoryFilters." + fence_topmed_consent_group_concept_path + "[*]";
        String arValue = (consent_group != null && !consent_group.isEmpty()) ? project_name + "." + consent_group : project_name;

        return getOrCreateAccessRule(
                ar_name,
                description,
                ruleText,
                AccessRule.TypeNaming.ALL_CONTAINS,
                arValue,
                false,
                false,
                false,
                false
        );
    }

    /**
     * Creates and returns a harmonized access rule AccessRule for Topmed.
     * Generates Main Rule only; gates & sub rules attached by calling method
     *
     * @param project_name  The name of the project.
     * @param consent_group The consent group.
     * @param label         The label for the rule.
     * @return The created AccessRule.
     */
    private AccessRule upsertHarmonizedAccessRule(String project_name, String consent_group, String label) {
        String ar_name = "AR_TOPMED_" + project_name + "_" + consent_group + "_" + label;
        logger.info("upsertHarmonizedAccessRule() Creating new access rule {}", ar_name);
        String description = "FENCE AR for " + project_name + "." + consent_group + " Topmed data";
        String ruleText = "$.query.query.categoryFilters." + fence_harmonized_consent_group_concept_path + "[*]";
        String arValue = project_name + "." + consent_group;

        return getOrCreateAccessRule(
                ar_name,
                description,
                ruleText,
                AccessRule.TypeNaming.ALL_CONTAINS,
                arValue,
                false,
                false,
                false,
                false
        );
    }

    /**
     * Creates and returns a consent gate AccessRule.
     * Insert a new gate (if it doesn't exist yet) to identify if consent values are present in the query.
     * return an existing gate named GATE_{gateName}_(PRESENT|MISSING) if it exists.
     *
     * @param gateName    The name of the gate.
     * @param rule        The rule expression.
     * @param is_present  Whether the gate is for present or missing consent.
     * @param description The description of the gate.
     * @return The created AccessRule.
     */
    private AccessRule upsertConsentGate(String gateName, String rule, boolean is_present, String description) {
        gateName = "GATE_" + gateName + "_" + (is_present ? "PRESENT" : "MISSING");
        return getOrCreateAccessRule(
                gateName,
                "FENCE GATE for " + description + " consent " + (is_present ? "present" : "missing"),
                rule,
                is_present ? AccessRule.TypeNaming.IS_NOT_EMPTY : AccessRule.TypeNaming.IS_EMPTY,
                null,
                false,
                false,
                false,
                false
        );
    }

    private AccessRule createPhenotypeSubRule(String conceptPath, String alias, String rule, int ruleType, String label, boolean useMapKey) {
        String ar_name = "AR_PHENO_" + alias + "_" + label;
        logger.info("createPhenotypeSubRule() Creating new access rule {}", ar_name);
        return getOrCreateAccessRule(
                ar_name,
                "FENCE SUB AR for " + alias + " " + label + " clinical concepts",
                rule,
                ruleType,
                ruleType == AccessRule.TypeNaming.IS_NOT_EMPTY ? null : conceptPath,
                useMapKey,
                useMapKey,
                false,
                false
        );
    }

    private AccessRule getOrCreateAccessRule(String name, String description, String rule, int type, String value, boolean checkMapKeyOnly, boolean checkMapNode, boolean evaluateOnlyByGates, boolean gateAnyRelation) {
        return accessRuleCache.computeIfAbsent(name, key -> {
            AccessRule ar = accessRuleService.getAccessRuleByName(key);
            if (ar == null) {
                logger.info("Creating new access rule {}", key);
                ar = new AccessRule();
                ar.setName(name);
                ar.setDescription(description);
                ar.setRule(rule);
                ar.setType(type);
                ar.setValue(value);
                ar.setCheckMapKeyOnly(checkMapKeyOnly);
                ar.setCheckMapNode(checkMapNode);
                ar.setEvaluateOnlyByGates(evaluateOnlyByGates);
                ar.setGateAnyRelation(gateAnyRelation);
                accessRuleService.save(ar);
            }

            return ar;
        });
    }

    private String extractProject(String roleName) {
        String projectPattern = "FENCE_(.*?)(?:_c\\d+)?$";
        if (roleName.startsWith("MANUAL_")) {
            projectPattern = "MANUAL_(.*?)(?:_c\\d+)?$";
        }
        Pattern projectRegex = Pattern.compile(projectPattern);
        Matcher projectMatcher = projectRegex.matcher(roleName);
        String project = "";
        if (projectMatcher.find()) {
            project = projectMatcher.group(1).trim();
        } else {
            logger.info("extractProject() Could not extract project from role name: {}", roleName);
            String[] parts = roleName.split("_", 1);
            if (parts.length > 0) {
                project = parts[1];
            }
        }
        return project;
    }

    private static String extractConsentGroup(String roleName) {
        String consentPattern = "FENCE_.*?_c(\\d+)$";
        if (roleName.startsWith("MANUAL_")) {
            consentPattern = "MANUAL_.*?_c(\\d+)$";
        }
        Pattern consentRegex = Pattern.compile(consentPattern);
        Matcher consentMatcher = consentRegex.matcher(roleName);
        String consentGroup = "";
        if (consentMatcher.find()) {
            consentGroup = "c" + consentMatcher.group(1).trim();
        }
        return consentGroup;
    }

    private StudyMetaData getFENCEMappingforProjectAndConsent(String projectId, String consent_group) {
        String consentVal = (consent_group != null && !consent_group.isEmpty()) ? projectId + "." + consent_group : projectId;
        logger.info("getFENCEMappingforProjectAndConsent() looking up {}", consentVal);

        return this.fenceMappingUtility.getFENCEMapping().get(consentVal);
    }

}
