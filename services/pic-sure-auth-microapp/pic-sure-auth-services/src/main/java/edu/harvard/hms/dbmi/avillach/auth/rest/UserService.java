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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.transaction.Transactional;
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

    @GET
    @RolesAllowed({ADMIN, SUPER_ADMIN})
    @Path("/{userId}")
    public Response getUserById(
            @PathParam("userId") String userId) {
        return getEntityById(userId,userRepo);
    }

    @GET
    @RolesAllowed({ADMIN, SUPER_ADMIN})
    @Path("")
    public Response getUserAll() {
        return getEntityAll(userRepo);
    }

    @Transactional
    @POST
    @RolesAllowed({ADMIN})
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/")
    public Response addUser(List<User> users){
        checkAssociation(users);
        for(User user : users) {
            if(user.getEmail() == null) {
	        		HashMap<String, String> metadata;
				try {
					metadata = new HashMap<String, String>(new ObjectMapper().readValue(user.getGeneralMetadata(), Map.class));
					List<String> emailKeys = metadata.keySet().stream().filter((key)->{return key.toLowerCase().contains("email");}).collect(Collectors.toList());
		        		if(emailKeys.size()>0) {
		        			user.setEmail(metadata.get(emailKeys.get(0)));
		        		}
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
	        }
        }
	
        return addEntity(users, userRepo);
    }

    @POST
    @RolesAllowed({ADMIN})
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/{uuid}/role/{role}")
    public Response changeRole(
            @PathParam("uuid") String uuid,
            @PathParam("role") String role){
        User user = userRepo.getById(UUID.fromString(uuid));
        if (user == null)
            return PICSUREResponse.protocolError("User is not found by given user ID: " + uuid);

//        User updatedUser = userRepo.changeRole(user, role);

        return PICSUREResponse.success("User has new role: "); //+ updatedUser.getRoles(), updatedUser);
    }

    @PUT
    @RolesAllowed({ADMIN})
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/")
    public Response updateUser(List<User> users){
        checkAssociation(users);
        return updateEntity(users, userRepo);
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
    @Transactional
    @GET
    @Path("/me")
    public Response getCurrentUser(
            @Context HttpHeaders httpHeaders,
            @QueryParam("hasToken") Boolean hasToken,
            @QueryParam("hasHPDSQueryTemplate") Boolean hasHPDSQueryTemplate){
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

        User.UserForDisaply userForDisaply = new User.UserForDisaply()
                .setEmail(user.getEmail())
                .setPrivileges(user.getPrivilegeNameSet())
                .setUuid(user.getUuid().toString());

        if (hasToken!=null && hasToken!=false){

            if (user.getToken() != null && !user.getToken().isEmpty()){
                userForDisaply.setToken(user.getToken());
            } else {
                user.setToken(generateUserLongTermToken(httpHeaders));
                userRepo.merge(user);
                userForDisaply.setToken(user.getToken());
            }
        }

        return PICSUREResponse.success(userForDisaply);
    }

    @Transactional
    @GET
    @Path("/me/queryTemplate/{applicationId}")
    public Response getQueryTemplate(
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
            logger.error("mergeTemplate() cannot convert map to json string. The map mergedTemplate is.");
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
    @Transactional
    @GET
    @Path("/me/refresh_long_term_token")
    public Response refreshUserToken(
            @Context HttpHeaders httpHeaders,
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

    private String generateUserLongTermToken(HttpHeaders httpHeaders) throws ProtocolException{
        // grant the long term token
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
