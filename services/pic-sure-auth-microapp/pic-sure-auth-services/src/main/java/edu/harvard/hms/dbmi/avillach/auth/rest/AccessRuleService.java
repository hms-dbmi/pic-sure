package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.dbmi.avillach.util.response.PICSUREResponse;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.AccessRule;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.AccessRuleRepository;
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
import java.util.List;

import static edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming.AuthRoleNaming.ADMIN;
import static edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming.AuthRoleNaming.SUPER_ADMIN;

/**
 * <p>Endpoint for service handling business logic for access rules.</p>
 * <p>Note: Only users with the super admin role can access this endpoint.</p>
 */
@Api
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

    @ApiOperation(value = "GET information of one AccessRule with the UUID, requires ADMIN or SUPER_ADMIN role")
    @GET
    @RolesAllowed({ADMIN, SUPER_ADMIN})
    @Path("/{accessRuleId}")
    public Response getAccessRuleById(
            @ApiParam(value="The UUID of the accessRule to fetch information about")
            @PathParam("accessRuleId") String accessRuleId) {
        return getEntityById(accessRuleId,accessRuleRepo);
    }

    @ApiOperation(value = "GET a list of existing AccessRules, requires ADMIN or SUPER_ADMIN role")
    @GET
    @RolesAllowed({ADMIN, SUPER_ADMIN})
    @Path("")
    public Response getAccessRuleAll() {
        return getEntityAll(accessRuleRepo);
    }

    @ApiOperation(value = "POST a list of AccessRules, requires SUPER_ADMIN role")
    @POST
    @RolesAllowed(SUPER_ADMIN)
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/")
    public Response addAccessRule(
            @ApiParam(required = true, value = "A list of AccessRule in JSON format")
            List<AccessRule> accessRules){
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

    @ApiOperation(value = "Update a list of AccessRules, will only update the fields listed, requires SUPER_ADMIN role")
    @PUT
    @RolesAllowed(SUPER_ADMIN)
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/")
    public Response updateAccessRule(
            @ApiParam(required = true, value = "A list of AccessRule with fields to be updated in JSON format")
            List<AccessRule> accessRules){
        return updateEntity(accessRules, accessRuleRepo);
    }

    @ApiOperation(value = "DELETE an AccessRule by Id only if the accessRule is not associated by others, requires SUPER_ADMIN role")
    @Transactional
    @DELETE
    @RolesAllowed(SUPER_ADMIN)
    @Path("/{accessRuleId}")
    public Response removeById(
            @ApiParam(required = true, value = "A valid accessRule Id")
            @PathParam("accessRuleId") final String accessRuleId) {
        return removeEntityById(accessRuleId, accessRuleRepo);
    }

    @ApiOperation(value = "GET all types listed for the rule in accessRule that could be used, requires SUPER_ADMIN role")
    @GET
    @RolesAllowed(SUPER_ADMIN)
    @Path("/allTypes")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAllTypes(){
        return PICSUREResponse.success(AccessRule.TypeNaming.getTypeNameMap());
    }

}
