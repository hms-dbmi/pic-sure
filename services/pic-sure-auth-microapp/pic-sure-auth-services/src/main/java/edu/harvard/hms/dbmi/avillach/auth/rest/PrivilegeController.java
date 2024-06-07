package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.hms.dbmi.avillach.auth.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.model.response.PICSUREResponse;
import edu.harvard.hms.dbmi.avillach.auth.service.PrivilegeService;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.annotation.security.RolesAllowed;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming.AuthRoleNaming.ADMIN;
import static edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming.AuthRoleNaming.SUPER_ADMIN;

/**
 * <p>Endpoint for service handling business logic for privileges.
 * <br>Note: Only users with the super admin role can access this endpoint.</p>
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
    @RolesAllowed({ADMIN, SUPER_ADMIN})
    @GetMapping(path = "/{privilegeId}", produces = "application/json")
    public ResponseEntity<?> getPrivilegeById(
            @Parameter(description="The UUID of the privilege to fetch information about")
            @PathVariable("privilegeId") String privilegeId) {
        Privilege privilegeById = this.privilegeService.getPrivilegeById(privilegeId);

        if (privilegeById == null) {
            return PICSUREResponse.protocolError("Privilege not found");
        }

        return PICSUREResponse.success(privilegeById);
    }

    @Operation(description = "GET a list of existing privileges, requires ADMIN or SUPER_ADMIN role")
    @RolesAllowed({ADMIN, SUPER_ADMIN})
    @GetMapping(produces = "application/json")
    public ResponseEntity<List<Privilege>> getPrivilegeAll() {
        List<Privilege> privilegesAll = this.privilegeService.getPrivilegesAll();
        return PICSUREResponse.success(privilegesAll);
    }

    @Operation(description = "POST a list of privileges, requires SUPER_ADMIN role")
    @RolesAllowed({SUPER_ADMIN})
    @PostMapping(consumes = "application/json", produces = "application/json")
    public ResponseEntity<List<Privilege>> addPrivilege(
            @Parameter(required = true, description = "A list of privileges in JSON format")
            @RequestBody List<Privilege> privileges){
        privileges = this.privilegeService.addPrivileges(privileges);
        return PICSUREResponse.success(privileges);
    }

    @Operation(description = "Update a list of privileges, will only update the fields listed, requires SUPER_ADMIN role")
    @RolesAllowed({SUPER_ADMIN})
    @PutMapping(consumes = "application/json", produces = "application/json")
    public ResponseEntity<List<Privilege>> updatePrivilege(
            @Parameter(required = true, description = "A list of privilege with fields to be updated in JSON format")
            @RequestBody List<Privilege> privileges){
            privileges = this.privilegeService.updatePrivileges(privileges);
            return ResponseEntity.ok(privileges);
    }

    @Operation(description = "DELETE an privilege by Id only if the privilege is not associated by others, requires SUPER_ADMIN role")
    @RolesAllowed({SUPER_ADMIN})
    @DeleteMapping(path = "/{privilegeId}", produces = "application/json")
    public ResponseEntity<List<Privilege>> removeById(
            @Parameter(required = true, description = "A valid privilege Id")
            @PathVariable("privilegeId") final String privilegeId) {
        List<Privilege> privileges = this.privilegeService.deletePrivilegeByPrivilegeId(privilegeId);
        return ResponseEntity.ok(privileges);
    }

}
