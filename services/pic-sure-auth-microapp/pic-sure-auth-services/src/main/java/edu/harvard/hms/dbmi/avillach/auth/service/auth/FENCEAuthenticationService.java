package edu.harvard.hms.dbmi.avillach.auth.service.auth;

import com.fasterxml.jackson.databind.JsonNode;

import edu.harvard.dbmi.avillach.util.HttpClientUtil;
import edu.harvard.dbmi.avillach.util.response.PICSUREResponse;
import edu.harvard.hms.dbmi.avillach.auth.JAXRSConfiguration;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.AccessRule;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.Application;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.Connection;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.Role;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.AccessRuleRepository;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.ApplicationRepository;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.ConnectionRepository;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.PrivilegeRepository;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.RoleRepository;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.UserRepository;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuthUtils;

import org.apache.http.Header;
import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.ws.rs.NotAuthorizedException;
import javax.ws.rs.core.Response;

import static edu.harvard.hms.dbmi.avillach.auth.JAXRSConfiguration.fence_consent_group_concept_path;
import static edu.harvard.hms.dbmi.avillach.auth.JAXRSConfiguration.fence_harmonized_concept_path;
import static edu.harvard.hms.dbmi.avillach.auth.JAXRSConfiguration.fence_standard_access_rules;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class FENCEAuthenticationService {
    private Logger logger = LoggerFactory.getLogger(FENCEAuthenticationService.class);

    @Inject
    UserRepository userRepo;

    @Inject
    RoleRepository roleRepo;

    @Inject
    ConnectionRepository connectionRepo;

    @Inject
    AccessRuleRepository accessruleRepo;

    @Inject
    UserRepository userRole;

    @Inject
    ApplicationRepository applicationRepo;

    @Inject
    PrivilegeRepository privilegeRepo;

    @Inject
    AuthUtils authUtil;

    private Application picSureApp;
    private Connection fenceConnection;
    private Map<String, String> fenceMapping;

    @PostConstruct
	public void initializeFenceService() {
		 picSureApp = applicationRepo.getUniqueResultByColumn("name", "PICSURE");
		 fenceConnection = connectionRepo.getUniqueResultByColumn("label", "FENCE");
		 fenceMapping = getFENCEMapping();
    }

    private JsonNode getFENCEUserProfile(String access_token) {
        logger.debug("getFENCEUserProfile() starting");
        List<Header> headers = new ArrayList<>();
        headers.add(new BasicHeader("Authorization", "Bearer " + access_token));

        logger.debug("getFENCEUserProfile() getting user profile from uri:"+JAXRSConfiguration.idp_provider_uri+"/user/user");
        JsonNode fence_user_profile_response = HttpClientUtil.simpleGet(
                JAXRSConfiguration.idp_provider_uri+"/user/user",
                JAXRSConfiguration.client,
                JAXRSConfiguration.objectMapper,
                headers.toArray(new Header[headers.size()])
        );

        logger.debug("getFENCEUserProfile() finished, returning user profile"+fence_user_profile_response.asText());
        return fence_user_profile_response;
    }

    private JsonNode getFENCEAccessToken(String fence_code) {
        logger.debug("getFENCEAccessToken() starting, using FENCE code");

        List<Header> headers = new ArrayList<>();
        Base64.Encoder encoder = Base64.getEncoder();
        String fence_auth_header = JAXRSConfiguration.fence_client_id+":"+JAXRSConfiguration.fence_client_secret;
        headers.add(new BasicHeader("Authorization",
                "Basic " + encoder.encodeToString(fence_auth_header.getBytes())));
        headers.add(new BasicHeader("Content-type", "application/x-www-form-urlencoded; charset=UTF-8"));

        // Build the request body, as JSON
        String query_string =
        		"grant_type=authorization_code"
        				+ "&code=" + fence_code
        				+ "&redirect_uri=" + JAXRSConfiguration.fence_redirect_url;

        String fence_token_url = JAXRSConfiguration.idp_provider_uri+"/user/oauth2/token";

        JsonNode resp = null;
        try {
            resp = HttpClientUtil.simplePost(
                    fence_token_url,
                    new StringEntity(query_string),
                    JAXRSConfiguration.client,
                    JAXRSConfiguration.objectMapper,
                    headers.toArray(new Header[headers.size()])
            );
        } catch (Exception ex) {
            logger.error("getFENCEAccessToken() failed to call FENCE token service, "+ex.getMessage());
        }
        logger.debug("getFENCEAccessToken() finished. "+resp.asText());
        return resp;
    }

    // Get access_token from FENCE, based on the provided `code`
    public Response getFENCEProfile(Map<String, String> authRequest){
        logger.debug("getFENCEProfile() starting...");
        String fence_code  = authRequest.get("code");

        JsonNode fence_user_profile = null;
        // Get the Gen3/FENCE user profile. It is a JsonNode object
        try {
            logger.debug("getFENCEProfile() query FENCE for user profile with code");
            fence_user_profile = getFENCEUserProfile(getFENCEAccessToken(fence_code).get("access_token").asText());
            logger.debug("getFENCEProfile() user profile structure:"+fence_user_profile.asText());
            logger.debug("getFENCEProfile() .username:" + fence_user_profile.get("username"));
            logger.debug("getFENCEProfile() .user_id:" + fence_user_profile.get("user_id"));
            logger.debug("getFENCEProfile() .email:" + fence_user_profile.get("email"));
        } catch (Exception ex) {
            logger.error("getFENCEToken() could not retrieve the user profile from the auth provider, because "+ex.getMessage());
            throw new NotAuthorizedException("Could not get the user profile "+
                    "from the Gen3 authentication provider."+ex.getMessage());
        }

        User current_user = null;
        try {
            // Create or retrieve the user profile from our database, based on the the key
            // in the Gen3/FENCE profile
            current_user = createUserFromFENCEProfile(fence_user_profile);
            logger.info("getFENCEProfile() saved details for user with e-mail:"
                    +current_user.getEmail()
                    +" and subject:"
                    +current_user.getSubject());

        } catch (Exception ex) {
            logger.error("getFENCEToken() Could not persist the user information, because "+ex.getMessage());
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

            logger.debug("getFENCEProfile() AccessRole:"+access_role_name);
            String[] parts = access_role_name.split("\\.");

            String newRoleName;
            if (parts.length > 1) {
               newRoleName = "FENCE_"+parts[0]+"_"+parts[parts.length-1];
            } else {
              newRoleName = "FENCE_"+access_role_name;
            }
            logger.info("getFENCEProfile() New PSAMA role name:"+newRoleName);

                if (upsertRole(current_user, newRoleName, "FENCE role "+newRoleName)) {
                    logger.info("getFENCEProfile() Updated user role. Now it includes `"+newRoleName+"`");
                } else {
                    logger.error("getFENCEProfile() could not add roles to user's profile");
                }

                // TODO: In case we need to do something with this part, we can uncomment it.
                //JsonNode role_object = fence_user_profile.get("project_access").get(newRoleName);
                //It is a an array of strings, like this: ["read-storage","read"]
                //logger.debug("getFENCEProfile() object:"+role_object.toString());
        }
        try {
            userRepo.changeRole(current_user, current_user.getRoles());
            logger.debug("upsertRole() updated user, who now has "+current_user.getRoles().size()+" roles.");
        } catch (Exception ex) {
            logger.error("upsertRole() Could not add roles to user, because "+ex.getMessage());
        }
        HashMap<String, Object> claims = new HashMap<String,Object>();
        claims.put("name", fence_user_profile.get("name"));
        claims.put("email", current_user.getEmail());
        claims.put("sub", current_user.getSubject());
        HashMap<String, String> responseMap = authUtil.getUserProfileResponse(claims);
        logger.debug("getFENCEProfile() UserProfile response object has been generated");

        logger.debug("getFENCEToken() finished");
        return PICSUREResponse.success(responseMap);
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
        new_user.setSubject("fence|"+node.get("user_id").asText());
        new_user.setEmail(node.get("email").asText());
        new_user.setGeneralMetadata(node.toString());
        // This is a hack, but someone has to do it.
        new_user.setAcceptedTOS(new Date());
        new_user.setConnection(fenceConnection);
        logger.debug("createUserFromFENCEProfile() finished setting fields");

        User actual_user = userRepo.findOrCreate(new_user);

        // Clear current set of roles every time we create or retrieve a user
        actual_user.setRoles(new HashSet<>());
        logger.debug("createUserFromFENCEProfile() cleared roles");

        userRepo.persist(actual_user);
        logger.debug("createUserFromFENCEProfile() finished, user record inserted");
        return actual_user;
    }

    /**
     * Insert or Update the User object's list of Roles in the database.
     *
     * @param u The User object the generated Role will be added to
     * @param roleName Name of the Role
     * @param roleDescription Description of the Role
     * @return boolean Whether the Role was successfully added to the User or not
     */
    private boolean upsertRole(User u,  String roleName, String roleDescription) {
        boolean status = false;
        logger.debug("upsertRole() starting for user subject:"+u.getSubject());

        // Get the User's list of Roles. The first time, this will be an empty Set.
        // This method is called for every Role, and the User's list of Roles will
        // be updated for all subsequent calls.
        try {
            Role r = null;
            // Create the Role in the repository, if it does not exist. Otherwise, add it.
            Role existing_role = roleRepo.getUniqueResultByColumn("name", roleName);
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
                roleRepo.persist(r);
                logger.info("upsertRole() created new role");
            }
            u.getRoles().add(r);
            status = true;
        } catch (Exception ex) {
            logger.error("upsertRole() Could not inser/update role "+roleName+" to repo, because "+ex.getMessage());
        }


        logger.debug("upsertRole() finished");
        return status;
    }

    private Set<Privilege> upsertPrivilege(User u, Role r) {
        String roleName = r.getName();
        logger.info("upsertPrivilege() starting, adding privilege to role "+roleName);

        String[] parts = roleName.split("_");
        String project_name = parts[1];
        String consent_group = parts[2];
        // TODO: How to alert when the mapping is not in the list.
        String concept_path = fenceMapping.get(project_name);

        // Get privilege and assign it to this role.
        String privilegeName = r.getName().replaceFirst("FENCE_*","PRIV_FENCE_");
        logger.info("upsertPrivilege() Looking for privilege, with name : "+privilegeName);

        Set<Privilege> privs = r.getPrivileges();
        if (privs == null) { privs = new HashSet<Privilege>();}

        Privilege p = privilegeRepo.getUniqueResultByColumn("name", privilegeName);
        if (p != null) {
            logger.info("upsertPrivilege() Assigning privilege "+p.getName()+" to role "+r.getName());
            privs.add(p);

        } else {
            logger.info("upsertPrivilege() This is a new privilege");
            logger.info("upsertPrivilege() project:"+project_name+" consent_group:"+consent_group+" concept_path:"+concept_path);

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
            priv.setName("PRIV_FENCE_"+project_name+"_"+consent_group+(isHarmonized?"_HARMONIZED":""));
            priv.setDescription("FENCE privilege for "+project_name+"/"+consent_group);
            priv.setQueryScope(queryScopeConceptPath);

            String consent_concept_path = fence_consent_group_concept_path;
            // TOOD: Change this to a mustache template
            String queryTemplateText = "{\"categoryFilters\": {\""
                    +consent_concept_path
                    +"\":\""
                    +project_name+"."+consent_group
                    +"\"},"
                    +"\"numericFilters\":{},\"requiredFields\":[],"
                    +"\"variantInfoFilters\":[{\"categoryVariantInfoFilters\":{},\"numericVariantInfoFilters\":{}}],"
                    +"\"expectedResultType\": \"COUNT\""
                    +"}";
            priv.setQueryTemplate(queryTemplateText);
            priv.setQueryScope(queryScopeConceptPath);

            AccessRule ar = upsertAccessRule(project_name, consent_group);
            if (ar != null) {
                Set<AccessRule> accessrules = new HashSet<AccessRule>();
                accessrules.add(ar);
                // Add additionanl access rules
                for(String arName: fence_standard_access_rules.split(",")) {
                    if (arName.startsWith("AR_")) {
                        logger.info("Adding AccessRule "+arName+" to privilege "+priv.getName());
                        accessrules.add(accessruleRepo.getUniqueResultByColumn("name",arName));
                    }
                }
                priv.setAccessRules(accessrules);
                logger.info("createNewPrivilege() Added "+accessrules.size()+" access_rules to privilege");
            }

            privilegeRepo.persist(priv);
            logger.info("createNewPrivilege() Added new privilege "+priv.getName()+" to DB");
        } catch (Exception ex) {
            ex.printStackTrace();
            logger.error("createNewPrivilege() could not save privilege");
        }
        return priv;
    }

    private AccessRule upsertAccessRule(String project_name, String consent_group) {
        logger.debug("upsertAccessRule() starting");
        String ar_name = "AR_"+project_name+"_"+consent_group;
        AccessRule ar = accessruleRepo.getUniqueResultByColumn("name", ar_name);
        if (ar != null) {
            logger.info("upsertAccessRule() AccessRule "+ar_name+" already exists.");
            return ar;
        }

        logger.info("upsertAccessRule() Creating new access rule "+ar_name);
        ar = new AccessRule();
        ar.setName(ar_name);
        ar.setDescription("FENCE AR for "+project_name+"/"+consent_group);
        StringBuilder ruleText = new StringBuilder();
        ruleText.append("$..categoryFilters.['");
        ruleText.append(fence_consent_group_concept_path);
        ruleText.append("']");
        ar.setRule(ruleText.toString());
        ar.setType(AccessRule.TypeNaming.ALL_EQUALS);
        ar.setValue(project_name+"."+consent_group);
        ar.setCheckMapKeyOnly(false);
        ar.setCheckMapNode(true);
        ar.setEvaluateOnlyByGates(false);
        ar.setGateAnyRelation(false);

        // Assign all GATE_ access rules to this AR access rule.
        Set<AccessRule> gates = new HashSet<AccessRule>();
        for (String accessruleName : fence_standard_access_rules.split("\\,")) {
            if (accessruleName.startsWith("GATE_")) {
                logger.info("upsertAccessRule() Assign gate " + accessruleName +
                		" to access_rule "+ar.getName());
                gates.add(accessruleRepo.getUniqueResultByColumn("name",accessruleName));
            } else {
                continue;
            }
        }
        ar.setGates(gates);

        accessruleRepo.persist(ar);

        logger.debug("upsertAccessRule() finished");
        return ar;
    }

	/*
	 * Get the mappings of fence privileges to paths
	 */
	@SuppressWarnings("unchecked")
	private Map<String, String> getFENCEMapping() {
		try {
			return JAXRSConfiguration.objectMapper.readValue(
					new File(String.join(File.separator,
							new String[] {JAXRSConfiguration.templatePath ,"fence_mapping.json"}))
					, Map.class);
		} catch (IOException e) {
			logger.error("fence-mapping.json not found at "+JAXRSConfiguration.templatePath);
		}
		return Map.of();
	}

}
