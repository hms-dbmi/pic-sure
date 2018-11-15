package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.dbmi.avillach.util.PicsureNaming;
import edu.harvard.dbmi.avillach.util.exception.ProtocolException;
import edu.harvard.dbmi.avillach.util.response.PICSUREResponse;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.Role;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.RoleRepository;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.UserRepository;
import edu.harvard.hms.dbmi.avillach.auth.service.BaseEntityService;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.transaction.Transactional;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Service handling business logic for CRUD on users
 */
@Path("/user")
public class UserService extends BaseEntityService<User> {

    Logger logger = LoggerFactory.getLogger(UserService.class);

    @Inject
    UserRepository userRepo;

    @Inject
    RoleRepository roleRepo;

    public UserService() {
        super(User.class);
    }

    @GET
    @RolesAllowed(AuthNaming.AuthRoleNaming.ROLE_SYSTEM)
    @Path("/{userId}")
    public Response getUserById(
            @PathParam("userId") String userId) {
        return getEntityById(userId,userRepo);
    }

    @GET
    @RolesAllowed(AuthNaming.AuthRoleNaming.ROLE_SYSTEM)
    @Path("")
    public Response getUserAll() {
        return getEntityAll(userRepo);
    }

    @Transactional
    @POST
    @RolesAllowed(AuthNaming.AuthRoleNaming.ROLE_SYSTEM)
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/")
    public Response addUser(List<User> users){
        checkRoleAssociation(users);
        return addEntity(users, userRepo);
    }

    @POST
    @RolesAllowed(AuthNaming.AuthRoleNaming.ROLE_SYSTEM)
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
    @RolesAllowed(AuthNaming.AuthRoleNaming.ROLE_SYSTEM)
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/")
    public Response updateUser(List<User> users){
        checkRoleAssociation(users);
        return updateEntity(users, userRepo);
    }

    @Transactional
    @DELETE
    @RolesAllowed(AuthNaming.AuthRoleNaming.ROLE_SYSTEM)
    @Path("/{userId}")
    public Response removeById(@PathParam("userId") final String userId) {
        return removeEntityById(userId, userRepo);
    }

    /**
     * check if the roles under user is in the database or not,
     * then retrieve it from database and attach it to user object
     *
     * @param users
     * @return
     */
    private void checkRoleAssociation(List<User> users) throws ProtocolException{

        for (User user: users){
            if (user.getRoles() != null){
                Set<Role> roles = new HashSet<>();
                user.getRoles().stream().forEach(t -> roleRepo.addObjectToSet(roles, roleRepo, t));
                user.setRoles(roles);
            }
        }
    }

}
