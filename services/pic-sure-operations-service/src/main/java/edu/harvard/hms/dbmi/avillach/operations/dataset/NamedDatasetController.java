package edu.harvard.hms.dbmi.avillach.operations.dataset;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Exposes {@code GET/POST /dataset/named} and {@code GET/PUT/DELETE /dataset/named/{id}}. Mappings are slash-less because the frontend uses
 * that form and Spring 6 serves exactly the declared mapping. Authorization is enforced in two layers: {@code WebSecurityConfig} requires
 * an authenticated caller for all of {@code /dataset/**} (the gateway must have supplied {@code X-User-Id}); {@link NamedDatasetService}
 * then email-scopes every operation, requiring and keying on the caller's email ({@code GatewayUser#getEmail()}), never {@code userId}.
 * This controller is a thin HTTP adapter -- identity validation lives in the service.
 */
@Tag(name = "Named datasets", description = "Saved queries a user names and reuses, scoped to the caller")
@RestController
@RequestMapping("/dataset/named")
public class NamedDatasetController {

    private final NamedDatasetService service;

    public NamedDatasetController(NamedDatasetService service) {
        this.service = service;
    }

    @Operation(summary = "List the caller's named datasets")
    @ApiResponses(
        {@ApiResponse(responseCode = "200", description = "The caller's named datasets"),
            @ApiResponse(responseCode = "401", description = "No caller email in the request")}
    )
    @GetMapping("")
    public List<NamedDatasetDto> list(GatewayUser user) {
        return service.listForUser(user);
    }

    @Operation(summary = "Name a query as a dataset for the caller")
    @ApiResponses(
        {@ApiResponse(responseCode = "201", description = "The created named dataset"),
            @ApiResponse(responseCode = "401", description = "No caller email in the request"),
            @ApiResponse(responseCode = "404", description = "Query not found"),
            @ApiResponse(responseCode = "409", description = "The caller already named that query")}
    )
    @PostMapping("")
    public ResponseEntity<NamedDatasetDto> create(GatewayUser user, @Valid @RequestBody NamedDatasetRequestDto req) {
        NamedDatasetDto created = service.create(user, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(summary = "Read one of the caller's named datasets")
    @ApiResponses(
        {@ApiResponse(responseCode = "200", description = "The named dataset"),
            @ApiResponse(responseCode = "401", description = "No caller email in the request"),
            @ApiResponse(responseCode = "404", description = "No named dataset with that id for the caller")}
    )
    @GetMapping("/{id}")
    public NamedDatasetDto get(GatewayUser user, @PathVariable("id") UUID id) {
        return service.getForUser(user, id);
    }

    @Operation(summary = "Rename or re-point one of the caller's named datasets")
    @ApiResponses(
        {@ApiResponse(responseCode = "200", description = "The updated named dataset"),
            @ApiResponse(responseCode = "401", description = "No caller email in the request"),
            @ApiResponse(responseCode = "404", description = "No named dataset with that id for the caller"),
            @ApiResponse(responseCode = "409", description = "The caller already named that query")}
    )
    @PutMapping("/{id}")
    public NamedDatasetDto update(GatewayUser user, @PathVariable("id") UUID id, @Valid @RequestBody NamedDatasetRequestDto req) {
        return service.update(user, id, req);
    }

    @Operation(summary = "Delete one of the caller's named datasets")
    @ApiResponses(
        {@ApiResponse(responseCode = "204", description = "Deleted"),
            @ApiResponse(responseCode = "401", description = "No caller email in the request"),
            @ApiResponse(responseCode = "404", description = "No named dataset with that id for the caller")}
    )
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(GatewayUser user, @PathVariable("id") UUID id) {
        service.delete(user, id);
        return ResponseEntity.noContent().build();
    }
}
