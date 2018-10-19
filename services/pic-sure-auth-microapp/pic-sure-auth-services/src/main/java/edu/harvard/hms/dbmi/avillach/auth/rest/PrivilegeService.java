package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.hms.dbmi.avillach.auth.data.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.PrivilegeRepository;
import edu.harvard.hms.dbmi.avillach.auth.service.BaseEntityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.transaction.Transactional;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.List;

@Path("/privilege")
public class PrivilegeService extends BaseEntityService<Privilege> {

    Logger logger = LoggerFactory.getLogger(PrivilegeService.class);

    @Inject
    PrivilegeRepository privilegeRepo;

    public PrivilegeService() {
        super(Privilege.class);
    }

    @GET
//    @RolesAllowed(PicsureNaming.RoleNaming.ROLE_SYSTEM)
    @Path("/{privilegeId}")
    public Response getPrivilegeById(
            @PathParam("privilegeId") String privilegeId) {
        return getEntityById(privilegeId,privilegeRepo);
    }

    @GET
//    @RolesAllowed(PicsureNaming.RoleNaming.ROLE_SYSTEM)
    @Path("")
    public Response getPrivilegeAll() {
        return getEntityAll(privilegeRepo);
    }

    @POST
//    @RolesAllowed(PicsureNaming.RoleNaming.ROLE_SYSTEM)
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/")
    public Response addPrivilege(List<Privilege> privileges){
        return addEntity(privileges, privilegeRepo);
    }

    @PUT
//    @RolesAllowed(PicsureNaming.RoleNaming.ROLE_SYSTEM)
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/")
    public Response updatePrivilege(List<Privilege> privileges){
        return updateEntity(privileges, privilegeRepo);
    }

    @Transactional
    @DELETE
//    @RolesAllowed(PicsureNaming.RoleNaming.ROLE_SYSTEM)
    @Path("/{privilegeId}")
    public Response removeById(@PathParam("privilegeId") final String privilegeId) {
        return removeEntityById(privilegeId, privilegeRepo);
    }

}
