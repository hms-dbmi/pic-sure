package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.hms.dbmi.avillach.auth.entity.Connection;
import edu.harvard.hms.dbmi.avillach.auth.entity.UserMetadataMapping;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.UserMetadataMappingService;
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

import static edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming.AuthRoleNaming.ADMIN;
import static edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming.AuthRoleNaming.SUPER_ADMIN;

/**
 * <p>Endpoint for service handling business logic for user metadata mapping.</p> <p><Note: Only users with the super admin role can access
 * this endpoint.</p>
 */
@Tag(name = "User Metadata Mapping Management")
@RestController
@RequestMapping("/mapping")
public class UserMetadataMappingWebController {

    private final UserMetadataMappingService mappingService;

    @Autowired
    public UserMetadataMappingWebController(UserMetadataMappingService mappingService) {
        this.mappingService = mappingService;
    }

    @Operation(description = "GET information of one UserMetadataMapping with the UUID, requires ADMIN or SUPER_ADMIN role")
    @AuditEvent(type = "OTHER", action = "mapping.read")
    @RolesAllowed({ADMIN, SUPER_ADMIN})
    @GetMapping(path = "{connectionId}", produces = "application/json")
    public Connection getMappingsForConnection(@PathVariable("connectionId") String connection) {
        return this.mappingService.getAllMappingsForConnection(connection);
    }

    @Operation(description = "GET a list of existing UserMetadataMappings, requires ADMIN or SUPER_ADMIN role")
    @AuditEvent(type = "OTHER", action = "mapping.list")
    @RolesAllowed({ADMIN, SUPER_ADMIN})
    @GetMapping(produces = "application/json")
    public List<UserMetadataMapping> getAllMappings() {
        return mappingService.getAllMappings();
    }

    /**
     * The {@code IllegalArgumentException} the service raises for an unknown connection maps to a 400 with its message intact in
     * {@link edu.harvard.hms.dbmi.avillach.auth.exceptions.GlobalExceptionHandler}. It used to be caught here and answered as a 500, which
     * told the caller a bad request was PSAMA's fault.
     */
    @Operation(description = "POST a list of UserMetadataMappings, requires SUPER_ADMIN role")
    @AuditEvent(type = "ADMIN", action = "mapping.modify")
    @RolesAllowed({SUPER_ADMIN})
    @PostMapping(consumes = "application/json", produces = "application/json")
    public List<UserMetadataMapping> addMapping(
        @Parameter(
            required = true, description = "A list of UserMetadataMapping in JSON format"
        ) @RequestBody List<UserMetadataMapping> mappings, HttpServletRequest request
    ) {
        AuditAttributes.putMetadata(request, "mapping_count", String.valueOf(mappings.size()));
        return mappingService.addMappings(mappings);
    }

    @Operation(description = "Update a list of UserMetadataMappings, will only update the fields listed, requires SUPER_ADMIN role")
    @AuditEvent(type = "ADMIN", action = "mapping.modify")
    @RolesAllowed({SUPER_ADMIN})
    @PutMapping(consumes = "application/json", produces = "application/json")
    public List<UserMetadataMapping> updateMapping(
        @Parameter(
            required = true, description = "A list of UserMetadataMapping with fields to be updated in JSON format"
        ) @RequestBody List<UserMetadataMapping> mappings, HttpServletRequest request
    ) {
        AuditAttributes.putMetadata(request, "mapping_count", String.valueOf(mappings.size()));
        List<UserMetadataMapping> userMetadataMappings = this.mappingService.updateUserMetadataMappings(mappings);

        if (userMetadataMappings == null || userMetadataMappings.isEmpty()) {
            throw new PicsureException(
                HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "No UserMetadataMapping found with the given Ids"
            );
        }
        return userMetadataMappings;
    }

    @Operation(
        description = "DELETE an UserMetadataMapping by Id only if the UserMetadataMapping is not associated by others, "
            + "requires SUPER_ADMIN role"
    )
    @AuditEvent(type = "ADMIN", action = "mapping.delete")
    @RolesAllowed({SUPER_ADMIN})
    @DeleteMapping(path = "/{mappingId}", produces = "application/json")
    public List<UserMetadataMapping> removeById(
        @Parameter(required = true, description = "A valid UserMetadataMapping Id") @PathVariable("mappingId") final String mappingId,
        HttpServletRequest request
    ) {
        AuditAttributes.putMetadata(request, "mapping_id", mappingId);
        return this.mappingService.removeMetadataMappingByIdAndRetrieveAll(mappingId);
    }
}
