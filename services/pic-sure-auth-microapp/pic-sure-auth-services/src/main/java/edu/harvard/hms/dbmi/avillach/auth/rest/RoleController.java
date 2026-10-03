package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.hms.dbmi.avillach.auth.entity.Role;
import edu.harvard.hms.dbmi.avillach.auth.exceptions.PicSureResponseException;
import edu.harvard.hms.dbmi.avillach.auth.model.request.RoleCreateRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.request.RoleUpdateRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.response.PICSUREResponse;
import edu.harvard.hms.dbmi.avillach.auth.model.response.PicSureResponseBody;
import edu.harvard.hms.dbmi.avillach.auth.model.response.RoleResponse;
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
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.text.MessageFormat;
import java.util.List;


/**
 * <p>Endpoint for service handling business logic for user roles. <br>Note: Users with admin level access can view roles, but only super
 * admin users can modify them.</p>
 */
@Tag(name = "Role Management", description = "Roles that bundle privileges for users.")
@Controller
@RequestMapping("/role")
public class RoleController {

    private final RoleService roleService;

    @Autowired
    public RoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    @Operation(summary = "Read one role", description = "GET information of one Role with the UUID.")
    @ApiResponse(responseCode = "200", description = "The role.")
    @ApiResponse(responseCode = "400", description = "No role with that UUID.")
    @AuditEvent(type = "OTHER", action = "role.read")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'SUPER_ADMIN')")
    @GetMapping(produces = "application/json", path = "/{roleId}")
    public ResponseEntity<RoleResponse> getRoleById(
        @Parameter(description = "The UUID of the Role to fetch information about.") @PathVariable("roleId") String roleId
    ) {
        Role role = this.roleService.getRoleById(roleId).orElseThrow(
            () -> new PicSureResponseException(HttpStatus.BAD_REQUEST, "Invalid request", "Role is not found by given role ID: " + roleId)
        );
        return PICSUREResponse.success(RoleResponse.from(role));
    }

    @Operation(summary = "List every role", description = "GET a list of existing Roles.")
    @ApiResponse(responseCode = "200", description = "Every role.")
    @AuditEvent(type = "OTHER", action = "role.list")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'SUPER_ADMIN')")
    @GetMapping
    public ResponseEntity<List<RoleResponse>> getRoleAll() {
        return PICSUREResponse.success(RoleResponse.fromAll(this.roleService.getAllRoles()));
    }

    @Operation(summary = "Create roles", description = "POST a list of Roles.")
    @ApiResponse(responseCode = "200", description = "The created roles, in the message and content envelope.")
    @AuditEvent(type = "ADMIN", action = "role.modify")
    @PreAuthorize("hasAnyAuthority('SUPER_ADMIN')")
    @PostMapping(produces = "application/json")
    public ResponseEntity<PicSureResponseBody<List<RoleResponse>>> addRole(
        @Parameter(
            required = true, description = "The roles to create, each naming its privileges by UUID."
        ) @RequestBody List<@NotNull @Valid RoleCreateRequest> roles, HttpServletRequest request
    ) {
        AuditAttributes.putMetadata(request, "role_count", String.valueOf(roles.size()));
        return PICSUREResponse.success("All roles are added.", RoleResponse.fromAll(this.roleService.createFrom(roles)));
    }

    @Operation(summary = "Update the given fields of roles", description = "Update a list of Roles, will only update the fields listed.")
    @ApiResponse(responseCode = "200", description = "The updated roles, in the message and content envelope.")
    @AuditEvent(type = "ADMIN", action = "role.modify")
    @PreAuthorize("hasAnyAuthority('SUPER_ADMIN')")
    @PutMapping(produces = "application/json")
    public ResponseEntity<PicSureResponseBody<List<RoleResponse>>> updateRole(
        @Parameter(
            required = true, description = "The roles to update, each named by UUID; a field left out keeps its stored value."
        ) @RequestBody List<@NotNull @Valid RoleUpdateRequest> roles, HttpServletRequest request
    ) {
        AuditAttributes.putMetadata(request, "role_count", String.valueOf(roles.size()));
        List<Role> updatedRoles = this.roleService.updateFrom(roles);
        if (updatedRoles.isEmpty()) {
            throw new PicSureResponseException(HttpStatus.BAD_REQUEST, "Invalid request", "No Role(s) has been updated.");
        }

        return PICSUREResponse.success("All Roles are updated.", RoleResponse.fromAll(updatedRoles));
    }

    @Operation(
        summary = "Delete a role that nothing references",
        description = "DELETE an Role by Id only if the Role is not associated by others."
    )
    @ApiResponses(
        {@ApiResponse(responseCode = "200", description = "The remaining roles, in the message and content envelope."),
            @ApiResponse(responseCode = "400", description = "No role with that UUID."),
            @ApiResponse(responseCode = "409", description = "Other entities still reference this role.")}
    )
    @AuditEvent(type = "ADMIN", action = "role.delete")
    @PreAuthorize("hasAnyAuthority('SUPER_ADMIN')")
    @DeleteMapping(produces = "application/json", path = "/{roleId}")
    public ResponseEntity<PicSureResponseBody<List<RoleResponse>>> removeById(
        @Parameter(required = true, description = "The uuid of the role to delete.") @PathVariable("roleId") final String roleId,
        HttpServletRequest request
    ) {
        AuditAttributes.putMetadata(request, "role_id", roleId);
        List<Role> remainingRoles = this.roleService.removeRoleById(roleId)
            .orElseThrow(() -> new PicSureResponseException(HttpStatus.BAD_REQUEST, "Invalid request", "Role not found - uuid: " + roleId));

        return PICSUREResponse.success(
            MessageFormat.format("Successfully deleted role by id: {0}, listing rest of the role(s) as below", roleId),
            RoleResponse.fromAll(remainingRoles)
        );
    }


}
