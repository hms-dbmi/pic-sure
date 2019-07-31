package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.dbmi.avillach.util.response.PICSUREResponse;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.AccessRule;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.AccessRuleRepository;
import edu.harvard.hms.dbmi.avillach.auth.service.BaseEntityService;
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

import static edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming.AuthRoleNaming.ADMIN;
import static edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming.AuthRoleNaming.SUPER_ADMIN;

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
    @RolesAllowed({ADMIN, SUPER_ADMIN})
    @Path("/{accessRuleId}")
    public Response getAccessRuleById(
            @PathParam("accessRuleId") String accessRuleId) {
        return getEntityById(accessRuleId,accessRuleRepo);
    }

    @GET
    @RolesAllowed({ADMIN, SUPER_ADMIN})
    @Path("")
    public Response getAccessRuleAll() {
        return getEntityAll(accessRuleRepo);
    }

    @POST
    @RolesAllowed(SUPER_ADMIN)
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/")
    public Response addAccessRule(List<AccessRule> accessRules){
        accessRules.stream().forEach(accessRule -> {
            if (accessRule.getEvaluateOnlyByGates() == null)
                accessRule.setEvaluateOnlyByGates(false);

            if (accessRule.getCheckMapKeyOnly() == null)
                accessRule.setCheckMapKeyOnly(false);

            if (accessRule.getCheckMapNode() == null)
                accessRule.setCheckMapNode(false);

            if (accessRule.getGateAnyRelation() == null)
                accessRule.setGateAnyRelation(false);
        });
        return addEntity(accessRules, accessRuleRepo);
    }

    @PUT
    @RolesAllowed(SUPER_ADMIN)
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/")
    public Response updateAccessRule(List<AccessRule> accessRules){
        return updateEntity(accessRules, accessRuleRepo);
    }

    @Transactional
    @DELETE
    @RolesAllowed(SUPER_ADMIN)
    @Path("/{accessRuleId}")
    public Response removeById(@PathParam("accessRuleId") final String accessRuleId) {
        return removeEntityById(accessRuleId, accessRuleRepo);
    }

    @GET
    @RolesAllowed(SUPER_ADMIN)
    @Path("/allTypes")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAllTypes(){
        return PICSUREResponse.success(AccessRule.TypeNaming.getTypeNameMap());
    }

}
