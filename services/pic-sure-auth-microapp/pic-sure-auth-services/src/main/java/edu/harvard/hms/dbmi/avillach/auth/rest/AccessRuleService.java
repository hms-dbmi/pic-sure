package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.dbmi.avillach.util.response.PICSUREResponse;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.AccessRule;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.AccessRuleRepository;
import edu.harvard.hms.dbmi.avillach.auth.service.BaseEntityService;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming.AuthRoleNaming;
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
import java.util.List;

@Path("/accessRule")
public class AccessRuleService extends BaseEntityService<AccessRule> {

    Logger logger = LoggerFactory.getLogger(AccessRuleService.class);

    @Inject
    AccessRuleRepository accessRuleRepo;

    @Context
    SecurityContext securityContext;

    public AccessRuleService() {
        super(AccessRule.class);
    }

    @GET
    @Path("/{accessRuleId}")
    public Response getAccessRuleById(
            @PathParam("accessRuleId") String accessRuleId) {
        return getEntityById(accessRuleId,accessRuleRepo);
    }

    @GET
    @Path("")
    public Response getAccessRuleAll() {
        return getEntityAll(accessRuleRepo);
    }

    @POST
    @RolesAllowed(AuthRoleNaming.SUPER_ADMIN)
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/")
    public Response addAccessRule(List<AccessRule> accessRules){
        return addEntity(accessRules, accessRuleRepo);
    }

    @PUT
    @RolesAllowed(AuthRoleNaming.SUPER_ADMIN)
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/")
    public Response updateAccessRule(List<AccessRule> accessRules){
        return updateEntity(accessRules, accessRuleRepo);
    }

    @Transactional
    @DELETE
    @RolesAllowed(AuthRoleNaming.SUPER_ADMIN)
    @Path("/{accessRuleId}")
    public Response removeById(@PathParam("accessRuleId") final String accessRuleId) {
        return removeEntityById(accessRuleId, accessRuleRepo);
    }

    @GET
    @RolesAllowed(AuthRoleNaming.SUPER_ADMIN)
    @Path("/allTypes")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAllTypes(){
        return PICSUREResponse.success(AccessRule.TypeNaming.getTypeNameMap());
    }

}
