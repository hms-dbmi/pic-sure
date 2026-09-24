package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.hms.dbmi.avillach.auth.entity.AccessRule;
import edu.harvard.hms.dbmi.avillach.auth.model.response.PICSUREResponse;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.AccessRuleService;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuditAttributes;
import edu.harvard.dbmi.avillach.logging.AuditEvent;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;


/**
 * <p>Endpoint for service handling business logic for access rules.</p>
 * <p>Note: Only users with the super admin role can access this endpoint.</p>
 * <p>
 * Path: /accessRule
 */
@Tag(name = "Access Rule Management", description = "Access rules that gate what a privilege permits")
@Controller
@RequestMapping(value = "/accessRule")
public class AccessRuleController {

    private final AccessRuleService accessRuleService;

    @Autowired
    public AccessRuleController(AccessRuleService accessRuleService) {
        this.accessRuleService = accessRuleService;
    }

    @Operation(
        summary = "Read one access rule",
        description = "GET information of one AccessRule with the UUID, requires ADMIN or SUPER_ADMIN role"
    )
    @ApiResponse(responseCode = "200", description = "The access rule")
    @ApiResponse(responseCode = "404", description = "No access rule has that id")
    @AuditEvent(type = "OTHER", action = "access_rule.read")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'SUPER_ADMIN')")
    @GetMapping(value = "/{accessRuleId}")
    public ResponseEntity<?> getAccessRuleById(
        @Parameter(description = "The UUID of the accessRule to fetch information about") @PathVariable("accessRuleId") String accessRuleId
    ) {
        Optional<AccessRule> entityById = this.accessRuleService.getAccessRuleById(accessRuleId);

        if (entityById.isEmpty()) {
            return PICSUREResponse.error("AccessRule not found", 404);
        }

        return PICSUREResponse.success(entityById.get());
    }

    @Operation(summary = "List every access rule", description = "GET a list of existing AccessRules, requires ADMIN or SUPER_ADMIN role")
    @ApiResponse(responseCode = "200", description = "Every access rule")
    @AuditEvent(type = "OTHER", action = "access_rule.list")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'SUPER_ADMIN')")
    @GetMapping("")
    public ResponseEntity<List<AccessRule>> getAccessRuleAll() {
        List<AccessRule> allAccessRules = this.accessRuleService.getAllAccessRules();
        return PICSUREResponse.success(allAccessRules);
    }

    @Operation(summary = "Create access rules", description = "POST a list of AccessRules, requires SUPER_ADMIN role")
    @ApiResponse(responseCode = "200", description = "The created access rules")
    @ApiResponse(responseCode = "400", description = "No access rules were added")
    @AuditEvent(type = "ADMIN", action = "access_rule.modify")
    @PreAuthorize("hasAnyAuthority('SUPER_ADMIN')")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> addAccessRule(
        @Parameter(required = true, description = "A list of AccessRule in JSON format") @RequestBody List<AccessRule> accessRules,
        HttpServletRequest request
    ) {
        AuditAttributes.putMetadata(request, "access_rule_count", String.valueOf(accessRules.size()));
        accessRules = this.accessRuleService.addAccessRule(accessRules);

        if (accessRules.isEmpty()) {
            return PICSUREResponse.protocolError("No access rules added", 400);
        }

        return PICSUREResponse.success(accessRules);
    }

    @Operation(
        summary = "Update the given fields of access rules",
        description = "Update a list of AccessRules, will only update the fields listed, requires SUPER_ADMIN role"
    )
    @ApiResponse(responseCode = "200", description = "The updated access rules")
    @AuditEvent(type = "ADMIN", action = "access_rule.modify")
    @PreAuthorize("hasAnyAuthority('SUPER_ADMIN')")
    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<AccessRule>> updateAccessRule(
        @Parameter(
            required = true, description = "A list of AccessRule with fields to be updated in JSON format"
        ) @RequestBody List<AccessRule> accessRules, HttpServletRequest request
    ) {
        AuditAttributes.putMetadata(request, "access_rule_count", String.valueOf(accessRules.size()));
        accessRules = this.accessRuleService.updateAccessRules(accessRules);
        return PICSUREResponse.success(accessRules);
    }

    @Operation(
        summary = "Delete an access rule that nothing references",
        description = "DELETE an AccessRule by Id only if the accessRule is not associated by others, requires SUPER_ADMIN role"
    )
    @ApiResponse(responseCode = "200", description = "The remaining access rules")
    @ApiResponse(responseCode = "409", description = "Other entities still reference this access rule")
    @AuditEvent(type = "ADMIN", action = "access_rule.delete")
    @PreAuthorize("hasAnyAuthority('SUPER_ADMIN')")
    @DeleteMapping(path = "/{accessRuleId}")
    public ResponseEntity<List<AccessRule>> removeById(
        @Parameter(required = true, description = "A valid accessRule Id") @PathVariable("accessRuleId") final String accessRuleId,
        HttpServletRequest request
    ) {
        AuditAttributes.putMetadata(request, "access_rule_id", accessRuleId);
        return PICSUREResponse.success(this.accessRuleService.removeAccessRuleById(accessRuleId));
    }

    @Operation(
        summary = "The rule types an access rule may use",
        description = "GET all types listed for the rule in accessRule that could be used, requires SUPER_ADMIN role"
    )
    @ApiResponse(responseCode = "200", description = "Rule type names mapped to their numeric values")
    @AuditEvent(type = "OTHER", action = "access_rule.types")
    @PreAuthorize("hasAnyAuthority('SUPER_ADMIN')")
    @GetMapping(path = "/allTypes", produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Integer>> getAllTypes() {
        return PICSUREResponse.success(AccessRule.TypeNaming.getTypeNameMap());
    }

}
