package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.hms.dbmi.avillach.auth.entity.Role;
import edu.harvard.hms.dbmi.avillach.auth.model.response.PICSUREResponse;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.RoleService;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuditAttributes;
import edu.harvard.dbmi.avillach.logging.AuditEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.text.MessageFormat;
import java.util.List;
import java.util.Optional;


/**
 * <p>Endpoint for service handling business logic for user roles. <br>Note: Users with admin level access can view roles, but only super
 * admin users can modify them.</p>
 */
@Tag(name = "Role Management", description = "Roles that bundle privileges for users")
@Controller
@RequestMapping("/role")
public class RoleController {

    private final RoleService roleService;

    @Autowired
    public RoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    @Operation(summary = "Read one role", description = "GET information of one Role with the UUID, requires ADMIN or SUPER_ADMIN role")
    @ApiResponse(responseCode = "200", description = "The role")
    @ApiResponse(responseCode = "400", description = "No role with that UUID")
    @AuditEvent(type = "OTHER", action = "role.read")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'SUPER_ADMIN')")
    @GetMapping(produces = "application/json", path = "/{roleId}")
    public ResponseEntity<?> getRoleById(
        @Parameter(description = "The UUID of the Role to fetch information about") @PathVariable("roleId") String roleId
    ) {
        Optional<Role> optionalRole = this.roleService.getRoleById(roleId);
        if (optionalRole.isEmpty()) {
            return PICSUREResponse.protocolError("Role is not found by given role ID: " + roleId);
        }
        return PICSUREResponse.success(optionalRole.get());
    }

    @Operation(summary = "List every role", description = "GET a list of existing Roles, requires ADMIN or SUPER_ADMIN role")
    @ApiResponse(responseCode = "200", description = "Every role")
    @AuditEvent(type = "OTHER", action = "role.list")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'SUPER_ADMIN')")
    @GetMapping
    public ResponseEntity<List<Role>> getRoleAll() {
        List<Role> allRoles = this.roleService.getAllRoles();
        return PICSUREResponse.success(allRoles);
    }

    @Operation(summary = "Create roles", description = "POST a list of Roles, requires SUPER_ADMIN role")
    @ApiResponse(responseCode = "200", description = "The created roles")
    @AuditEvent(type = "ADMIN", action = "role.modify")
    @PreAuthorize("hasAnyAuthority('SUPER_ADMIN')")
    @PostMapping(produces = "application/json")
    public ResponseEntity<?> addRole(
        @Parameter(required = true, description = "A list of Roles in JSON format") @RequestBody List<Role> roles,
        HttpServletRequest request
    ) {
        AuditAttributes.putMetadata(request, "role_count", String.valueOf(roles.size()));
        List<Role> savedRoles = this.roleService.addRoles(roles);
        return PICSUREResponse.success("All roles are added.", savedRoles);
    }

    @Operation(
        summary = "Update the given fields of roles",
        description = "Update a list of Roles, will only update the fields listed, requires SUPER_ADMIN role"
    )
    @ApiResponse(responseCode = "200", description = "The updated roles")
    @AuditEvent(type = "ADMIN", action = "role.modify")
    @PreAuthorize("hasAnyAuthority('SUPER_ADMIN')")
    @PutMapping(produces = "application/json")
    public ResponseEntity<?> updateRole(
        @Parameter(required = true, description = "A list of Roles with fields to be updated in JSON format") @RequestBody List<Role> roles,
        HttpServletRequest request
    ) {
        AuditAttributes.putMetadata(request, "role_count", String.valueOf(roles.size()));
        List<Role> updatedRoles = this.roleService.updateRoles(roles);
        if (updatedRoles.isEmpty()) {
            return PICSUREResponse.protocolError("No Role(s) has been updated.");
        }

        return PICSUREResponse.success("All Roles are updated.", updatedRoles);
    }

    @Operation(
        summary = "Delete a role that nothing references",
        description = "DELETE an Role by Id only if the Role is not associated by others, requires SUPER_ADMIN role"
    )
    @ApiResponses(
        {@ApiResponse(responseCode = "200", description = "The remaining roles"),
            @ApiResponse(responseCode = "400", description = "No role with that UUID"),
            @ApiResponse(responseCode = "409", description = "Other entities still reference this role")}
    )
    @AuditEvent(type = "ADMIN", action = "role.delete")
    @PreAuthorize("hasAnyAuthority('SUPER_ADMIN')")
    @DeleteMapping(produces = "application/json", path = "/{roleId}")
    public ResponseEntity<?> removeById(
        @Parameter(required = true, description = "A valid Role Id") @PathVariable("roleId") final String roleId, HttpServletRequest request
    ) {
        AuditAttributes.putMetadata(request, "role_id", roleId);
        Optional<List<Role>> roles = this.roleService.removeRoleById(roleId);
        if (roles.isEmpty()) {
            return PICSUREResponse.protocolError("Role not found - uuid: " + roleId);
        }

        return PICSUREResponse.success(
            MessageFormat.format("Successfully deleted role by id: {0}, listing rest of the role(s) as below", roleId), roles.get()
        );
    }


}
