package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.hms.dbmi.avillach.auth.entity.AccessRule;
import edu.harvard.hms.dbmi.avillach.auth.exceptions.PicSureResponseException;
import edu.harvard.hms.dbmi.avillach.auth.model.request.AccessRuleCreateRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.request.AccessRuleUpdateRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.response.AccessRuleResponse;
import edu.harvard.hms.dbmi.avillach.auth.model.response.AccessRuleTypesResponse;
import edu.harvard.hms.dbmi.avillach.auth.model.response.PICSUREResponse;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.AccessRuleService;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuditAttributes;
import edu.harvard.dbmi.avillach.logging.AuditEvent;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.List;


/**
 * <p>Endpoint for service handling business logic for access rules.</p> <p>Note: Only users with the super admin role can access this
 * endpoint.</p> <p> Path: /accessRule
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

    @Operation(summary = "Read one access rule", description = "GET information of one AccessRule with the UUID.")
    @ApiResponse(responseCode = "200", description = "The access rule")
    @ApiResponse(responseCode = "404", description = "No access rule has that id")
    @AuditEvent(type = "OTHER", action = "access_rule.read")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'SUPER_ADMIN')")
    @GetMapping(value = "/{accessRuleId}")
    public ResponseEntity<AccessRuleResponse> getAccessRuleById(
        @Parameter(description = "The UUID of the accessRule to fetch information about") @PathVariable("accessRuleId") String accessRuleId
    ) {
        AccessRule accessRule = this.accessRuleService.getAccessRuleById(accessRuleId)
            .orElseThrow(() -> new PicSureResponseException(HttpStatus.NOT_FOUND, "AccessRule not found", null));

        return PICSUREResponse.success(AccessRuleResponse.from(accessRule));
    }

    @Operation(summary = "List every access rule", description = "GET a list of existing AccessRules")
    @ApiResponse(responseCode = "200", description = "Every access rule")
    @AuditEvent(type = "OTHER", action = "access_rule.list")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'SUPER_ADMIN')")
    @GetMapping("")
    public ResponseEntity<List<AccessRuleResponse>> getAccessRuleAll() {
        return PICSUREResponse.success(AccessRuleResponse.fromAll(this.accessRuleService.getAllAccessRules()));
    }

    @Operation(summary = "Create access rules", description = "POST a list of AccessRules")
    @ApiResponse(responseCode = "200", description = "The created access rules")
    @ApiResponse(responseCode = "400", description = "No access rules were added")
    @AuditEvent(type = "ADMIN", action = "access_rule.modify")
    @PreAuthorize("hasAnyAuthority('SUPER_ADMIN')")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<AccessRuleResponse>> addAccessRule(
        @Parameter(
            required = true, description = "The access rules to create; the server generates each identifier"
        ) @RequestBody List<@NotNull @Valid AccessRuleCreateRequest> accessRuleRequests, HttpServletRequest request
    ) {
        AuditAttributes.putMetadata(request, "access_rule_count", String.valueOf(accessRuleRequests.size()));
        List<AccessRule> accessRules = this.accessRuleService.createFrom(accessRuleRequests);

        if (accessRules.isEmpty()) {
            throw new PicSureResponseException(HttpStatus.BAD_REQUEST, "No access rules added", null);
        }

        return PICSUREResponse.success(AccessRuleResponse.fromAll(accessRules));
    }

    @Operation(
        summary = "Update the given fields of access rules",
        description = "Update a list of AccessRules, will only update the fields listed"
    )
    @ApiResponse(responseCode = "200", description = "The updated access rules")
    @AuditEvent(type = "ADMIN", action = "access_rule.modify")
    @PreAuthorize("hasAnyAuthority('SUPER_ADMIN')")
    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<AccessRuleResponse>> updateAccessRule(
        @Parameter(
            required = true, description = "The access rules to update, each named by UUID; a field left out keeps its stored value"
        ) @RequestBody List<@NotNull @Valid AccessRuleUpdateRequest> accessRules, HttpServletRequest request
    ) {
        AuditAttributes.putMetadata(request, "access_rule_count", String.valueOf(accessRules.size()));
        return PICSUREResponse.success(AccessRuleResponse.fromAll(this.accessRuleService.updateFrom(accessRules)));
    }

    @Operation(
        summary = "Delete an access rule that nothing references",
        description = "DELETE an AccessRule by Id only if the accessRule is not associated by others"
    )
    @ApiResponse(responseCode = "200", description = "The remaining access rules")
    @ApiResponse(responseCode = "409", description = "Other entities still reference this access rule")
    @AuditEvent(type = "ADMIN", action = "access_rule.delete")
    @PreAuthorize("hasAnyAuthority('SUPER_ADMIN')")
    @DeleteMapping(path = "/{accessRuleId}")
    public ResponseEntity<List<AccessRuleResponse>> removeById(
        @Parameter(required = true, description = "A valid accessRule Id") @PathVariable("accessRuleId") final String accessRuleId,
        HttpServletRequest request
    ) {
        AuditAttributes.putMetadata(request, "access_rule_id", accessRuleId);
        return PICSUREResponse.success(AccessRuleResponse.fromAll(this.accessRuleService.removeAccessRuleById(accessRuleId)));
    }

    @Operation(
        summary = "The rule types an access rule may use",
        description = "GET all types listed for the rule in accessRule that could be used"
    )
    @ApiResponse(responseCode = "200", description = "Rule type names mapped to their numeric values, under the types member.")
    @AuditEvent(type = "OTHER", action = "access_rule.types")
    @PreAuthorize("hasAnyAuthority('SUPER_ADMIN')")
    @GetMapping(path = "/allTypes", produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AccessRuleTypesResponse> getAllTypes() {
        return PICSUREResponse.success(new AccessRuleTypesResponse(AccessRule.TypeNaming.getTypeNameMap()));
    }

}
