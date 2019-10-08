package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.dbmi.avillach.util.response.PICSUREResponse;
import edu.harvard.hms.dbmi.avillach.auth.JAXRSConfiguration;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.Role;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.PrivilegeRepository;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.RoleRepository;
import edu.harvard.hms.dbmi.avillach.auth.service.BaseEntityService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
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

import static edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming.AuthRoleNaming.ADMIN;
import static edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming.AuthRoleNaming.SUPER_ADMIN;

@Api
@Path("/role")
public class RoleService extends BaseEntityService<Role> {

    Logger logger = LoggerFactory.getLogger(RoleService.class);

    @Context
    SecurityContext securityContext;

    @Inject
    RoleRepository roleRepo;

    @Inject
    PrivilegeRepository privilegeRepo;

    public RoleService() {
        super(Role.class);
    }

    @ApiOperation(value = "GET information of one Role with the UUID, requires ADMIN or SUPER_ADMIN role")
    @GET
    @Path("/{roleId}")
    @RolesAllowed({ADMIN, SUPER_ADMIN})
    public Response getRoleById(
            @ApiParam(value="The UUID of the Role to fetch information about")
            @PathParam("roleId") String roleId) {
        return getEntityById(roleId,roleRepo);
    }

    @ApiOperation(value = "GET a list of existing Roles, requires ADMIN or SUPER_ADMIN role")
    @GET
    @Path("")
    @RolesAllowed({ADMIN, SUPER_ADMIN})
    public Response getRoleAll() {
        return getEntityAll(roleRepo);
    }

    @ApiOperation(value = "POST a list of Roles, requires SUPER_ADMIN role")
    @Transactional
    @POST
    @RolesAllowed({SUPER_ADMIN})
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/")
    public Response addRole(
            @ApiParam(required = true, value = "A list of Roles in JSON format")
            List<Role> roles){
        checkPrivilegeAssociation(roles);
        return addEntity(roles, roleRepo);
    }

    @ApiOperation(value = "Update a list of Roles, will only update the fields listed, requires SUPER_ADMIN role")
    @Transactional
    @PUT
    @RolesAllowed({SUPER_ADMIN})
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/")
    public Response updateRole(
            @ApiParam(required = true, value = "A list of Roles with fields to be updated in JSON format")
            List<Role> roles){
        checkPrivilegeAssociation(roles);
        return updateEntity(roles, roleRepo);
    }

    @ApiOperation(value = "DELETE an Role by Id only if the Role is not associated by others, requires SUPER_ADMIN role")
    @Transactional
    @DELETE
    @RolesAllowed({SUPER_ADMIN})
    @Path("/{roleId}")
    public Response removeById(
            @ApiParam(required = true, value = "A valid Role Id")
            @PathParam("roleId") final String roleId) {
        Role role = roleRepo.getById(UUID.fromString(roleId));
        if (JAXRSConfiguration.defaultAdminRoleName.equals(role.getName())){
            logger.info("User: " + JAXRSConfiguration.getPrincipalName(securityContext)
                    + ", is trying to remove the default system role: " + JAXRSConfiguration.defaultAdminRoleName);
            return PICSUREResponse.protocolError("Default System Role cannot be removed - uuid: " + role.getUuid().toString()
                    + ", name: " + role.getName());
        }
        return removeEntityById(roleId, roleRepo);
    }

    /**
     * check if the privileges under role is in the database or not,
     * then retrieve it from database and attach it to role object
     *
     * @param roles
     * @return
     */
    private void checkPrivilegeAssociation(List<Role> roles){

        for (Role role: roles){
            if (role.getPrivileges() != null) {
                Set<Privilege> privileges = new HashSet<>();
                role.getPrivileges().stream().forEach(p -> privilegeRepo.addObjectToSet(privileges, privilegeRepo, p));
                role.setPrivileges(privileges);
            }
        }

    }

}
