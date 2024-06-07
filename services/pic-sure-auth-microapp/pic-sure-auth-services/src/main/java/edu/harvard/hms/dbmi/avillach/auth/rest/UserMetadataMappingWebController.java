package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.hms.dbmi.avillach.auth.entity.Connection;
import edu.harvard.hms.dbmi.avillach.auth.entity.UserMetadataMapping;
import edu.harvard.hms.dbmi.avillach.auth.model.response.PICSUREResponse;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.UserMetadataMappingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.security.RolesAllowed;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming.AuthRoleNaming.ADMIN;
import static edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming.AuthRoleNaming.SUPER_ADMIN;

/**
 * <p>Endpoint for service handling business logic for user metadata mapping.</p>
 * <p><Note: Only users with the super admin role can access this endpoint.</p>
 */
@Tag(name = "User Metadata Mapping Management")
@Controller
@RequestMapping("/mapping")
public class UserMetadataMappingWebController {

    private final UserMetadataMappingService mappingService;

    @Autowired
    public UserMetadataMappingWebController(UserMetadataMappingService mappingService) {
        this.mappingService = mappingService;
    }

    @Operation(description = "GET information of one UserMetadataMapping with the UUID, requires ADMIN or SUPER_ADMIN role")
    @RolesAllowed({ADMIN, SUPER_ADMIN})
    @GetMapping(path = "{connectionId}", produces = "application/json")
    public ResponseEntity<Connection> getMappingsForConnection(@PathVariable("connectionId") String connection) {
        Connection allMappingsForConnection = this.mappingService.getAllMappingsForConnection(connection);
        return PICSUREResponse.success(allMappingsForConnection);
    }

    @Operation(description = "GET a list of existing UserMetadataMappings, requires ADMIN or SUPER_ADMIN role")
    @RolesAllowed({ADMIN, SUPER_ADMIN})
    @GetMapping(produces = "application/json")
    public ResponseEntity<List<UserMetadataMapping>> getAllMappings() {
        List<UserMetadataMapping> allMappings = mappingService.getAllMappings();
        return PICSUREResponse.success(allMappings);
    }

    @Operation(description = "POST a list of UserMetadataMappings, requires SUPER_ADMIN role")
    @RolesAllowed({SUPER_ADMIN})
    @PostMapping(consumes = "application/json", produces = "application/json")
    public ResponseEntity<?> addMapping(
            @Parameter(required = true, description = "A list of UserMetadataMapping in JSON format")
            @RequestBody List<UserMetadataMapping> mappings) {

        try {
            List<UserMetadataMapping> userMetadataMappings = mappingService.addMappings(mappings);
            return PICSUREResponse.success(userMetadataMappings);
        } catch (IllegalArgumentException e) {
            return PICSUREResponse.error(e.getMessage());
        }
    }

    @Operation(description = "Update a list of UserMetadataMappings, will only update the fields listed, requires SUPER_ADMIN role")
    @RolesAllowed({SUPER_ADMIN})
    @PutMapping(consumes = "application/json", produces = "application/json")
    public ResponseEntity<?> updateMapping(
            @Parameter(required = true, description = "A list of UserMetadataMapping with fields to be updated in JSON format")
            @RequestBody List<UserMetadataMapping> mappings) {
        List<UserMetadataMapping> userMetadataMappings = this.mappingService.updateUserMetadataMappings(mappings);

        if (userMetadataMappings == null || userMetadataMappings.isEmpty()) {
            return PICSUREResponse.error("No UserMetadataMapping found with the given Ids");
        }

        return PICSUREResponse.success(userMetadataMappings);
    }

    @Operation(description = "DELETE an UserMetadataMapping by Id only if the UserMetadataMapping is not associated by others, requires SUPER_ADMIN role")
    @RolesAllowed({SUPER_ADMIN})
    @DeleteMapping(path = "/{mappingId}", produces = "application/json")
    public ResponseEntity<List<UserMetadataMapping>> removeById(
            @Parameter(required = true, description = "A valid UserMetadataMapping Id")
            @PathVariable("mappingId") final String mappingId) {
        List<UserMetadataMapping> userMetadataMappings = this.mappingService.removeMetadataMappingByIdAndRetrieveAll(mappingId);
        return PICSUREResponse.success(userMetadataMappings);
    }
}
