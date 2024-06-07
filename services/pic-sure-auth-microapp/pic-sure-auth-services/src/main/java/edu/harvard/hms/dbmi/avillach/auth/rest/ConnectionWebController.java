package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.hms.dbmi.avillach.auth.entity.Connection;
import edu.harvard.hms.dbmi.avillach.auth.model.response.PICSUREResponse;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.ConnectionWebService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming.AuthRoleNaming.ADMIN;
import static edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming.AuthRoleNaming.SUPER_ADMIN;

/**
 * <p>Endpoint for service handling business logic for connections to PSAMA. <br>
 * Note: Only users with the super admin role can access this endpoint.</p>
 */
@Tag(name = "Connection Management")
@Controller
@RequestMapping("/connection")
public class ConnectionWebController {


    private final ConnectionWebService connectionWebService;

    @Autowired
    public ConnectionWebController(ConnectionWebService connectionWebSerivce) {
        this.connectionWebService = connectionWebSerivce;
    }

    @Operation(description = "GET information of one Connection with the UUID, requires ADMIN or SUPER_ADMIN role")
    @GetMapping(path = "/{connectionId}", produces = "application/json")
    @Secured({SUPER_ADMIN, ADMIN})
    public ResponseEntity<?> getConnectionById(
            @Parameter(required = true, description = "The UUID of the Connection to fetch information about")
            @PathVariable("connectionId") String connectionId) {
        try {
            Connection connectionById = connectionWebService.getConnectionById(connectionId);
            return ResponseEntity.ok(connectionById);
        } catch (IllegalArgumentException e) {
            return PICSUREResponse.protocolError(e.getMessage());
        }
    }

    @Operation(description = "GET a list of existing Connection, requires SUPER_ADMIN or ADMIN role")
    @GetMapping(value = "/", produces = "application/json")
    @Secured({SUPER_ADMIN, ADMIN})
    public ResponseEntity<List<Connection>> getAllConnections() {
        List<Connection> allConnections = connectionWebService.getAllConnections();
        return ResponseEntity.ok(allConnections);
    }

    @Operation(description = "POST a list of Connections, requires SUPER_ADMIN role")
    @Secured({SUPER_ADMIN})
    @PostMapping(produces = "application/json", consumes = "application/json")
    public ResponseEntity<?> addConnection(
            @Parameter(required = true, description = "A list of Connections in JSON format")
            @RequestBody List<Connection> connections) {
        try {
            connections = connectionWebService.addConnection(connections);
        } catch (IllegalArgumentException e) {
            return PICSUREResponse.protocolError(e.getMessage());
        }

        return PICSUREResponse.success("All connections are added.", connections);
    }

    @Operation(description = "Update a list of Connections, will only update the fields listed, requires SUPER_ADMIN role")
    @Secured({SUPER_ADMIN})
    @PutMapping(produces = "application/json", consumes = "application/json")
    public ResponseEntity<List<Connection>> updateConnection(
            @Parameter(required = true, description = "A list of Connection with fields to be updated in JSON format")
            @RequestBody List<Connection> connections) {
        List<Connection> responseEntity = connectionWebService.updateConnections(connections);
        return ResponseEntity.ok(responseEntity);
    }

    @Operation(description = "DELETE an Connection by Id only if the Connection is not associated by others, requires SUPER_ADMIN role")
    @Secured({SUPER_ADMIN})
    @DeleteMapping(path = "/{connectionId}", produces = "application/json")
    public ResponseEntity<List<Connection>> removeById(
            @Parameter(required = true, description = "A valid connection Id")
            @PathVariable("connectionId") final String connectionId) {
        List<Connection> connections = connectionWebService.removeConnectionById(connectionId);
        return ResponseEntity.ok(connections);
    }


}
