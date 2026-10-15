package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.hms.dbmi.avillach.auth.model.request.ConnectionCreateRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.request.ConnectionUpdateRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.response.ConnectionResponse;
import edu.harvard.hms.dbmi.avillach.auth.model.response.PICSUREResponse;
import edu.harvard.hms.dbmi.avillach.auth.model.response.PicSureResponseBody;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.ConnectionWebService;
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
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.List;


/**
 * <p>Endpoint for service handling business logic for connections to PSAMA. <br> Note: Only users with the super admin role can access this
 * endpoint.</p>
 */
@Tag(name = "Connection Management", description = "Identity provider connections.")
@Controller
@RequestMapping("/connection")
public class ConnectionWebController {


    private final ConnectionWebService connectionWebService;

    @Autowired
    public ConnectionWebController(ConnectionWebService connectionWebSerivce) {
        this.connectionWebService = connectionWebSerivce;
    }

    @Operation(
        summary = "Read one connection",
        description = "Returns one connection by its business id, the same value the connection list shows as id."
    )
    @ApiResponse(responseCode = "200", description = "The connection.")
    @ApiResponse(responseCode = "400", description = "No connection with that id.")
    @AuditEvent(type = "OTHER", action = "connection.read")
    @GetMapping(path = "/{connectionId}", produces = "application/json")
    @PreAuthorize("hasAnyAuthority('SUPER_ADMIN', 'ADMIN')")
    public ResponseEntity<ConnectionResponse> getConnectionById(
        @Parameter(required = true, description = "The business id of the connection, not its uuid.") @PathVariable(
            "connectionId"
        ) String connectionId
    ) {
        return ResponseEntity.ok(ConnectionResponse.from(connectionWebService.getConnectionById(connectionId)));
    }

    @Operation(summary = "List every connection", description = "Lists every identity provider connection.")
    @ApiResponse(responseCode = "200", description = "Every connection.")
    @AuditEvent(type = "OTHER", action = "connection.list")
    @GetMapping
    @PreAuthorize("hasAnyAuthority('SUPER_ADMIN', 'ADMIN')")
    public ResponseEntity<List<ConnectionResponse>> getAllConnections() {
        return ResponseEntity.ok(ConnectionResponse.fromAll(connectionWebService.getAllConnections()));
    }

    @Operation(summary = "Create connections", description = "Creates the connections in the request body.")
    @ApiResponse(responseCode = "200", description = "The created connections, in the message and content envelope.")
    @AuditEvent(type = "ADMIN", action = "connection.modify")
    @PreAuthorize("hasAnyAuthority('SUPER_ADMIN')")
    @PostMapping(produces = "application/json", consumes = "application/json")
    public ResponseEntity<PicSureResponseBody<List<ConnectionResponse>>> addConnection(
        @Parameter(
            required = true, description = "The connections to create; the server generates each identifier."
        ) @RequestBody List<@NotNull @Valid ConnectionCreateRequest> connectionRequests, HttpServletRequest request
    ) {
        AuditAttributes.putMetadata(request, "connection_count", String.valueOf(connectionRequests.size()));
        return PICSUREResponse
            .success("All connections are added.", ConnectionResponse.fromAll(connectionWebService.createFrom(connectionRequests)));
    }

    @Operation(
        summary = "Update the given fields of connections",
        description = "Updates the connections in the request body, changing only the fields each one lists."
    )
    @ApiResponse(responseCode = "200", description = "The updated connections, as a bare array.")
    @AuditEvent(type = "ADMIN", action = "connection.modify")
    @PreAuthorize("hasAnyAuthority('SUPER_ADMIN')")
    @PutMapping(produces = "application/json", consumes = "application/json")
    public ResponseEntity<List<ConnectionResponse>> updateConnection(
        @Parameter(
            required = true, description = "The connections to update, each named by UUID; a field left out keeps its stored value."
        ) @RequestBody List<@NotNull @Valid ConnectionUpdateRequest> connections, HttpServletRequest request
    ) {
        AuditAttributes.putMetadata(request, "connection_count", String.valueOf(connections.size()));
        return ResponseEntity.ok(ConnectionResponse.fromAll(connectionWebService.updateFrom(connections)));
    }

    @Operation(
        summary = "Delete a connection that nothing references",
        description = "Deletes the connection with the given business id unless other entities still reference it, and returns the remaining connections."
    )
    @ApiResponse(responseCode = "200", description = "The remaining connections.")
    @ApiResponse(responseCode = "409", description = "Other entities still reference this connection.")
    @AuditEvent(type = "ADMIN", action = "connection.delete")
    @PreAuthorize("hasAnyAuthority('SUPER_ADMIN')")
    @DeleteMapping(path = "/{connectionId}", produces = "application/json")
    public ResponseEntity<List<ConnectionResponse>> removeById(
        @Parameter(required = true, description = "The business id of the connection to delete, not its uuid.") @PathVariable(
            "connectionId"
        ) final String connectionId, HttpServletRequest request
    ) {
        AuditAttributes.putMetadata(request, "connection_id", connectionId);
        return ResponseEntity.ok(ConnectionResponse.fromAll(connectionWebService.removeConnectionById(connectionId)));
    }


}
