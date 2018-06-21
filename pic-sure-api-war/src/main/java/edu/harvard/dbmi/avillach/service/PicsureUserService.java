package edu.harvard.dbmi.avillach.service;

import com.fasterxml.jackson.databind.ser.Serializers;
import edu.harvard.dbmi.avillach.data.entity.User;
import edu.harvard.dbmi.avillach.data.repository.UserRepository;
import edu.harvard.dbmi.avillach.util.response.PICSUREResponse;
import edu.harvard.dbmi.avillach.utils.PicsureWarNaming;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.transaction.Transactional;
import javax.validation.constraints.NotNull;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Service handling business logic for CRUD on users
 */
@Path("/user")
public class PicsureUserService extends PicsureBaseEntityService<User> {

    Logger logger = LoggerFactory.getLogger(PicsureUserService.class);

    @Inject
    UserRepository userRepo;

    public PicsureUserService() {
        super(User.class);
    }

    @GET
    @RolesAllowed(PicsureWarNaming.RoleNaming.ROLE_SYSTEM)
    @Path("/{userId}")
    public Response getUserById(
            @PathParam("userId") String userId) {
        return getEntityById(userId,userRepo);
    }

    @GET
    @RolesAllowed(PicsureWarNaming.RoleNaming.ROLE_SYSTEM)
    @Path("")
    public Response getUserAll() {
        return getEntityAll(userRepo);
    }

    @POST
    @RolesAllowed(PicsureWarNaming.RoleNaming.ROLE_SYSTEM)
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/")
    public Response addUser(List<User> users){
        return addEntity(users, userRepo);
    }

    @POST
    @RolesAllowed(PicsureWarNaming.RoleNaming.ROLE_SYSTEM)
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/{uuid}/role/{role}")
    public Response changeRole(
            @PathParam("uuid") String uuid,
            @PathParam("role") String role){
        User user = userRepo.getById(UUID.fromString(uuid));
        if (user == null)
            return PICSUREResponse.protocolError("User is not found by given user ID: " + uuid);

        User updatedUser = userRepo.changeRole(user, role);

        return PICSUREResponse.success("User has new role: " + updatedUser.getRoles(), updatedUser);
    }

    @GET
    @RolesAllowed(PicsureWarNaming.RoleNaming.ROLE_SYSTEM)
    @Path("/availableRoles")
    public Response availableRoles(){
        return PICSUREResponse.success(PicsureWarNaming.RoleNaming.allRoles());
    }

    @PUT
    @RolesAllowed(PicsureWarNaming.RoleNaming.ROLE_SYSTEM)
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/")
    public Response updateUser(List<User> users){
        return updateEntity(users, userRepo);
    }

    @Transactional
    @DELETE
    @RolesAllowed(PicsureWarNaming.RoleNaming.ROLE_SYSTEM)
    @Path("/{userId}")
    public Response removeById(@PathParam("userId") final String userId) {
        return removeEntityById(userId, userRepo);
    }

}
