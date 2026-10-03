package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.hms.dbmi.avillach.auth.entity.UserMetadataMapping;
import edu.harvard.hms.dbmi.avillach.auth.exceptions.PicSureResponseException;
import edu.harvard.hms.dbmi.avillach.auth.model.request.UserMetadataMappingCreateRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.request.UserMetadataMappingUpdateRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.response.ConnectionResponse;
import edu.harvard.hms.dbmi.avillach.auth.model.response.PICSUREResponse;
import edu.harvard.hms.dbmi.avillach.auth.model.response.UserMetadataMappingResponse;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.UserMetadataMappingService;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuditAttributes;
import edu.harvard.dbmi.avillach.logging.AuditEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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

import java.util.List;


/**
 * <p>Endpoint for service handling business logic for user metadata mapping.</p> <p><Note: Only users with the super admin role can access
 * this endpoint.</p>
 */
@Tag(name = "User Metadata Mapping Management", description = "Mappings from identity provider claims to user metadata.")
@Controller
@RequestMapping("/mapping")
public class UserMetadataMappingWebController {

    private final UserMetadataMappingService mappingService;

    @Autowired
    public UserMetadataMappingWebController(UserMetadataMappingService mappingService) {
        this.mappingService = mappingService;
    }

    @Operation(
        summary = "The connection a mapping lookup names",
        description = "GET the Connection with the given business id. The response is the connection itself, not its mappings."
    )
    @ApiResponse(responseCode = "200", description = "The named connection.")
    @AuditEvent(type = "OTHER", action = "mapping.read")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'SUPER_ADMIN')")
    @GetMapping(path = "{connectionId}", produces = "application/json")
    public ResponseEntity<ConnectionResponse> getMappingsForConnection(@PathVariable("connectionId") String connection) {
        return PICSUREResponse.success(ConnectionResponse.from(this.mappingService.getAllMappingsForConnection(connection)));
    }

    @Operation(summary = "List every user metadata mapping", description = "GET a list of existing UserMetadataMappings.")
    @ApiResponse(responseCode = "200", description = "Every user metadata mapping.")
    @AuditEvent(type = "OTHER", action = "mapping.list")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'SUPER_ADMIN')")
    @GetMapping(produces = "application/json")
    public ResponseEntity<List<UserMetadataMappingResponse>> getAllMappings() {
        return PICSUREResponse.success(UserMetadataMappingResponse.fromAll(mappingService.getAllMappings()));
    }

    @Operation(summary = "Create mappings", description = "POST a list of UserMetadataMappings.")
    @ApiResponse(responseCode = "200", description = "The created mappings.")
    @AuditEvent(type = "ADMIN", action = "mapping.modify")
    @PreAuthorize("hasAnyAuthority('SUPER_ADMIN')")
    @PostMapping(consumes = "application/json", produces = "application/json")
    public ResponseEntity<List<UserMetadataMappingResponse>> addMapping(
        @Parameter(
            required = true, description = "The mappings to create, each naming an existing connection by its id."
        ) @RequestBody List<@NotNull @Valid UserMetadataMappingCreateRequest> mappings, HttpServletRequest request
    ) {

        AuditAttributes.putMetadata(request, "mapping_count", String.valueOf(mappings.size()));
        List<UserMetadataMapping> userMetadataMappings;
        try {
            userMetadataMappings = mappingService.createFrom(mappings);
        } catch (IllegalArgumentException e) {
            throw new PicSureResponseException(HttpStatus.INTERNAL_SERVER_ERROR, "Application error", e.getMessage());
        }
        return PICSUREResponse.success(UserMetadataMappingResponse.fromAll(userMetadataMappings));
    }

    @Operation(
        summary = "Update the given fields of mappings",
        description = "Update a list of UserMetadataMappings, will only update the fields listed."
    )
    @ApiResponse(responseCode = "200", description = "The updated mappings.")
    @AuditEvent(type = "ADMIN", action = "mapping.modify")
    @PreAuthorize("hasAnyAuthority('SUPER_ADMIN')")
    @PutMapping(consumes = "application/json", produces = "application/json")
    public ResponseEntity<List<UserMetadataMappingResponse>> updateMapping(
        @Parameter(
            required = true, description = "The mappings to update, each named by UUID; a field left out keeps its stored value."
        ) @RequestBody List<@NotNull @Valid UserMetadataMappingUpdateRequest> mappings, HttpServletRequest request
    ) {
        AuditAttributes.putMetadata(request, "mapping_count", String.valueOf(mappings.size()));
        List<UserMetadataMapping> userMetadataMappings = this.mappingService.updateFrom(mappings);

        if (userMetadataMappings == null || userMetadataMappings.isEmpty()) {
            throw new PicSureResponseException(
                HttpStatus.INTERNAL_SERVER_ERROR, "Application error", "No UserMetadataMapping found with the given Ids"
            );
        }
        return PICSUREResponse.success(UserMetadataMappingResponse.fromAll(userMetadataMappings));
    }

    @Operation(
        summary = "Delete a mapping",
        description = "DELETE an UserMetadataMapping by Id only if the UserMetadataMapping is not associated by others."
    )
    @ApiResponse(responseCode = "200", description = "The remaining mappings.")
    @AuditEvent(type = "ADMIN", action = "mapping.delete")
    @PreAuthorize("hasAnyAuthority('SUPER_ADMIN')")
    @DeleteMapping(path = "/{mappingId}", produces = "application/json")
    public ResponseEntity<List<UserMetadataMappingResponse>> removeById(
        @Parameter(required = true, description = "The uuid of the mapping to delete.") @PathVariable("mappingId") final String mappingId,
        HttpServletRequest request
    ) {
        AuditAttributes.putMetadata(request, "mapping_id", mappingId);
        return PICSUREResponse
            .success(UserMetadataMappingResponse.fromAll(this.mappingService.removeMetadataMappingByIdAndRetrieveAll(mappingId)));
    }
}
