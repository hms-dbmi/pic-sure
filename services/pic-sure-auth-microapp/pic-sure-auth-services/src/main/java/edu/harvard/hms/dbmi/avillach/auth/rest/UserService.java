package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.dbmi.avillach.util.exception.ProtocolException;
import edu.harvard.dbmi.avillach.util.response.PICSUREResponse;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.Connection;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.Role;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.ConnectionRepository;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.RoleRepository;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.UserRepository;
import edu.harvard.hms.dbmi.avillach.auth.service.BaseEntityService;

import org.hibernate.mapping.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.transaction.Transactional;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming.AuthRoleNaming.*;

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

    public UserService() {
        super(User.class);
    }

    @GET
    @RolesAllowed({SYSTEM, ADMIN, SUPER_ADMIN})
    @Path("/{userId}")
    public Response getUserById(
            @PathParam("userId") String userId) {
        return getEntityById(userId,userRepo);
    }

    @GET
    @RolesAllowed({SYSTEM, ADMIN, SUPER_ADMIN})
    @Path("")
    public Response getUserAll() {
        return getEntityAll(userRepo);
    }

    @Transactional
    @POST
    @RolesAllowed({SYSTEM, ADMIN, SUPER_ADMIN})
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/")
    public Response addUser(List<User> users){
        checkAssociation(users);
        for(User user : users) {
            if(user.getEmail() == null) {
	        		HashMap<String, String> metadata;
				try {
					metadata = new HashMap<String, String>((java.util.Map<? extends String, ? extends String>) new ObjectMapper().readValue(user.getGeneralMetadata(), Map.class));
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
    @RolesAllowed({SYSTEM, ADMIN, SUPER_ADMIN})
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
    @RolesAllowed({SYSTEM, ADMIN, SUPER_ADMIN})
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/")
    public Response updateUser(List<User> users){
        checkAssociation(users);
        return updateEntity(users, userRepo);
    }

    @GET
    @Path("/me")
    public Response getCurrentUser(){
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

        return PICSUREResponse.success(new User.UserForDisaply()
                .setEmail(user.getEmail())
                .setPrivileges(user.getPrivilegeNameSet()));
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
