package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.hms.dbmi.avillach.auth.entity.AccessRule;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.AccessRuleService;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuditAttributes;
import edu.harvard.dbmi.avillach.logging.AuditEvent;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming.AuthRoleNaming.ADMIN;
import static edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming.AuthRoleNaming.SUPER_ADMIN;

/**
 * <p>Endpoint for service handling business logic for access rules.</p> <p>Note: Only users with the super admin role can access this
 * endpoint.</p> <p> Path: /accessRule
 */
@Tag(name = "Access Rule Management")
@RestController
@RequestMapping(value = "/accessRule")
public class AccessRuleController {

    private final AccessRuleService accessRuleService;

    @Autowired
    public AccessRuleController(AccessRuleService accessRuleService) {
        this.accessRuleService = accessRuleService;
    }

    /**
     * The miss is a 404. It used to be a 500 whose body was {@code {"message":"AccessRule not found","content":404}} -- the intended status
     * passed as the envelope's payload -- so callers saw a server fault for an ordinary missing id.
     */
    @Operation(description = "GET information of one AccessRule with the UUID, requires ADMIN or SUPER_ADMIN role")
    @AuditEvent(type = "OTHER", action = "access_rule.read")
    @RolesAllowed({ADMIN, SUPER_ADMIN})
    @GetMapping(value = "/{accessRuleId}")
    public AccessRule getAccessRuleById(
        @Parameter(description = "The UUID of the accessRule to fetch information about") @PathVariable("accessRuleId") String accessRuleId
    ) {
        Optional<AccessRule> entityById = this.accessRuleService.getAccessRuleById(accessRuleId);

        return entityById.orElseThrow(() -> new PicsureException(HttpStatus.NOT_FOUND, "not_found", "AccessRule not found"));
    }

    @Operation(description = "GET a list of existing AccessRules, requires ADMIN or SUPER_ADMIN role")
    @AuditEvent(type = "OTHER", action = "access_rule.list")
    @RolesAllowed({ADMIN, SUPER_ADMIN})
    @GetMapping("")
    public List<AccessRule> getAccessRuleAll() {
        return this.accessRuleService.getAllAccessRules();
    }

    @Operation(description = "POST a list of AccessRules, requires SUPER_ADMIN role")
    @AuditEvent(type = "ADMIN", action = "access_rule.modify")
    @RolesAllowed(SUPER_ADMIN)
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public List<AccessRule> addAccessRule(
        @Parameter(required = true, description = "A list of AccessRule in JSON format") @RequestBody List<AccessRule> accessRules,
        HttpServletRequest request
    ) {
        AuditAttributes.putMetadata(request, "access_rule_count", String.valueOf(accessRules.size()));
        List<AccessRule> saved = this.accessRuleService.addAccessRule(accessRules);

        if (saved.isEmpty()) {
            throw new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", "No access rules added");
        }

        return saved;
    }

    @Operation(description = "Update a list of AccessRules, will only update the fields listed, requires SUPER_ADMIN role")
    @AuditEvent(type = "ADMIN", action = "access_rule.modify")
    @RolesAllowed(SUPER_ADMIN)
    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public List<AccessRule> updateAccessRule(
        @Parameter(
            required = true, description = "A list of AccessRule with fields to be updated in JSON format"
        ) @RequestBody List<AccessRule> accessRules, HttpServletRequest request
    ) {
        AuditAttributes.putMetadata(request, "access_rule_count", String.valueOf(accessRules.size()));
        return this.accessRuleService.updateAccessRules(accessRules);
    }

    @Operation(description = "DELETE an AccessRule by Id only if the accessRule is not associated by others, requires SUPER_ADMIN role")
    @AuditEvent(type = "ADMIN", action = "access_rule.delete")
    @RolesAllowed(SUPER_ADMIN)
    @DeleteMapping(path = "/{accessRuleId}")
    public List<AccessRule> removeById(
        @Parameter(required = true, description = "A valid accessRule Id") @PathVariable("accessRuleId") final String accessRuleId,
        HttpServletRequest request
    ) {
        AuditAttributes.putMetadata(request, "access_rule_id", accessRuleId);
        return this.accessRuleService.removeAccessRuleById(accessRuleId);
    }

    /**
     * {@code consumes = application/json} is gone: this is a GET with no body, and demanding a request content type on it means a plain
     * {@code GET /accessRule/allTypes} answers 415 rather than the type map.
     */
    @Operation(description = "GET all types listed for the rule in accessRule that could be used, requires SUPER_ADMIN role")
    @AuditEvent(type = "OTHER", action = "access_rule.types")
    @RolesAllowed(SUPER_ADMIN)
    @GetMapping(path = "/allTypes", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Integer> getAllTypes() {
        return AccessRule.TypeNaming.getTypeNameMap();
    }

}
