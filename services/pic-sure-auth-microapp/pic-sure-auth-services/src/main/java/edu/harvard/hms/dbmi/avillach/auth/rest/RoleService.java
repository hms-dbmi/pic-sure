package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.dbmi.avillach.util.PicsureNaming;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.Role;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.PrivilegeRepository;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.RoleRepository;
import edu.harvard.hms.dbmi.avillach.auth.service.BaseEntityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.transaction.Transactional;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Path("/role")
public class RoleService extends BaseEntityService<Role> {

    Logger logger = LoggerFactory.getLogger(RoleService.class);

    @Inject
    RoleRepository roleRepo;

    @Inject
    PrivilegeRepository privilegeRepo;

    public RoleService() {
        super(Role.class);
    }

    @GET
    @RolesAllowed(PicsureNaming.RoleNaming.ROLE_SYSTEM)
    @Path("/{roleId}")
    public Response getRoleById(
            @PathParam("roleId") String roleId) {
        return getEntityById(roleId,roleRepo);
    }

    @GET
    @RolesAllowed(PicsureNaming.RoleNaming.ROLE_SYSTEM)
    @Path("")
    public Response getRoleAll() {
        return getEntityAll(roleRepo);
    }

    @Transactional
    @POST
    @RolesAllowed(PicsureNaming.RoleNaming.ROLE_SYSTEM)
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/")
    public Response addRole(List<Role> roles){
        checkPrivilegeAssociation(roles);
        return addEntity(roles, roleRepo);
    }

    @Transactional
    @PUT
    @RolesAllowed(PicsureNaming.RoleNaming.ROLE_SYSTEM)
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/")
    public Response updateRole(List<Role> roles){
        checkPrivilegeAssociation(roles);
        return updateEntity(roles, roleRepo);
    }
    
    @Transactional
    @DELETE
    @RolesAllowed(PicsureNaming.RoleNaming.ROLE_SYSTEM)
    @Path("/{roleId}")
    public Response removeById(@PathParam("roleId") final String roleId) {
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
            if (role.getPrivileges() == null)
                continue;

            Set<Privilege> privilegeSet = new HashSet<>();
            for (Privilege privilege: role.getPrivileges()) {
                if (privilege.getUuid() == null)
                    continue;

                Privilege p = privilegeRepo.getById(privilege.getUuid());
                if (p != null)
                    privilegeSet.add(p);
            }

            role.setPrivileges(privilegeSet);
        }

    }

}
