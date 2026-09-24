package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.hms.dbmi.avillach.auth.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.model.response.PICSUREResponse;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.PrivilegeService;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuditAttributes;
import edu.harvard.dbmi.avillach.logging.AuditEvent;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;


/**
 * <p>Endpoint for service handling business logic for privileges. <br>Note: Only users with the super admin role can access this
 * endpoint.</p>
 */
@Tag(name = "Privilege Management", description = "Privileges granted through roles")
@RestController
@RequestMapping("/privilege")
public class PrivilegeController {

    private final PrivilegeService privilegeService;

    @Autowired
    public PrivilegeController(PrivilegeService privilegeService) {
        this.privilegeService = privilegeService;
    }

    @Operation(
        summary = "Read one privilege", description = "GET information of one Privilege with the UUID, requires ADMIN or SUPER_ADMIN role"
    )
    @ApiResponse(responseCode = "200", description = "The privilege")
    @ApiResponse(responseCode = "400", description = "No privilege with that UUID")
    @AuditEvent(type = "OTHER", action = "privilege.read")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'SUPER_ADMIN')")
    @GetMapping(path = "/{privilegeId}", produces = "application/json")
    public ResponseEntity<?> getPrivilegeById(
        @Parameter(description = "The UUID of the privilege to fetch information about") @PathVariable("privilegeId") String privilegeId
    ) {
        Privilege privilegeById = this.privilegeService.getPrivilegeById(privilegeId);

        if (privilegeById == null) {
            return PICSUREResponse.protocolError("Privilege not found");
        }

        return PICSUREResponse.success(privilegeById);
    }

    @Operation(summary = "List every privilege", description = "GET a list of existing privileges, requires ADMIN or SUPER_ADMIN role")
    @ApiResponse(responseCode = "200", description = "Every privilege")
    @AuditEvent(type = "OTHER", action = "privilege.list")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'SUPER_ADMIN')")
    @GetMapping(produces = "application/json")
    public ResponseEntity<List<Privilege>> getPrivilegeAll() {
        List<Privilege> privilegesAll = this.privilegeService.getPrivilegesAll();
        return PICSUREResponse.success(privilegesAll);
    }

    @Operation(summary = "Create privileges", description = "POST a list of privileges, requires SUPER_ADMIN role")
    @ApiResponse(responseCode = "200", description = "The created privileges")
    @AuditEvent(type = "ADMIN", action = "privilege.modify")
    @PreAuthorize("hasAnyAuthority('SUPER_ADMIN')")
    @PostMapping(consumes = "application/json", produces = "application/json")
    public ResponseEntity<List<Privilege>> addPrivilege(
        @Parameter(required = true, description = "A list of privileges in JSON format") @RequestBody List<Privilege> privileges,
        HttpServletRequest request
    ) {
        AuditAttributes.putMetadata(request, "privilege_count", String.valueOf(privileges.size()));
        privileges = this.privilegeService.addPrivileges(privileges);
        return PICSUREResponse.success(privileges);
    }

    @Operation(
        summary = "Update the given fields of privileges",
        description = "Update a list of privileges, will only update the fields listed, requires SUPER_ADMIN role"
    )
    @ApiResponse(responseCode = "200", description = "The updated privileges")
    @AuditEvent(type = "ADMIN", action = "privilege.modify")
    @PreAuthorize("hasAnyAuthority('SUPER_ADMIN')")
    @PutMapping(consumes = "application/json", produces = "application/json")
    public ResponseEntity<List<Privilege>> updatePrivilege(
        @Parameter(
            required = true, description = "A list of privilege with fields to be updated in JSON format"
        ) @RequestBody List<Privilege> privileges, HttpServletRequest request
    ) {
        AuditAttributes.putMetadata(request, "privilege_count", String.valueOf(privileges.size()));
        privileges = this.privilegeService.updatePrivileges(privileges);
        return ResponseEntity.ok(privileges);
    }

    @Operation(
        summary = "Delete a privilege that nothing references",
        description = "DELETE an privilege by Id only if the privilege is not associated by others, requires SUPER_ADMIN role"
    )
    @ApiResponse(responseCode = "200", description = "The remaining privileges")
    @ApiResponse(responseCode = "409", description = "Other entities still reference this privilege")
    @AuditEvent(type = "ADMIN", action = "privilege.delete")
    @PreAuthorize("hasAnyAuthority('SUPER_ADMIN')")
    @DeleteMapping(path = "/{privilegeId}", produces = "application/json")
    public ResponseEntity<List<Privilege>> removeById(
        @Parameter(required = true, description = "A valid privilege Id") @PathVariable("privilegeId") final String privilegeId,
        HttpServletRequest request
    ) {
        AuditAttributes.putMetadata(request, "privilege_id", privilegeId);
        List<Privilege> privileges = this.privilegeService.deletePrivilegeByPrivilegeId(privilegeId);
        return ResponseEntity.ok(privileges);
    }

}
