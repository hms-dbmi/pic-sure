package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.hms.dbmi.avillach.auth.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.model.request.PrivilegeCreateRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.request.PrivilegeUpdateRequest;
import edu.harvard.hms.dbmi.avillach.auth.exceptions.PicSureResponseException;
import edu.harvard.hms.dbmi.avillach.auth.model.response.PICSUREResponse;
import edu.harvard.hms.dbmi.avillach.auth.model.response.PrivilegeResponse;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.PrivilegeService;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuditAttributes;
import edu.harvard.dbmi.avillach.logging.AuditEvent;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
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

    @Operation(summary = "Read one privilege", description = "GET information of one Privilege with the UUID")
    @ApiResponse(responseCode = "200", description = "The privilege")
    @ApiResponse(responseCode = "400", description = "No privilege with that UUID")
    @AuditEvent(type = "OTHER", action = "privilege.read")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'SUPER_ADMIN')")
    @GetMapping(path = "/{privilegeId}", produces = "application/json")
    public ResponseEntity<PrivilegeResponse> getPrivilegeById(
        @Parameter(description = "The UUID of the privilege to fetch information about") @PathVariable("privilegeId") String privilegeId
    ) {
        Privilege privilegeById = this.privilegeService.getPrivilegeById(privilegeId);

        if (privilegeById == null) {
            throw new PicSureResponseException(HttpStatus.BAD_REQUEST, "Invalid request", "Privilege not found");
        }

        return PICSUREResponse.success(PrivilegeResponse.from(privilegeById));
    }

    @Operation(summary = "List every privilege", description = "GET a list of existing privileges")
    @ApiResponse(responseCode = "200", description = "Every privilege")
    @AuditEvent(type = "OTHER", action = "privilege.list")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'SUPER_ADMIN')")
    @GetMapping(produces = "application/json")
    public ResponseEntity<List<PrivilegeResponse>> getPrivilegeAll() {
        return PICSUREResponse.success(PrivilegeResponse.fromAll(this.privilegeService.getPrivilegesAll()));
    }

    @Operation(summary = "Create privileges", description = "POST a list of privileges")
    @ApiResponse(responseCode = "200", description = "The created privileges")
    @AuditEvent(type = "ADMIN", action = "privilege.modify")
    @PreAuthorize("hasAnyAuthority('SUPER_ADMIN')")
    @PostMapping(consumes = "application/json", produces = "application/json")
    public ResponseEntity<List<PrivilegeResponse>> addPrivilege(
        @Parameter(
            required = true, description = "The privileges to create, each naming its application by UUID"
        ) @RequestBody List<@NotNull @Valid PrivilegeCreateRequest> privileges, HttpServletRequest request
    ) {
        AuditAttributes.putMetadata(request, "privilege_count", String.valueOf(privileges.size()));
        return PICSUREResponse.success(PrivilegeResponse.fromAll(this.privilegeService.createFrom(privileges)));
    }

    @Operation(
        summary = "Update the given fields of privileges", description = "Update a list of privileges, will only update the fields listed"
    )
    @ApiResponse(responseCode = "200", description = "Every privilege after the update, not only the updated ones")
    @AuditEvent(type = "ADMIN", action = "privilege.modify")
    @PreAuthorize("hasAnyAuthority('SUPER_ADMIN')")
    @PutMapping(consumes = "application/json", produces = "application/json")
    public ResponseEntity<List<PrivilegeResponse>> updatePrivilege(
        @Parameter(
            required = true, description = "The privileges to update, each named by UUID; a field left out keeps its stored value"
        ) @RequestBody List<@NotNull @Valid PrivilegeUpdateRequest> privileges, HttpServletRequest request
    ) {
        AuditAttributes.putMetadata(request, "privilege_count", String.valueOf(privileges.size()));
        return ResponseEntity.ok(PrivilegeResponse.fromAll(this.privilegeService.updateFrom(privileges)));
    }

    @Operation(
        summary = "Delete a privilege that nothing references",
        description = "DELETE an privilege by Id only if the privilege is not associated by others"
    )
    @ApiResponse(responseCode = "200", description = "The remaining privileges")
    @ApiResponse(responseCode = "409", description = "Other entities still reference this privilege")
    @AuditEvent(type = "ADMIN", action = "privilege.delete")
    @PreAuthorize("hasAnyAuthority('SUPER_ADMIN')")
    @DeleteMapping(path = "/{privilegeId}", produces = "application/json")
    public ResponseEntity<List<PrivilegeResponse>> removeById(
        @Parameter(required = true, description = "A valid privilege Id") @PathVariable("privilegeId") final String privilegeId,
        HttpServletRequest request
    ) {
        AuditAttributes.putMetadata(request, "privilege_id", privilegeId);
        return ResponseEntity.ok(PrivilegeResponse.fromAll(this.privilegeService.deletePrivilegeByPrivilegeId(privilegeId)));
    }

}
