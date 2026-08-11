package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.hms.dbmi.avillach.auth.entity.Role;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.RoleService;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuditAttributes;
import edu.harvard.dbmi.avillach.logging.AuditEvent;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

import static edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming.AuthRoleNaming.ADMIN;
import static edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming.AuthRoleNaming.SUPER_ADMIN;

/**
 * <p>Endpoint for service handling business logic for user roles. <br>Note: Users with admin level access can view roles, but only super
 * admin users can modify them.</p>
 *
 * <p>The mutating endpoints answer with the affected roles and nothing else. They used to wrap that list in {@code {message: "All roles are
 * added.", content: [...]}}; the prose was never machine-readable and the status already says whether the write happened.
 */
@Tag(name = "Role Management")
@RestController
@RequestMapping("/role")
public class RoleController {

    private final RoleService roleService;

    @Autowired
    public RoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    @Operation(description = "GET information of one Role with the UUID, requires ADMIN or SUPER_ADMIN role")
    @AuditEvent(type = "OTHER", action = "role.read")
    @RolesAllowed({ADMIN, SUPER_ADMIN})
    @GetMapping(produces = "application/json", path = "/{roleId}")
    public Role getRoleById(
        @Parameter(description = "The UUID of the Role to fetch information about") @PathVariable("roleId") String roleId
    ) {
        Optional<Role> optionalRole = this.roleService.getRoleById(roleId);
        return optionalRole.orElseThrow(
            () -> new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", "Role is not found by given role ID: " + roleId)
        );
    }

    @Operation(description = "GET a list of existing Roles, requires ADMIN or SUPER_ADMIN role")
    @AuditEvent(type = "OTHER", action = "role.list")
    @RolesAllowed({ADMIN, SUPER_ADMIN})
    @GetMapping
    public List<Role> getRoleAll() {
        return this.roleService.getAllRoles();
    }

    @Operation(description = "POST a list of Roles, requires SUPER_ADMIN role")
    @AuditEvent(type = "ADMIN", action = "role.modify")
    @RolesAllowed({SUPER_ADMIN})
    @PostMapping(produces = "application/json")
    public List<Role> addRole(
        @Parameter(required = true, description = "A list of Roles in JSON format") @RequestBody List<Role> roles,
        HttpServletRequest request
    ) {
        AuditAttributes.putMetadata(request, "role_count", String.valueOf(roles.size()));
        return this.roleService.addRoles(roles);
    }

    @Operation(description = "Update a list of Roles, will only update the fields listed, requires SUPER_ADMIN role")
    @AuditEvent(type = "ADMIN", action = "role.modify")
    @RolesAllowed({SUPER_ADMIN})
    @PutMapping(produces = "application/json")
    public List<Role> updateRole(
        @Parameter(required = true, description = "A list of Roles with fields to be updated in JSON format") @RequestBody List<Role> roles,
        HttpServletRequest request
    ) {
        AuditAttributes.putMetadata(request, "role_count", String.valueOf(roles.size()));
        List<Role> updatedRoles = this.roleService.updateRoles(roles);
        if (updatedRoles.isEmpty()) {
            throw new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", "No Role(s) has been updated.");
        }

        return updatedRoles;
    }

    /** Answers with the roles that remain, as before -- the prose that used to precede them said only what the caller already knew. */
    @Operation(description = "DELETE an Role by Id only if the Role is not associated by others, requires SUPER_ADMIN role")
    @AuditEvent(type = "ADMIN", action = "role.delete")
    @RolesAllowed({SUPER_ADMIN})
    @DeleteMapping(produces = "application/json", path = "/{roleId}")
    public List<Role> removeById(
        @Parameter(required = true, description = "A valid Role Id") @PathVariable("roleId") final String roleId, HttpServletRequest request
    ) {
        AuditAttributes.putMetadata(request, "role_id", roleId);
        Optional<List<Role>> roles = this.roleService.removeRoleById(roleId);
        return roles.orElseThrow(() -> new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", "Role not found - uuid: " + roleId));
    }


}
