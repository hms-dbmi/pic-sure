package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.hms.dbmi.avillach.auth.entity.AccessRule;
import edu.harvard.hms.dbmi.avillach.auth.model.response.PICSUREResponse;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.authorization.AccessRuleService;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.security.RolesAllowed;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming.AuthRoleNaming.ADMIN;
import static edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming.AuthRoleNaming.SUPER_ADMIN;

/**
 * <p>Endpoint for service handling business logic for access rules.</p>
 * <p>Note: Only users with the super admin role can access this endpoint.</p>
 * <p>
 * Path: /accessRule
 */
@Tag(name = "Access Rule Management")
@Controller
@RequestMapping(value = "/accessRule")
public class AccessRuleController {

    private final AccessRuleService accessRuleService;

    @Autowired
    public AccessRuleController(AccessRuleService accessRuleService) {
        this.accessRuleService = accessRuleService;
    }

    @Operation(description = "GET information of one AccessRule with the UUID, requires ADMIN or SUPER_ADMIN role")
    @Secured(value = {ADMIN, SUPER_ADMIN})
    @GetMapping(value = "/{accessRuleId}")
    public ResponseEntity<?> getAccessRuleById(
            @Parameter(description = "The UUID of the accessRule to fetch information about")
            @PathVariable("accessRuleId") String accessRuleId) {
        Optional<AccessRule> entityById = this.accessRuleService.getAccessRuleById(accessRuleId);

        if (entityById.isEmpty()) {
            return PICSUREResponse.error("AccessRule not found", 404);
        }

        return PICSUREResponse.success(entityById.get());
    }

    @Operation(description = "GET a list of existing AccessRules, requires ADMIN or SUPER_ADMIN role")
    @Secured({ADMIN, SUPER_ADMIN})
    @GetMapping("")
    public ResponseEntity<List<AccessRule>> getAccessRuleAll() {
        List<AccessRule> allAccessRules = this.accessRuleService.getAllAccessRules();
        return PICSUREResponse.success(allAccessRules);
    }

    @Operation(description = "POST a list of AccessRules, requires SUPER_ADMIN role")
    @RolesAllowed(SUPER_ADMIN)
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> addAccessRule(
            @Parameter(required = true, description = "A list of AccessRule in JSON format")
            @RequestBody List<AccessRule> accessRules) {
        accessRules = this.accessRuleService.addAccessRule(accessRules);

        if (accessRules.isEmpty()) {
            return PICSUREResponse.protocolError("No access rules added", 400);
        }

        return PICSUREResponse.success(accessRules);
    }

    @Operation(description = "Update a list of AccessRules, will only update the fields listed, requires SUPER_ADMIN role")
    @RolesAllowed(SUPER_ADMIN)
    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<AccessRule>> updateAccessRule(
            @Parameter(required = true, description = "A list of AccessRule with fields to be updated in JSON format")
            @RequestBody List<AccessRule> accessRules) {
        accessRules = this.accessRuleService.updateAccessRules(accessRules);
        return PICSUREResponse.success(accessRules);
    }

    @Operation(description = "DELETE an AccessRule by Id only if the accessRule is not associated by others, requires SUPER_ADMIN role")
    @RolesAllowed(SUPER_ADMIN)
    @DeleteMapping(path = "/{accessRuleId}")
    public ResponseEntity<List<AccessRule>> removeById(
            @Parameter(required = true, description = "A valid accessRule Id")
            @PathVariable("accessRuleId") final String accessRuleId) {
        return PICSUREResponse.success(this.accessRuleService.removeAccessRuleById(accessRuleId));
    }

    @Operation(description = "GET all types listed for the rule in accessRule that could be used, requires SUPER_ADMIN role")
    @RolesAllowed(SUPER_ADMIN)
    @GetMapping(path = "/allTypes", produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Integer>> getAllTypes() {
        return PICSUREResponse.success(AccessRule.TypeNaming.getTypeNameMap());
    }

}
