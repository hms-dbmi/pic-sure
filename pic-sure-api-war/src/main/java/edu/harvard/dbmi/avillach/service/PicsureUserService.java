package edu.harvard.dbmi.avillach.service;

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
public class PicsureUserService {

    Logger logger = LoggerFactory.getLogger(PicsureUserService.class);

    @Inject
    UserRepository userRepo;

    @GET
    @RolesAllowed(PicsureWarNaming.RoleNaming.ROLE_SYSTEM)
    @Path("/{userId}")
    public Response getUserById(
            @PathParam("userId") String userId) {
        logger.info("Looking for user by ID: " + userId + "...");

        User user = userRepo.getById(UUID.fromString(userId));
        if (user == null)
            return PICSUREResponse.protocolError("User is not found by given user ID: " + userId);
        else
            return PICSUREResponse.success(user);

    }

    @GET
    @RolesAllowed(PicsureWarNaming.RoleNaming.ROLE_SYSTEM)
    @Path("")
    public Response getUserAll() {
        logger.info("Getting all users...");
        List<User> users = null;

        users = userRepo.list();

        if (users == null)
            return PICSUREResponse.applicationError("Error occurs when listing all users.");

        return PICSUREResponse.success(users);
    }

    @POST
    @RolesAllowed(PicsureWarNaming.RoleNaming.ROLE_SYSTEM)
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/")
    public Response addUser(List<User> users){
        if (users == null || users.isEmpty())
            return PICSUREResponse.protocolError("No user to be added.");

        List<User> addedUsers = addOrUpdate(users, true);

        if (addedUsers.size() < users.size())
            return PICSUREResponse.applicationError(Integer.toString(users.size()-addedUsers.size())
                    + " users are NOT operated." +
                    " Added users are as follow: ", addedUsers);

        return PICSUREResponse.success("All users are added.", addedUsers);
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
        if (users == null || users.isEmpty())
            return PICSUREResponse.protocolError("No user to be updated.");

        List<User> addedUsers = addOrUpdate(users, false);

        if (addedUsers.size() < users.size())
            return PICSUREResponse.applicationError(Integer.toString(users.size()-addedUsers.size())
                    + " users are NOT operated." +
                    " Updated users are as follow: ", addedUsers);

        return PICSUREResponse.success("All users are updated.", addedUsers);
    }

    /**
     *
     * @param users
     * @param forAdd true for adding, false for merging
     * @return
     */
    private List<User> addOrUpdate(@NotNull List<User> users, boolean forAdd){
        List<User> operatedUsers = new ArrayList<>();
        for (User user : users){
            boolean dbContacted = false;
            if (forAdd) {
                userRepo.persist(user);
                dbContacted = true;
            }
            else if (userRepo.getById(user.getUuid()) !=null) {
                userRepo.merge(user);
                dbContacted = true;
            }

            if (!dbContacted || userRepo.getById(user.getUuid()) == null){
                continue;
            }
            operatedUsers.add(user);
        }
        return operatedUsers;
    }



    @Transactional
    @DELETE
    @RolesAllowed(PicsureWarNaming.RoleNaming.ROLE_SYSTEM)
    @Path("/{userId}")
    public Response removeById(@PathParam("userId") final String userId) {
        UUID uuid = UUID.fromString(userId);
        User user = userRepo.getById(uuid);
        if (user == null)
            return PICSUREResponse.protocolError("User is not found by user ID");

        userRepo.remove(user);

        user = userRepo.getById(uuid);
        if (user != null){
            return PICSUREResponse.applicationError("Cannot delete the user by id: " + userId);
        }

        return PICSUREResponse.success("Successfully deleted user by id: " + userId+", listing rest of the users as below"
                , userRepo.list());

    }

}
