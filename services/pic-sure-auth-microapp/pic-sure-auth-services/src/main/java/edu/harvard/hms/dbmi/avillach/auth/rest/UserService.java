package edu.harvard.hms.dbmi.avillach.auth.rest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.util.exception.ApplicationException;
import edu.harvard.dbmi.avillach.util.exception.ProtocolException;
import edu.harvard.dbmi.avillach.util.response.PICSUREResponse;
import edu.harvard.hms.dbmi.avillach.auth.JAXRSConfiguration;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.Application;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.*;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.*;
import edu.harvard.hms.dbmi.avillach.auth.service.BaseEntityService;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuthUtils;
import edu.harvard.hms.dbmi.avillach.auth.utils.JWTUtil;
import edu.harvard.hms.dbmi.avillach.auth.utils.JsonUtils;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.transaction.Transactional;
import javax.validation.constraints.NotNull;
import javax.ws.rs.*;
import javax.ws.rs.core.*;
import java.io.IOException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;

import static edu.harvard.hms.dbmi.avillach.auth.JAXRSConfiguration.*;
import static edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming.AuthRoleNaming.ADMIN;
import static edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming.AuthRoleNaming.SUPER_ADMIN;

/**
 * <p>Endpoint for service handling business logic for users.</p>
 */
@Api
@Path("/user")
public class UserService extends BaseEntityService<User> {

    Logger logger = LoggerFactory.getLogger(UserService.class);

    @Context
    SecurityContext securityContext;

    @Inject
    UserRepository userRepo;

    @Inject
    RoleRepository roleRepo;

    @Inject
    PrivilegeRepository privilegeRepo;

    @Inject
    ConnectionRepository connectionRepo;

    @Inject
    ApplicationRepository applicationRepo;

    @Inject
    AccessRuleRepository accessruleRepo;

    public UserService() {
        super(User.class);
    }

    @ApiOperation(value = "GET information of one user with the UUID, requires ADMIN or SUPER_ADMIN roles")
    @GET
    @RolesAllowed({ADMIN, SUPER_ADMIN})
    @Path("/{userId}")
    public Response getUserById(
            @ApiParam(required = true, value="The UUID of the user to fetch information about")
            @PathParam("userId") String userId) {
        return getEntityById(userId,userRepo);
    }

    @ApiOperation(value = "GET a list of existing users, requires ADMIN or SUPER_ADMIN roles")
    @GET
    @RolesAllowed({ADMIN, SUPER_ADMIN})
    @Path("")
    public Response getUserAll() {
        return getEntityAll(userRepo);
    }

    @ApiOperation(value = "POST a list of users, requires ADMIN role")
    @Transactional
    @POST
    @RolesAllowed({ADMIN})
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/")
    public Response addUser(
            @ApiParam(required = true, value = "A list of user in JSON format")
            List<User> users){
        User currentUser = (User)securityContext.getUserPrincipal();
        if (currentUser == null || currentUser.getUuid() == null){
            logger.error("Security context didn't have a user stored.");
            return PICSUREResponse.applicationError("Inner application error, please contact admin.");
        }

        checkAssociation(users);

        boolean allowAdd = true;
        for(User user : users) {
            if (!allowUpdateSuperAdminRole(currentUser, user, null)){
                allowAdd = false;
                break;
            }

            if(user.getEmail() == null) {
	        		HashMap<String, String> metadata;
				try {
					metadata = new HashMap<String, String>(new ObjectMapper().readValue(user.getGeneralMetadata(), Map.class));
					List<String> emailKeys = metadata.keySet().stream().filter((key)->{return key.toLowerCase().contains("email");}).collect(Collectors.toList());
		        		if(emailKeys.size()>0) {
		        			user.setEmail(metadata.get(emailKeys.get(0)));
		        		}
				} catch (IOException e) {
					e.printStackTrace();
				}
	        }
        }

	    if (allowAdd){
            return addEntity(users, userRepo);
        } else {
            logger.error("updateUser() user - " + currentUser.getUuid() + " - with roles ["+ currentUser.getRoleString() + "] - is not allowed to grant "
                    + AuthNaming.AuthRoleNaming.SUPER_ADMIN + " role when adding a user.");
            throw new ProtocolException(Response.Status.BAD_REQUEST, "Not allowed to add a user with a " + AuthNaming.AuthRoleNaming.SUPER_ADMIN + " privilege associated.");
        }
    }

    @ApiOperation(value = "Update a list of users, will only update the fields listed, requires ADMIN role")
    @Transactional
    @PUT
    @RolesAllowed({ADMIN})
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/")
    public Response updateUser(List<User> users){
        User currentUser = (User)securityContext.getUserPrincipal();
        if (currentUser == null || currentUser.getUuid() == null){
            logger.error("Security context didn't have a user stored.");
            return PICSUREResponse.applicationError("Inner application error, please contact admin.");
        }

        checkAssociation(users);

        boolean allowUpdate = true;
        for (User user : users) {

            User originalUser = userRepo.getById(user.getUuid());
            if (allowUpdateSuperAdminRole(currentUser, user, originalUser)){
                continue;
            } else {
                allowUpdate = false;
                break;
            }
        }

        if (allowUpdate){
            return updateEntity(users, userRepo);
        }
        else {
            logger.error("updateUser() user - " + currentUser.getUuid() + " - with roles ["+ currentUser.getRoleString() + "] - is not allowed to grant or remove "
                    + AuthNaming.AuthRoleNaming.SUPER_ADMIN + " privilege.");
            throw new ProtocolException(Response.Status.BAD_REQUEST, "Not allowed to update a user with changes associated to " + AuthNaming.AuthRoleNaming.SUPER_ADMIN + " privilege.");
        }
    }

    /**
     * This check is to prevent non-super-admin user to create/remove a super admin role
     * against a user(include themselves). Only super admin user could perform such actions.
     *
     * <p>
     *     if operations not related to super admin role updates, this will return true.
     * </p>
     *
     * The logic here is checking the state of the super admin role in the input and output users,
     * if the state is changed, check if the user is a super admin to determine if the user could perform the action.
     *
     * @param currentUser the user trying to perform the action
     * @param inputUser
     * @param originalUser there could be no original user when adding a new user
     * @return
     */
    private boolean allowUpdateSuperAdminRole(
            @NotNull User currentUser,
            @NotNull User inputUser,
            User originalUser){

        // if current user is a super admin, this check will return true
        for (Role role : currentUser.getRoles()) {
            for (Privilege privilege : role.getPrivileges()){
                if (privilege.getName().equals(AuthNaming.AuthRoleNaming.SUPER_ADMIN)) {
                    return true;
                }
            }
        }

        boolean inputUserHasSuperAdmin = false;
        boolean originalUserHasSuperAdmin = false;

        for (Role role : inputUser.getRoles()){
            for (Privilege privilege : role.getPrivileges()){
                if (privilege.getName().equals(AuthNaming.AuthRoleNaming.SUPER_ADMIN)){
                    inputUserHasSuperAdmin = true;
                    break;
                }
            }
        }

        if (originalUser != null){
            for (Role role : originalUser.getRoles()){
                for (Privilege privilege : role.getPrivileges()){
                    if (privilege.getName().equals(AuthNaming.AuthRoleNaming.SUPER_ADMIN)){
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

    /**
     * For the long term token, current logic is,
     * every time a user hit this endpoint <code>/me</code> with the query parameter ?hasToken presented,
     * it will refresh the long term token.
     *
     * @param httpHeaders
     * @param hasToken
     * @return
     */
    @ApiOperation(value = "Retrieve information of current user")
    @Transactional
    @GET
    @Path("/me")
    public Response getCurrentUser(
            @Context HttpHeaders httpHeaders,
            @ApiParam(required = false, value = "Attribute that represents if a long term token will attach to the response")
            @QueryParam("hasToken") Boolean hasToken){
        User user = (User) securityContext.getUserPrincipal();
        if (user == null || user.getUuid() == null){
            logger.error("Security context didn't have a user stored.");
            return PICSUREResponse.applicationError("Inner application error, please contact admin.");
        }

        user = userRepo.getById(user.getUuid());
        if (user == null){
            logger.error("When retrieving current user, it returned null");
            return PICSUREResponse.applicationError("Inner application error, please contact admin.");
        }

        User.UserForDisaply userForDisplay = new User.UserForDisaply()
                .setEmail(user.getEmail())
                .setPrivileges(user.getPrivilegeNameSet())
                .setUuid(user.getUuid().toString());

        // currently, the queryScopes are simple combination of queryScope string together as a set.
        // We are expecting the queryScope string as plain string. If it is a JSON, we could change the
        // code to use JsonUtils.mergeTemplateMap(Map, Map)
        Set<Privilege> privileges = user.getTotalPrivilege();
        if (privileges != null && !privileges.isEmpty()){
            Set<String> scopes = new TreeSet<>();
            privileges.stream().forEach(privilege -> {
                try {
                    Arrays.stream(objectMapper.readValue(privilege.getQueryScope(), String[].class))
                            .forEach(scopeList-> scopes.addAll(Arrays.asList(scopeList)));
                } catch (JsonProcessingException e) {
                    logger.error("Parsing issue for privilege " + privilege.getUuid() + " queryScope", e);
                }
            });
            userForDisplay.setQueryScopes(scopes);
        }

        if (hasToken!=null){

            if (user.getToken() != null && !user.getToken().isEmpty()){
                userForDisplay.setToken(user.getToken());
            } else {
                user.setToken(generateUserLongTermToken(httpHeaders));
                userRepo.merge(user);
                userForDisplay.setToken(user.getToken());
            }
        }

        return PICSUREResponse.success(userForDisplay);
    }

    @ApiOperation(value = "Retrieve the queryTemplate of certain application by given application Id for the currentUser ")
    @Transactional
    @GET
    @Path("/me/queryTemplate/{applicationId}")
    public Response getQueryTemplate(
            @ApiParam(required = false, value = "Application Id for the returning queryTemplate")
            @PathParam("applicationId") String applicationId){

        if (applicationId == null || applicationId.trim().isEmpty()){
            logger.error("getQueryTemplate() input application UUID is null or empty.");
            throw new ProtocolException("Input application UUID is incorrect.");
        }

        User user = (User) securityContext.getUserPrincipal();
        if (user == null || user.getUuid() == null){
            logger.error("Security context didn't have a user stored.");
            return PICSUREResponse.applicationError("Inner application error, please contact admin.");
        }

        user = userRepo.getById(user.getUuid());
        if (user == null){
            logger.error("When retrieving current user, it returned null");
            return PICSUREResponse.applicationError("Inner application error, please contact admin.");
        }

        Application application = applicationRepo.getById(UUID.fromString(applicationId));

        if (application == null){
            logger.error("getQueryTemplate() cannot find corresponding application by UUID: " + applicationId);
            throw new ProtocolException("Cannot find application by input UUID: " + applicationId);
        }

        return PICSUREResponse.success(
                Map.of("queryTemplate", mergeTemplate(user, application)));

    }


    private String mergeTemplate(User user, Application application) {
        String resultJSON = null;
        Map mergedTemplateMap = null;
        for (Privilege privilege : user.getPrivilegesByApplication(application)){
            String template = privilege.getQueryTemplate();
            logger.debug("mergeTemplate() processing template:"+template);
            if (template == null || template.trim().isEmpty()){
                continue;
            }
            Map<String, Object> templateMap = null;
            try {
                templateMap = objectMapper.readValue(template, Map.class);
            } catch (IOException ex){
                logger.error("mergeTemplate() cannot convert stored queryTemplate using Jackson, the queryTemplate is: " + template);
                throw new ApplicationException("Inner application error, please contact admin.");
            }

            if (templateMap == null) {
                continue;
            }

            if (mergedTemplateMap == null){
                mergedTemplateMap = templateMap;
                continue;
            }

            mergedTemplateMap = JsonUtils.mergeTemplateMap(mergedTemplateMap, templateMap);
        }

        try {
            resultJSON = objectMapper.writeValueAsString(mergedTemplateMap);
        } catch (JsonProcessingException ex) {
            logger.error("mergeTemplate() cannot convert map to json string. The map mergedTemplate is: " + mergedTemplateMap);
            throw new ApplicationException("Inner application error, please contact admin.");
        }

        return resultJSON;

    }

    /**
     * For the long term token, current logic is,
     * every time a user hit this endpoint /me
     * with the query parameter ?hasToken presented,
     * it will refresh the long term token.
     *
     * @param httpHeaders
     * @param hasToken
     * @return
     */
    @ApiOperation(value = "refresh the long term tokne of current user")
    @Transactional
    @GET
    @Path("/me/refresh_long_term_token")
    public Response refreshUserToken(
            @Context HttpHeaders httpHeaders,
            @ApiParam(required = false, value = "A flag represents if the long term token will be returned or not")
            @QueryParam("hasToken") Boolean hasToken){
        User user = (User) securityContext.getUserPrincipal();
        if (user == null || user.getUuid() == null){
            logger.error("Security context didn't have a user stored.");
            return PICSUREResponse.applicationError("Inner application error, please contact admin.");
        }

        user = userRepo.getById(user.getUuid());
        if (user == null){
            logger.error("When retrieving current user, it returned null");
            return PICSUREResponse.applicationError("Inner application error, please contact admin.");
        }

        String longTermToken = generateUserLongTermToken(httpHeaders);
        user.setToken(longTermToken);

        userRepo.merge(user);

        return PICSUREResponse.success(Map.of("userLongTermToken", longTermToken));
    }

    /**
     * Logic here is, retrieve the subject of the user from httpHeader. Then generate a long term one
     * with LONG_TERM_TOKEN_PREFIX| in front of the subject to be able to distinguish with regular ones, since
     * long term token only generated for accessing certain things to, in some degrees, decrease the insecurity.
     * @param httpHeaders
     * @return
     * @throws ProtocolException
     */
    private String generateUserLongTermToken(HttpHeaders httpHeaders) throws ProtocolException{
        Jws<Claims> jws;
        try {
            jws = AuthUtils.parseToken(clientSecret,
                    // the original token should be able to grab from header, otherwise, it should be stopped
                    // at JWTFilter level
                    httpHeaders.getHeaderString(HttpHeaders.AUTHORIZATION)
                            .substring(6)
                            .trim());
        } catch (NotAuthorizedException ex) {
            throw new ProtocolException("Cannot parse token in header");
        }

        Claims claims = jws.getBody();
        String tokenSubject = claims.getSubject();

        if (tokenSubject.startsWith(AuthNaming.LONG_TERM_TOKEN_PREFIX+"|")) {
            // considering the subject already contains a "|"
            // to prevent infinitely adding the long term token prefix
            // we will grab the real subject here
            tokenSubject = tokenSubject.substring(AuthNaming.LONG_TERM_TOKEN_PREFIX.length()+1);
        }

        return JWTUtil.createJwtToken(clientSecret,
                claims.getId(),
                claims.getIssuer(),
                claims,
                AuthNaming.LONG_TERM_TOKEN_PREFIX + "|" + tokenSubject,
                longTermTokenExpirationTime);
    }


    /**
     * check all referenced field if they are already in database. If
     * they are in database, then retrieve it by id, and attach it to
     * user object.
     *
     * @param users
     * @return
     */
    private void checkAssociation(List<User> users) throws ProtocolException{

        for (User user: users){
            if (user.getRoles() != null){
                Set<Role> roles = new HashSet<>();
                user.getRoles().stream().forEach(t -> roleRepo.addObjectToSet(roles, roleRepo, t));
                user.setRoles(roles);
            }

            if (user.getConnection() != null){
                Connection connection = connectionRepo.getUniqueResultByColumn("id", user.getConnection().getId());
                user.setConnection(connection);
            }
        }
    }

    /**
     * Create or update a user record, based on the FENCE user profile, which is in JSON format.
     *
     * @param node User profile, as it is received from Gen3 FENCE, in JSON format
     * @return User The actual entity, as it is persisted (if no errors) in the PSAMA database
     */
    public User createUserFromFENCEProfile(JsonNode node) {
        logger.debug("createUserFromFENCEProfile() starting...");

        User new_user = new User();
        new_user.setSubject("fence|"+node.get("user_id").asText());
        new_user.setEmail(node.get("email").asText());
        new_user.setGeneralMetadata(node.toString());
        // This is a hack, but someone has to do it.
        new_user.setAcceptedTOS(new Date());
        new_user.setConnection(connectionRepo.getUniqueResultByColumn("label", "FENCE"));
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
    public boolean upsertRole(User u,  String roleName, String roleDescription) {
        boolean status = false;
        logger.debug("upsertRole() starting for user subject:"+u.getSubject());

        // Get the User's list of Roles. The first time, this will be an empty Set.
        // This method is called for every Role, and the User's list of Roles will
        // be updated for all subsequent calls.
        Set<Role> users_roles = new HashSet<Role>(); //u.getRoles();
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
            users_roles.add(r);
            logger.info("upsertRole() added to new set of roles. Now there are "+users_roles.size()+" roles.");
        } catch (Exception ex) {
            logger.error("upsertRole() Could not inser/update role "+roleName+" to repo, because "+ex.getMessage());
        }

        try {
            userRepo.changeRole(u, users_roles);
            logger.debug("upsertRole() updated user, who now has "+users_roles.size()+" roles.");
            status = true;
        } catch (Exception ex) {
            logger.error("upsertRole() Could not add roles to user, because "+ex.getMessage());
        }

        logger.debug("upsertRole() finished");
        return status;
    }

    private Set<Privilege> upsertPrivilege(User u, Role r) {
        String roleName = r.getName();
        logger.info("upsertPrivilege() starting, adding privilege to role "+roleName);

        Map<String, String> fenceMapping = null;
        try {
            fenceMapping = JsonUtils.getFENCEMapping();
        } catch (Exception e) {
            e.printStackTrace();
            logger.warn("upsertPrivilege() Could not process the JSON mapping project=>concept_path");
        }
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

            Application app = applicationRepo.getUniqueResultByColumn("name", "PICSURE");
            // Add new privilege PRIV_FENCE_phs######_c# and PRIV_FENCE_phs######_c#_HARMONIZED
            privs.add(createNewPrivilege(app, project_name, consent_group, concept_path, false));
            privs.add(createNewPrivilege(app, project_name, consent_group, fence_harmonized_concept_path, true));
        }
        logger.info("upsertPrivilege() Finished");
        return privs;
    }

    private Privilege createNewPrivilege(Application app, String project_name, String consent_group, String queryScopeConceptPath, boolean isHarmonized) {
        Privilege priv = new Privilege();

        // Build Privilege Object
        try {
            priv.setApplication(app);
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
                logger.info("upsertAccessRule() Assign gate "+accessruleName+" to access_rule "+ar.getName());
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

}
