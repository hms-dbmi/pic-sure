package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.hms.dbmi.avillach.auth.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.PrivilegeService;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuditAttributes;
import edu.harvard.dbmi.avillach.logging.AuditEvent;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming.AuthRoleNaming.ADMIN;
import static edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming.AuthRoleNaming.SUPER_ADMIN;

/**
 * <p>Endpoint for service handling business logic for privileges. <br>Note: Only users with the super admin role can access this
 * endpoint.</p>
 */
@Tag(name = "Privilege Management")
@RestController
@RequestMapping("/privilege")
public class PrivilegeController {

    private final PrivilegeService privilegeService;

    @Autowired
    public PrivilegeController(PrivilegeService privilegeService) {
        this.privilegeService = privilegeService;
    }

    @Operation(description = "GET information of one Privilege with the UUID, requires ADMIN or SUPER_ADMIN role")
    @AuditEvent(type = "OTHER", action = "privilege.read")
    @RolesAllowed({ADMIN, SUPER_ADMIN})
    @GetMapping(path = "/{privilegeId}", produces = "application/json")
    public Privilege getPrivilegeById(
        @Parameter(description = "The UUID of the privilege to fetch information about") @PathVariable("privilegeId") String privilegeId
    ) {
        Privilege privilegeById = this.privilegeService.getPrivilegeById(privilegeId);

        if (privilegeById == null) {
            throw new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", "Privilege not found");
        }

        return privilegeById;
    }

    @Operation(description = "GET a list of existing privileges, requires ADMIN or SUPER_ADMIN role")
    @AuditEvent(type = "OTHER", action = "privilege.list")
    @RolesAllowed({ADMIN, SUPER_ADMIN})
    @GetMapping(produces = "application/json")
    public List<Privilege> getPrivilegeAll() {
        return this.privilegeService.getPrivilegesAll();
    }

    @Operation(description = "POST a list of privileges, requires SUPER_ADMIN role")
    @AuditEvent(type = "ADMIN", action = "privilege.modify")
    @RolesAllowed({SUPER_ADMIN})
    @PostMapping(consumes = "application/json", produces = "application/json")
    public List<Privilege> addPrivilege(
        @Parameter(required = true, description = "A list of privileges in JSON format") @RequestBody List<Privilege> privileges,
        HttpServletRequest request
    ) {
        AuditAttributes.putMetadata(request, "privilege_count", String.valueOf(privileges.size()));
        return this.privilegeService.addPrivileges(privileges);
    }

    @Operation(description = "Update a list of privileges, will only update the fields listed, requires SUPER_ADMIN role")
    @AuditEvent(type = "ADMIN", action = "privilege.modify")
    @RolesAllowed({SUPER_ADMIN})
    @PutMapping(consumes = "application/json", produces = "application/json")
    public List<Privilege> updatePrivilege(
        @Parameter(
            required = true, description = "A list of privilege with fields to be updated in JSON format"
        ) @RequestBody List<Privilege> privileges, HttpServletRequest request
    ) {
        AuditAttributes.putMetadata(request, "privilege_count", String.valueOf(privileges.size()));
        return this.privilegeService.updatePrivileges(privileges);
    }

    @Operation(description = "DELETE an privilege by Id only if the privilege is not associated by others, requires SUPER_ADMIN role")
    @AuditEvent(type = "ADMIN", action = "privilege.delete")
    @RolesAllowed({SUPER_ADMIN})
    @DeleteMapping(path = "/{privilegeId}", produces = "application/json")
    public List<Privilege> removeById(
        @Parameter(required = true, description = "A valid privilege Id") @PathVariable("privilegeId") final String privilegeId,
        HttpServletRequest request
    ) {
        AuditAttributes.putMetadata(request, "privilege_id", privilegeId);
        return this.privilegeService.deletePrivilegeByPrivilegeId(privilegeId);
    }

}
