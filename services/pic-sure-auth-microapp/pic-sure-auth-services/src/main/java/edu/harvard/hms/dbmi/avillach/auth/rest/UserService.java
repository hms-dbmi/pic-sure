package edu.harvard.hms.dbmi.avillach.auth.rest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.util.exception.ApplicationException;
import edu.harvard.dbmi.avillach.util.exception.ProtocolException;
import edu.harvard.dbmi.avillach.util.response.PICSUREResponse;
import edu.harvard.hms.dbmi.avillach.auth.JAXRSConfiguration;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.Application;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.*;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.ApplicationRepository;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.ConnectionRepository;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.RoleRepository;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.UserRepository;
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
import java.util.*;
import java.util.stream.Collectors;

import static edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming.AuthRoleNaming.ADMIN;
import static edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming.AuthRoleNaming.SUPER_ADMIN;

/**
 * Service handling business logic for CRUD on users
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
    ConnectionRepository connectionRepo;

    @Inject
    ApplicationRepository applicationRepo;

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
            userForDisplay.setQueryScopes(privileges.stream()
                    .map(privilege -> privilege.getQueryScope())
                    .filter(q -> q!=null && !q.isEmpty())
                    .collect(Collectors.toSet()));
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

            if (template == null || template.trim().isEmpty()){
                continue;
            }

            Map<String, Object> templateMap = null;

            try {
                templateMap = JAXRSConfiguration.objectMapper.readValue(template, Map.class);
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
            resultJSON = JAXRSConfiguration.objectMapper.writeValueAsString(mergedTemplateMap);
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
            jws = AuthUtils.parseToken(JAXRSConfiguration.clientSecret,
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

        return JWTUtil.createJwtToken(JAXRSConfiguration.clientSecret,
                claims.getId(),
                claims.getIssuer(),
                claims,
                AuthNaming.LONG_TERM_TOKEN_PREFIX + "|" + tokenSubject,
                JAXRSConfiguration.longTermTokenExpirationTime);
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

}
